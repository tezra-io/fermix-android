#!/usr/bin/env bash
#
# The signer check of candidate.yml's sign and, through check_release.sh, of promote.yml's publish, play and
# after-publish (MILESTONE_51_ANDROID_CI_CD.md C8, section 4.1): a candidate's APK and its app bundle are each
# signed by one certificate, and that certificate's SHA-256 is the release entry of the engine's
# android_signers.json, as vendored at contracts/mobile/android_signers.json. That is the digest a daemon reads
# from the key attestation (design D4), so a candidate signed by any other key, a development key or the SDK's
# included, is refused, and so is one whose package is not the file's. The file joins the engine's export in
# stage D2 (section 5, E1); until a re-vendor brings it, every candidate is refused here.
#
# The file is held to E1's shape: v 1, a package, and signers of lower-case 64-hex digests with one release
# entry and no digest twice. apksigner reads the APK's signature at minSdk 35 (design D18), and jarsigner and
# keytool the bundle's, which Play takes as its upload key's (docs/RELEASING.md). Exits 1 on a refusal, 2 on
# a missing tool or argument.
#
#   sign_check.sh <android_signers.json> <apk> <aab>
set -euo pipefail
shopt -s inherit_errexit

BUILD_TOOLS_VERSION="36.0.0"
MIN_SDK=35

fatal() {
  echo "sign_check: $*" >&2
  exit 2
}

refuse() {
  echo "sign_check: $*" >&2
  exit 1
}

# The release entry's digest, once the file is held to E1's shape.
release_digest() {
  local signers=$1 problems
  problems="$(jq -r '
    def hex64: type == "string" and test("^[0-9a-f]{64}$");
    [ (if .v != 1 then "v is not 1" else empty end),
      (if (.package | type) != "string" then "no package" else empty end),
      (if (.signers | type) != "array" then "no signers list" else empty end),
      (.signers // [] | map(select(.sha256 | hex64 | not)) | length
        | if . > 0 then "a digest that is not lower-case 64 hex" else empty end),
      (.signers // [] | map(select(.role == "release")) | length
        | if . != 1 then "\(.) release entries, not one" else empty end),
      (.signers // [] | map(.sha256) | if length != (unique | length) then "a digest listed twice" else empty end)
    ] | .[]' "$signers")" || refuse "$signers could not be read as E1 shapes it"
  [ -z "$problems" ] || refuse "$signers is not shaped as E1 says: $(paste -sd ';' - <<<"$problems")"
  jq -r '.signers[] | select(.role == "release") | .sha256' "$signers"
}

# [digests] when they are one, else a refusal for [file]: a second signer or a rotated key is no release.
one_digest() {
  local file=$1 digests=$2
  if [ -z "$digests" ] || [ "$(awk 'END { print NR }' <<<"$digests")" -ne 1 ]; then
    refuse "$file is not signed by exactly one certificate: found ${digests:-none}"
  fi
  echo "$digests"
}

# The one certificate digest apksigner reads from the APK's verified signature.
apk_digest() {
  local apk=$1 printed digests
  printed="$("$APKSIGNER" verify --print-certs --min-sdk-version "$MIN_SDK" "$apk" 2>&1)" ||
    refuse "$apk does not verify: $printed"
  digests="$(sed -n 's/^.*certificate SHA-256 digest: \([0-9a-f]\{64\}\)$/\1/p' <<<"$printed")"
  one_digest "$apk" "$digests"
}

# The one certificate digest of the bundle's JAR signature, once jarsigner verified it.
aab_digest() {
  local aab=$1 verified printed digests
  verified="$(jarsigner -verify "$aab" 2>&1)" || refuse "$aab does not verify: $verified"
  grep -qx "jar verified." <<<"$verified" || refuse "$aab is not signed: $(head -3 <<<"$verified" | paste -sd ' ' -)"
  # jarsigner verifies a jar some of whose entries no signature covers, and only warns of them.
  if grep -q "unsigned entries" <<<"$verified"; then refuse "$aab holds entries its signature does not cover"; fi
  printed="$(keytool -printcert -jarfile "$aab")" || refuse "keytool could not read the signer of $aab"
  digests="$(sed -n 's/^[[:space:]]*SHA256: \([0-9A-F:]*\)$/\1/p' <<<"$printed" | tr -d ':' | tr 'A-F' 'a-f')"
  one_digest "$aab" "$digests"
}

main() {
  [ $# -eq 3 ] || { echo "usage: sign_check.sh <android_signers.json> <apk> <aab>" >&2; exit 2; }
  local signers=$1 apk=$2 aab=$3 tool release package apk_package signed
  for tool in awk grep jarsigner jq keytool paste sed tr; do
    command -v "$tool" >/dev/null || fatal "no $tool on the PATH"
  done
  [ -n "${ANDROID_HOME:-}" ] || fatal "ANDROID_HOME names no SDK"
  APKSIGNER="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/apksigner"
  AAPT2="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/aapt2"
  readonly APKSIGNER AAPT2
  [ -x "$APKSIGNER" ] && [ -x "$AAPT2" ] || fatal "no apksigner or aapt2 in build tools $BUILD_TOOLS_VERSION"
  [ -f "$apk" ] || fatal "no APK at $apk"
  [ -f "$aab" ] || fatal "no app bundle at $aab"
  [ -f "$signers" ] || refuse "$signers is absent: the engine's export carries android_signers.json from stage D2" \
    "(CI/CD design E1), so no candidate is signed until the contract is re-vendored from an engine release with it"
  release="$(release_digest "$signers")"
  package="$(jq -r '.package' "$signers")"
  apk_package="$("$AAPT2" dump packagename "$apk")" || refuse "aapt2 could not read the package of $apk"
  [ "$apk_package" = "$package" ] || refuse "$apk is $apk_package, but $signers is for $package"
  signed="$(apk_digest "$apk")"
  [ "$signed" = "$release" ] ||
    refuse "$apk is signed by $signed, not by the release certificate $release that $signers names (C8)"
  signed="$(aab_digest "$aab")"
  [ "$signed" = "$release" ] ||
    refuse "$aab is signed by $signed, not by the release certificate $release that $signers names (C8)"
  echo "sign_check: $apk and $aab are signed by the release certificate $release"
}

main "$@"
