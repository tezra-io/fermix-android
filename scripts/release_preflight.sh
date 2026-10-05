#!/usr/bin/env bash
#
# candidate.yml's preflight (MILESTONE_51_ANDROID_CI_CD.md section 4.1): before anything is built, refuse a
# release tag when
#
#   1. the tagged commit is not on main (C1): a release is the merge commit of its release pull request
#   2. a release with this tag exists already: a published one is immutable, and a draft means this tag
#      built its one candidate (C7), which is deleted by hand before the tag may build another
#   3. the tag is not the commit's versionName: the tag must name this commit, and the build names a commit
#      by its nearest release tag (build-logic's AppVersion.kt, the same describe), which must be this one
#   4. the commit's versionCode is not above every other release tag's (section 4.4), so Android would
#      refuse it over an earlier release
#   5. contracts/SOURCE.json names no published engine release, or one built from another commit than the
#      one the contract was taken from (C4: the daemon ships first)
#   6. the protocol the app speaks, core-session's SESSION_VERSION, is outside the mobile contract's
#      supported_version_range in SOURCE.json: the pinned engine release does not serve it, and the app
#      would pair with no released engine (design D1; the publishing checklist's "never ship an app build
#      that requires a protocol the released engine lacks")
#   7. the commit's CHANGELOG.md has no entry for the version (changelog_entry.sh)
#
# It reads the commit's own files, never the working tree, from a checkout with the full history and
# origin/main (actions/checkout, fetch-depth: 0), and asks GitHub through gh (GH_TOKEN) about this
# repository's releases, REPO (owner/name), and the engine's. Every refusal is printed, then it exits 1; a
# missing tool or a malformed argument stops it at once with status 2.
#
#   release_preflight.sh <vX.Y.Z> <commit>
set -euo pipefail
shopt -s inherit_errexit

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The one repository whose release the vendored contract must be (C4), as verify_protocol_contract.sh fixes it.
ENGINE_REPOSITORY="tezra-io/fermix"
MAIN_REF="refs/remotes/origin/main"
# build-logic's RELEASE_TAG_GLOB: the tags describe takes for a release tag.
RELEASE_TAG_GLOB="v[0-9]*.[0-9]*.[0-9]*"
RELEASE_TAG='^v[0-9]+\.[0-9]+\.[0-9]+$'
# More release tags than this is not a repository this script was written for; it stops rather than guess.
MAX_RELEASE_TAGS=1000
# Where the app declares the protocol it speaks, the one line "internal const val SESSION_VERSION = <n>".
SESSION_SOURCE="core-session/src/main/kotlin/io/tezra/fermix/session/SecureChannel.kt"
TOOLS="awk gh git jq paste sed"

fatal() {
  echo "release_preflight: $*" >&2
  exit 2
}

refusal() {
  echo "release_preflight: $*"
}

# The versionCode of version.properties at [revision], or nothing when it has no such file or line, which each
# caller refuses in its own words.
version_code_at() {
  local text
  text="$(git show "$1:version.properties" 2>/dev/null)" || return 0
  sed -n 's/^versionCode=\([0-9][0-9]*\)$/\1/p' <<<"$text"
}

# 1. On main.
check_on_main() {
  local commit=$1 status=0
  git merge-base --is-ancestor "$commit" "$MAIN_REF" || status=$?
  case "$status" in
    0) ;;
    1) refusal "the tagged commit $commit is not on main: merge the release pull request and tag its merge commit" ;;
    *) fatal "git could not decide whether $commit is on main (status $status)" ;;
  esac
}

# 2. No release, published or draft, has the tag.
check_no_release() {
  local tag=$1 releases states
  releases="$(gh api --paginate --slurp "repos/$REPO/releases?per_page=100")" ||
    fatal "gh could not list $REPO's releases"
  states="$(jq -r --arg tag "$tag" \
    'add // [] | map(select(.tag_name == $tag)) | .[] | if .draft then "draft" else "published" end' <<<"$releases")"
  case "$states" in
    "") ;;
    draft) refusal "a draft release for $tag exists: a tag builds one candidate (C7); delete it to build again" ;;
    published) refusal "the $tag release is published already, and a published release is never replaced" ;;
    *) refusal "$REPO has more than one release for $tag: $(paste -sd ' ' - <<<"$states")" ;;
  esac
}

# 3. The tag names the commit, and the build would name the commit by the tag.
check_tag_is_version_name() {
  local tag=$1 commit=$2 tagged described
  tagged="$(git rev-parse --verify --quiet "refs/tags/$tag^{commit}")" || tagged=""
  [ "$tagged" = "$commit" ] || refusal "the tag $tag names ${tagged:-no commit here}, not $commit"
  described="$(git describe --tags --abbrev=0 --match "$RELEASE_TAG_GLOB" --always "$commit")" ||
    fatal "git describe failed for $commit"
  [ "$described" = "$tag" ] ||
    refusal "the build names $commit by its nearest release tag, $described, not $tag, so not ${tag#v}"
}

# 4. The versionCode is above every other release tag's.
check_code_raised() {
  local tag=$1 commit=$2 code tags count other other_code
  code="$(version_code_at "$commit")"
  [ -n "$code" ] || { refusal "version.properties at $commit holds no versionCode"; return 0; }
  # for-each-ref, git's plumbing, prints a tag a line whatever the machine's configuration; git tag's list is laid out
  # in columns, several tags to a line, under column.ui = always.
  tags="$(git for-each-ref --format='%(refname:strip=2)' "refs/tags/$RELEASE_TAG_GLOB" |
    awk -v tag="$tag" '/^v[0-9]+[.][0-9]+[.][0-9]+$/ && $0 != tag')"
  count="$(awk 'END { print NR }' <<<"$tags")"
  [ "$count" -le "$MAX_RELEASE_TAGS" ] || fatal "$count release tags, past the $MAX_RELEASE_TAGS this script reads"
  for other in $tags; do
    other_code="$(version_code_at "$other")"
    [ -n "$other_code" ] || fatal "version.properties at $other holds no versionCode to compare with"
    [ "$code" -gt "$other_code" ] ||
      refusal "versionCode $code at $tag is not above $other's $other_code: the release pull request raises it"
  done
}

# 5. SOURCE.json names a published engine release, built from the commit the contract was taken from.
check_engine_release() {
  local commit=$1 source release pinned published release_commit
  source="$(git show "$commit:contracts/SOURCE.json")" || fatal "no contracts/SOURCE.json at $commit"
  release="$(jq -r '.upstream.release // ""' <<<"$source")"
  pinned="$(jq -r '.upstream.commit // ""' <<<"$source")"
  if ! [[ "$release" =~ $RELEASE_TAG ]]; then
    refusal "contracts/SOURCE.json names no engine release as upstream.release (found '$release'); C4 wants one"
    return 0
  fi
  # GitHub answers a tag's release only once it is published; gh's own words say why it found none.
  if ! published="$(gh api "repos/$ENGINE_REPOSITORY/releases/tags/$release" \
    --jq '(.draft | not) and (.prerelease | not)' 2>&1)"; then
    refusal "$ENGINE_REPOSITORY has no published release $release (C4): $published"
    return 0
  fi
  [ "$published" = "true" ] || { refusal "$ENGINE_REPOSITORY's $release is a draft or a prerelease (C4)"; return 0; }
  release_commit="$(gh api "repos/$ENGINE_REPOSITORY/commits/$release" --jq .sha)" ||
    fatal "gh could not read the commit of $ENGINE_REPOSITORY's $release"
  [ "$release_commit" = "$pinned" ] ||
    refusal "$ENGINE_REPOSITORY's $release is $release_commit, but contracts/SOURCE.json pins the contract at $pinned"
}

# 6. The pinned engine release serves the protocol the app speaks.
check_protocol_served() {
  local commit=$1 text speaks source window minimum maximum release
  if ! git cat-file -e "$commit:$SESSION_SOURCE" 2>/dev/null; then
    refusal "there is no $SESSION_SOURCE at $commit to say which protocol the app speaks"
    return 0
  fi
  text="$(git show "$commit:$SESSION_SOURCE")" || fatal "git could not read $SESSION_SOURCE at $commit"
  speaks="$(sed -n 's/^internal const val SESSION_VERSION = \([0-9][0-9]*\)$/\1/p' <<<"$text")"
  if [ -z "$speaks" ] || [ "$(awk 'END { print NR }' <<<"$speaks")" -ne 1 ]; then
    refusal "$SESSION_SOURCE at $commit declares no SESSION_VERSION, the protocol the app speaks, on one line"
    return 0
  fi
  source="$(git show "$commit:contracts/SOURCE.json")" || fatal "no contracts/SOURCE.json at $commit"
  window="$(jq -r '[.contracts[]? | select(.name == "mobile") | .supported_version_range
    | "\(.minimum) \(.maximum)"] | if length == 1 then .[0] else "" end' <<<"$source")"
  release="$(jq -r '.upstream.release // "the pinned engine"' <<<"$source")"
  if ! [[ "$window" =~ ^[0-9]+\ [0-9]+$ ]]; then
    refusal "contracts/SOURCE.json records no one mobile contract with its supported_version_range"
    return 0
  fi
  read -r minimum maximum <<<"$window"
  if [ "$speaks" -lt "$minimum" ] || [ "$speaks" -gt "$maximum" ]; then
    refusal "the app speaks mobile protocol $speaks, which $ENGINE_REPOSITORY $release does not serve (its window" \
      "is $minimum to $maximum): release the engine that serves it and re-vendor the contract first"
  fi
}

# 7. The changelog has the version's entry.
check_changelog() {
  local tag=$1 commit=$2 why
  if ! why="$(git show "$commit:CHANGELOG.md" | "$SCRIPTS_DIR/changelog_entry.sh" "${tag#v}" 2>&1 >/dev/null)"; then
    refusal "at $commit: $why"
  fi
}

run_checks() {
  local tag=$1 commit=$2
  check_on_main "$commit"
  check_no_release "$tag"
  check_tag_is_version_name "$tag" "$commit"
  check_code_raised "$tag" "$commit"
  check_engine_release "$commit"
  check_protocol_served "$commit"
  check_changelog "$tag" "$commit"
}

main() {
  [ $# -eq 2 ] || { echo "usage: release_preflight.sh <vX.Y.Z> <commit>" >&2; exit 2; }
  local tag=$1 commit=$2 tool report
  for tool in $TOOLS; do command -v "$tool" >/dev/null || fatal "no $tool on the PATH"; done
  [[ "$tag" =~ $RELEASE_TAG ]] || fatal "'$tag' is not a release tag vMAJOR.MINOR.PATCH (C7: no -rc tags)"
  [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fatal "'$commit' is not a full commit id"
  [[ "${REPO:-}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || fatal "REPO names no owner/repository"
  [ "$(git rev-parse --is-shallow-repository)" = false ] ||
    fatal "the checkout is shallow; check out with fetch-depth: 0 so that main and the earlier tags are here"
  git rev-parse --verify --quiet "$MAIN_REF^{commit}" >/dev/null || fatal "the checkout has no $MAIN_REF"
  git cat-file -e "$commit^{commit}" 2>/dev/null || fatal "the commit $commit is not in the checkout"
  report="$(run_checks "$tag" "$commit")"
  if [ -n "$report" ]; then
    echo "$report" >&2
    echo "release_preflight: $tag refused" >&2
    exit 1
  fi
  echo "release_preflight: $tag at $commit is on main, unreleased, versionName ${tag#v} with versionCode" \
    "$(version_code_at "$commit"), on a published engine release that serves its protocol, with its changelog entry"
}

main "$@"
