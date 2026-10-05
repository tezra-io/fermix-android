#!/usr/bin/env bash
#
# candidate.yml's sign, its signing: sign what build_candidate.sh built, once the build has ended, with the
# owner's release key (MILESTONE_51_ANDROID_CI_CD.md C8, section 4.5), with the SDK's apksigner and the JDK's
# jarsigner alone, so the key never reaches Gradle or what it runs; the build ran on this runner all the same,
# as section 4.5 has it (docs/RELEASING.md). The keystore comes from the release environment, base64 in
# RELEASE_KEYSTORE_BASE64 with RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD, and is
# written into a scratch directory only this user reads, shredded on every way out; the base64 leaves the
# environment once it is decoded, and the passwords reach the tools through their environment, never their
# command line.
#
# The APK is signed as the Android Gradle plugin signs a minSdk 35 release, APK signature schemes v2 and v3
# without v4's separate file, and the app bundle with a JAR signature, which Play reads as its upload key's.
# sign_check.sh then holds both to the release entry of android_signers.json.
#
#   sign_candidate.sh <vX.Y.Z> <unsigned-dir> <out-dir>
#
# reads <unsigned-dir>/unsigned.apk, unsigned.aab and mapping.txt and writes fermix-android-X.Y.Z.apk, .aab
# and -mapping.txt into <out-dir>. Exits 1 on a refusal, 2 on a missing tool or argument.
set -euo pipefail
shopt -s inherit_errexit

BUILD_TOOLS_VERSION="36.0.0"
# The scratch directory main makes for the keystore, which the EXIT trap shreds and removes.
scratch=""

fatal() {
  echo "sign_candidate: $*" >&2
  exit 2
}

# The EXIT trap: the keystore is gone however the script ends, or the script fails saying so.
shred_keystore() {
  if [ -z "$scratch" ] || [ ! -d "$scratch" ]; then return 0; fi
  if [ -e "$scratch/release.jks" ]; then
    shred -u -- "$scratch/release.jks" || { echo "sign_candidate: could not shred the keystore" >&2; exit 2; }
  fi
  rm -rf -- "$scratch"
}

main() {
  [ $# -eq 3 ] || { echo "usage: sign_candidate.sh <vX.Y.Z> <unsigned-dir> <out-dir>" >&2; exit 2; }
  local tag=$1 unsigned=$2 out=$3 tool variable name version store
  [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || fatal "'$tag' is not a release tag vMAJOR.MINOR.PATCH"
  for tool in base64 jarsigner mktemp shred; do command -v "$tool" >/dev/null || fatal "no $tool on the PATH"; done
  [ -n "${ANDROID_HOME:-}" ] || fatal "ANDROID_HOME names no SDK"
  local apksigner="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/apksigner"
  [ -x "$apksigner" ] || fatal "no apksigner in build tools $BUILD_TOOLS_VERSION"
  for variable in KEYSTORE_BASE64 STORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
    name="RELEASE_$variable"
    [ -n "${!name:-}" ] || { echo "sign_candidate: $name is empty: the release environment holds the key" >&2; exit 1; }
  done
  for name in unsigned.apk unsigned.aab mapping.txt; do [ -f "$unsigned/$name" ] || fatal "no $unsigned/$name"; done
  version="${tag#v}"
  mkdir -p "$out"
  trap shred_keystore EXIT
  scratch="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/release-key.XXXXXX")" ||
    fatal "could not make a scratch directory"
  chmod 700 "$scratch"
  store="$scratch/release.jks"
  (umask 077 && base64 --decode <<<"$RELEASE_KEYSTORE_BASE64" >"$store") ||
    fatal "RELEASE_KEYSTORE_BASE64 is not base64"
  unset RELEASE_KEYSTORE_BASE64
  "$apksigner" sign --ks "$store" --ks-key-alias "$RELEASE_KEY_ALIAS" \
    --ks-pass env:RELEASE_STORE_PASSWORD --key-pass env:RELEASE_KEY_PASSWORD \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false \
    --out "$out/fermix-android-$version.apk" "$unsigned/unsigned.apk"
  jarsigner -keystore "$store" -storepass:env RELEASE_STORE_PASSWORD -keypass:env RELEASE_KEY_PASSWORD \
    -signedjar "$out/fermix-android-$version.aab" "$unsigned/unsigned.aab" "$RELEASE_KEY_ALIAS"
  cp "$unsigned/mapping.txt" "$out/fermix-android-$version-mapping.txt"
  echo "sign_candidate: signed fermix-android-$version.apk and .aab with the release key into $out"
}

main "$@"
