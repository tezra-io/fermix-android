#!/usr/bin/env bash
#
# promote.yml's check of a release's files (MILESTONE_51_ANDROID_CI_CD.md section 4.3): publish runs it on the
# tag's draft before the draft becomes the release, as someone with write access could have changed the draft
# since the evidence job, and after-publish and the play job's first step on the published release. It
# downloads the tag's one release in the state named, a draft or published, into <dir>, and refuses it unless
#
#   1. it holds exactly the five files candidate.yml staged
#   2. cosign verifies SHA256SUMS's bundle as made by candidate.yml on this tag, keyless, with GitHub
#      Actions' issuer (C9), and the three files hash to SHA256SUMS, which lists them and nothing else
#   3. the APK is the one the evidence names (its sha256 from the evidence job)
#   4. the APK and the bundle are signed by the release entry of android_signers.json (sign_check.sh), the
#      tag's contracts/mobile/android_signers.json as promote.yml passes it
#
# so what is published, what the public downloads and what Play receives are the files the owner tested. Given
# <checked>, it writes there the id, name and size of each asset it checked, for publish_release.sh: an asset's
# bytes never change under its id, so a file swapped since has another. It asks GitHub through gh (GH_TOKEN)
# about REPO (owner/name); GitHub shows a draft only to a token that can push, contents: write, as publish's
# can. Exits 1 on a refusal, 2 on a missing tool or argument.
#
#   check_release.sh <vX.Y.Z> <draft|published> <apk-sha256> <android_signers.json> <dir> [<checked>]
set -euo pipefail
shopt -s inherit_errexit

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ISSUER="https://token.actions.githubusercontent.com"
USAGE="usage: check_release.sh <vX.Y.Z> <draft|published> <apk-sha256> <android_signers.json> <dir> [<checked>]"

fatal() {
  echo "check_release: $*" >&2
  exit 2
}

refuse() {
  echo "check_release: $*" >&2
  exit 1
}

# The one release of [tag] in [state], a draft or published, as GitHub describes it.
one_release() {
  local tag=$1 state=$2 releases release
  releases="$(gh api --paginate --slurp "repos/$REPO/releases?per_page=100")" ||
    fatal "gh could not list $REPO's releases"
  release="$(jq -c --arg tag "$tag" --argjson draft "$([ "$state" = draft ] && echo true || echo false)" \
    'add // [] | map(select(.tag_name == $tag)) | if length == 1 and .[0].draft == $draft then .[0] else empty end' \
    <<<"$releases")"
  [ -n "$release" ] || refuse "$REPO has no one $state release for $tag"
  echo "$release"
}

# Each of [names] from [release] into [dir], by asset id; the release holds them and nothing else.
download() {
  local release=$1 dir=$2 names=$3 held name id
  held="$(jq -r '.assets[].name' <<<"$release" | LC_ALL=C sort)"
  [ "$held" = "$(LC_ALL=C sort <<<"$names")" ] ||
    refuse "the release holds $(paste -sd ' ' - <<<"$held"), not the five files candidate.yml staged"
  for name in $names; do
    id="$(jq -r --arg name "$name" '.assets[] | select(.name == $name) | .id' <<<"$release")"
    gh api -H "Accept: application/octet-stream" "repos/$REPO/releases/assets/$id" >"$dir/$name" ||
      fatal "gh could not download $name"
  done
}

check_signed_sums() {
  local tag=$1 dir=$2 version=$3 identity listed
  identity="https://github.com/$REPO/.github/workflows/candidate.yml@refs/tags/$tag"
  cosign verify-blob --bundle "$dir/SHA256SUMS.cosign.bundle" --certificate-identity "$identity" \
    --certificate-oidc-issuer "$ISSUER" "$dir/SHA256SUMS" ||
    refuse "cosign does not verify SHA256SUMS as signed by $identity"
  listed="$(awk '{ print $2 }' "$dir/SHA256SUMS" | LC_ALL=C sort | paste -sd ' ' -)"
  [ "$listed" = "fermix-android-$version-mapping.txt fermix-android-$version.aab fermix-android-$version.apk" ] ||
    refuse "SHA256SUMS lists $listed, not the three files"
  (cd "$dir" && sha256sum --check --strict --quiet SHA256SUMS) || refuse "the files do not hash to SHA256SUMS"
}

main() {
  [ $# -eq 5 ] || [ $# -eq 6 ] || { echo "$USAGE" >&2; exit 2; }
  local tag=$1 state=$2 expected=$3 signers=$4 dir=$5 checked=${6:-} version release apk found tool
  [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || fatal "'$tag' is not a release tag vMAJOR.MINOR.PATCH"
  [[ "$state" =~ ^(draft|published)$ ]] || fatal "'$state' is neither draft nor published"
  [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || fatal "'$expected' is not a sha256"
  for tool in awk cosign gh jq paste sha256sum sort; do
    command -v "$tool" >/dev/null || fatal "no $tool on the PATH"
  done
  [[ "${REPO:-}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || fatal "REPO names no owner/repository"
  version="${tag#v}"
  apk="fermix-android-$version.apk"
  mkdir -p "$dir"
  release="$(one_release "$tag" "$state")"
  download "$release" "$dir" "$(printf '%s\n' "$apk" "fermix-android-$version.aab" \
    "fermix-android-$version-mapping.txt" SHA256SUMS SHA256SUMS.cosign.bundle)"
  check_signed_sums "$tag" "$dir" "$version"
  found="$(sha256sum "$dir/$apk" | awk '{ print $1 }')"
  [ "$found" = "$expected" ] || refuse "the $state APK is $found, not $expected as the evidence names"
  "$SCRIPTS_DIR/sign_check.sh" "$signers" "$dir/$apk" "$dir/fermix-android-$version.aab"
  if [ -n "$checked" ]; then
    jq -c '[.assets[] | {id, name, size}] | sort_by(.name)' <<<"$release" >"$checked"
  fi
  echo "check_release: $tag's $state files are the signed, tested candidate's"
}

main "$@"
