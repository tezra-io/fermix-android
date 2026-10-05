#!/usr/bin/env bash
#
# promote.yml's publish (MILESTONE_51_ANDROID_CI_CD.md section 4.3), once the owner approved it in the release
# environment: the tag's one draft release becomes the release, and nothing is rebuilt (C7). Between the
# evidence job and the approval, someone with write access could have changed the draft, so before anything is
# published the draft is checked again whole, as after-publish checks the release (check_release.sh): its five
# files, cosign's signature of SHA256SUMS, the evidence's APK and the release certificate. The release is then
# published by the id of the draft that was checked, and read back; a draft replaced since has another id, and
# the publish fails. Someone could still swap a file of the draft, delete it and upload another under its name,
# between the check and the publish: the swapped file has another asset id, so the release's assets are held
# to those checked just before the publish, when a change publishes nothing, and once more after it, when the
# release is made a draft again at once and the publish fails, after seconds in public. GitHub's immutable
# releases, a repository setting, would close that window (docs/RELEASING.md).
#
# It asks GitHub through gh (GH_TOKEN, one that can push, as GitHub shows a draft to no other) about REPO
# (owner/name). Exits 1 on a refusal, 2 on a missing tool or argument.
#
#   publish_release.sh <vX.Y.Z> <apk-sha256> <android_signers.json>
set -euo pipefail
shopt -s inherit_errexit

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The scratch directory main makes for the draft's files, which the EXIT trap removes.
work=""

fatal() {
  echo "publish_release: $*" >&2
  exit 2
}

refuse() {
  echo "publish_release: $*" >&2
  exit 1
}

# Whether release [id]'s assets are still those check_release.sh wrote to [checked]: id, name and size.
same_assets() {
  local id=$1 checked=$2 now
  now="$(gh api "repos/$REPO/releases/$id" --jq '[.assets[] | {id, name, size}] | sort_by(.name)')" ||
    fatal "gh could not read release $id"
  [ "$(jq -c . <<<"$now")" = "$(jq -c . "$checked")" ]
}

main() {
  [ $# -eq 3 ] || { echo "usage: publish_release.sh <vX.Y.Z> <apk-sha256> <android_signers.json>" >&2; exit 2; }
  local tag=$1 expected=$2 signers=$3 tool releases id draft_now
  [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || fatal "'$tag' is not a release tag vMAJOR.MINOR.PATCH"
  [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || fatal "'$expected' is not a sha256"
  for tool in gh jq mktemp; do command -v "$tool" >/dev/null || fatal "no $tool on the PATH"; done
  [[ "${REPO:-}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || fatal "REPO names no owner/repository"
  releases="$(gh api --paginate --slurp "repos/$REPO/releases?per_page=100")" ||
    fatal "gh could not list $REPO's releases"
  id="$(jq -r --arg tag "$tag" 'add // [] | map(select(.tag_name == $tag))
    | if length == 1 and .[0].draft then .[0].id else empty end' <<<"$releases")"
  [ -n "$id" ] || refuse "$REPO has no one draft release for $tag to publish"
  work="$(mktemp -d)" || fatal "could not make a scratch directory"
  trap 'rm -rf -- "${work:?}"' EXIT
  "$SCRIPTS_DIR/check_release.sh" "$tag" draft "$expected" "$signers" "$work/files" "$work/checked.json"
  same_assets "$id" "$work/checked.json" ||
    refuse "release $id's files changed after they were checked; nothing is published"
  gh api -X PATCH "repos/$REPO/releases/$id" -F draft=false >/dev/null || fatal "gh could not publish release $id"
  if ! same_assets "$id" "$work/checked.json"; then
    gh api -X PATCH "repos/$REPO/releases/$id" -F draft=true >/dev/null ||
      fatal "release $id's files changed as it was published, and gh could not make it a draft again: do it now"
    refuse "release $id's files changed as it was published; it is a draft again, as no one checked what it holds"
  fi
  draft_now="$(gh api "repos/$REPO/releases/$id" --jq '.draft')" || fatal "gh could not read release $id back"
  [ "$draft_now" = false ] || fatal "release $id is still a draft after it was published"
  echo "publish_release: $tag is published, its APK $expected"
}

main "$@"
