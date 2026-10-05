#!/usr/bin/env bash
#
# candidate.yml's stage (MILESTONE_51_ANDROID_CI_CD.md section 4.1): the verified files become a draft release,
# which only people with write access see, holding the universal APK, the app bundle, R8's mapping,
# SHA256SUMS and its cosign bundle (C9), and nothing else.
#
#   1. SHA256SUMS, as verify wrote it, holds the three files and they still hash to it, so the staged bytes are
#      the verified ones (C7)
#   2. no release has the tag yet
#   3. cosign signs SHA256SUMS keyless, as the workflow's own identity, candidate.yml on this tag, and the
#      signature is verified against that identity before anything is staged
#   4. the draft's notes are the version's CHANGELOG entry, the protocol the app speaks (core-session's
#      SESSION_VERSION) with the engine release the contract is from, and how to verify a download
#
# It asks GitHub through gh (GH_TOKEN) about REPO (owner/name), and reads CHANGELOG.md, contracts/SOURCE.json
# and core-session's SecureChannel.kt from the current directory, the tag's checkout. Exits 1 on a refusal, 2 on
# a missing tool or argument.
#
#   stage_candidate.sh <vX.Y.Z> <dir>
set -euo pipefail
shopt -s inherit_errexit

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ISSUER="https://token.actions.githubusercontent.com"
# Where the app declares the protocol it speaks, as release_preflight.sh reads it.
SESSION_SOURCE="core-session/src/main/kotlin/io/tezra/fermix/session/SecureChannel.kt"

fatal() {
  echo "stage_candidate: $*" >&2
  exit 2
}

refuse() {
  echo "stage_candidate: $*" >&2
  exit 1
}

check_sums() {
  local dir=$1 version=$2 listed expected
  listed="$(awk '{ print $2 }' "$dir/SHA256SUMS" | LC_ALL=C sort)"
  expected="$(printf '%s\n' "fermix-android-$version-mapping.txt" "fermix-android-$version.aab" \
    "fermix-android-$version.apk" | LC_ALL=C sort)"
  [ "$listed" = "$expected" ] || refuse "SHA256SUMS lists $(paste -sd ' ' - <<<"$listed"), not the three files"
  (cd "$dir" && sha256sum --check --strict --quiet SHA256SUMS) || refuse "the files are not the ones verify hashed"
}

check_no_release() {
  local tag=$1 count
  count="$(gh api --paginate --slurp "repos/$REPO/releases?per_page=100" |
    jq --arg tag "$tag" 'add // [] | map(select(.tag_name == $tag)) | length')" ||
    fatal "gh could not list $REPO's releases"
  [ "$count" -eq 0 ] || refuse "$REPO has a release for $tag already; a tag stages one candidate (C7)"
}

sign_sums() {
  local dir=$1 identity=$2
  cosign sign-blob --yes --bundle "$dir/SHA256SUMS.cosign.bundle" "$dir/SHA256SUMS"
  cosign verify-blob --bundle "$dir/SHA256SUMS.cosign.bundle" --certificate-identity "$identity" \
    --certificate-oidc-issuer "$ISSUER" "$dir/SHA256SUMS" || refuse "cosign does not verify what it just signed"
}

# The protocol the app speaks, which preflight held to the pinned engine release's window; fatal outside it.
app_protocol() {
  local speaks window
  speaks="$(sed -n 's/^internal const val SESSION_VERSION = \([0-9][0-9]*\)$/\1/p' "$SESSION_SOURCE")"
  [[ "$speaks" =~ ^[0-9]+$ ]] || fatal "$SESSION_SOURCE declares no SESSION_VERSION on one line"
  window="$(jq -r '.contracts[] | select(.name == "mobile") | .supported_version_range
    | "\(.minimum) \(.maximum)"' contracts/SOURCE.json)"
  [[ "$window" =~ ^[0-9]+\ [0-9]+$ ]] || fatal "contracts/SOURCE.json records no one mobile protocol window"
  [ "$speaks" -ge "${window% *}" ] && [ "$speaks" -le "${window#* }" ] ||
    fatal "the app speaks protocol $speaks, outside the pinned engine's window $window, which preflight refuses"
  echo "$speaks"
}

write_notes() {
  local version=$1 identity=$2 notes=$3 engine protocol
  engine="$(jq -r '.upstream.release' contracts/SOURCE.json)"
  protocol="$(app_protocol)"
  {
    "$SCRIPTS_DIR/changelog_entry.sh" "$version" <CHANGELOG.md
    printf '\n### Engine\n\nSpeaks mobile protocol %s, which the Fermix engine %s serves, and pairs with that\n' \
      "$protocol" "$engine"
    printf 'release and the later ones that serve protocol %s.\n' "$protocol"
    printf '\n### Verifying a download\n\n```bash\ncosign verify-blob --bundle SHA256SUMS.cosign.bundle \\\n'
    printf '  --certificate-identity %s \\\n' "$identity"
    printf '  --certificate-oidc-issuer %s SHA256SUMS\nsha256sum --check --ignore-missing SHA256SUMS\n```\n' "$ISSUER"
  } >"$notes"
}

main() {
  [ $# -eq 2 ] || { echo "usage: stage_candidate.sh <vX.Y.Z> <dir>" >&2; exit 2; }
  local tag=$1 dir=$2 version identity tool
  [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || fatal "'$tag' is not a release tag vMAJOR.MINOR.PATCH"
  for tool in awk cosign gh jq paste sha256sum sort; do
    command -v "$tool" >/dev/null || fatal "no $tool on the PATH"
  done
  [[ "${REPO:-}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || fatal "REPO names no owner/repository"
  [ -f "$dir/SHA256SUMS" ] || fatal "no $dir/SHA256SUMS: verify writes it"
  version="${tag#v}"
  identity="https://github.com/$REPO/.github/workflows/candidate.yml@refs/tags/$tag"
  check_sums "$dir" "$version"
  check_no_release "$tag"
  sign_sums "$dir" "$identity"
  write_notes "$version" "$identity" "$dir/notes.md"
  gh release create "$tag" --repo "$REPO" --draft --verify-tag --title "Fermix for Android $version" \
    --notes-file "$dir/notes.md" \
    "$dir/fermix-android-$version.apk" "$dir/fermix-android-$version.aab" "$dir/fermix-android-$version-mapping.txt" \
    "$dir/SHA256SUMS" "$dir/SHA256SUMS.cosign.bundle"
  echo "stage_candidate: $tag is a draft release, signed as $identity"
}

main "$@"
