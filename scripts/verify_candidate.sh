#!/usr/bin/env bash
#
# candidate.yml's verify (MILESTONE_51_ANDROID_CI_CD.md section 4.1), on the signed files sign wrote: refuse
# the candidate when
#
#   1. apksigner does not verify the APK at its own minSdk, or zipalign finds it unaligned (an uncompressed
#      native library off a 16 KB page among it), as the signing happened outside Gradle
#   2. scripts/check_release_policy.sh, as it is, fails on the APK, or on the app bundle's base module, from
#      which Play builds every split a phone installs from it; or the bundle holds a module besides base
#   3. the APK or the app bundle is debuggable, or either is another package than io.tezra.fermix
#   4. the APK and the bundle disagree on version, or either is not the tag's versionName with
#      version.properties' versionCode (section 4.4)
#
# then write SHA256SUMS over the APK, the bundle and the mapping, which stage signs. The bundle's base module is
# read as an APK: its files laid out as an APK lays them, and its manifest and resources converted by aapt2 from
# the bundle's protocol buffers to the binary form an APK carries, as the policy reads it. Run from the tag's
# checkout. Exits 1 on a refusal, 2 on a missing tool or argument.
#
#   verify_candidate.sh <vX.Y.Z> <dir>
set -euo pipefail
shopt -s inherit_errexit

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_TOOLS_VERSION="36.0.0"
PACKAGE="io.tezra.fermix"
# The scratch directory main makes for the bundle's manifest, which the EXIT trap removes.
work=""

fatal() {
  echo "verify_candidate: $*" >&2
  exit 2
}

refuse() {
  echo "verify_candidate: $*" >&2
  exit 1
}

# "<package> <versionCode> <versionName> <debuggable>" from aapt2's badging of [apk].
identity() {
  local badging package code name debuggable=no
  badging="$("$BUILD_TOOLS/aapt2" dump badging "$1")" || refuse "aapt2 could not read $1"
  package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging")"
  code="$(sed -n "s/^package: .* versionCode='\([^']*\)'.*/\1/p" <<<"$badging")"
  name="$(sed -n "s/^package: .* versionName='\([^']*\)'.*/\1/p" <<<"$badging")"
  if grep -q '^application-debuggable' <<<"$badging"; then debuggable=yes; fi
  echo "$package $code $name $debuggable"
}

# The bundle's base module as an APK, as bundletool lays one out: the manifest and the resource table at the top,
# the dex files and the module's root/ too, res/, assets/ and lib/ as they are; then converted from protocol
# buffers to binary resources. The bundle's metadata and its JAR signature, which no phone receives, are left
# out.
bundle_base_apk() {
  local aab=$1 module="$work/aab/base" laid="$work/bundle-base" entries others name
  entries="$(unzip -Z1 "$aab")" || refuse "could not list $aab"
  others="$(awk -F/ '!/^(base|BUNDLE-METADATA|META-INF)\// && $0 != "BundleConfig.pb" { print $1 }' <<<"$entries" |
    sort -u | paste -sd' ')"
  [ -z "$others" ] || refuse "$aab holds a module besides base: $others"
  unzip -q "$aab" 'base/*' -d "$work/aab" || refuse "could not unpack $aab"
  [ -f "$module/manifest/AndroidManifest.xml" ] && [ -f "$module/resources.pb" ] ||
    refuse "$aab has no base/manifest/AndroidManifest.xml and base/resources.pb"
  mkdir "$laid"
  mv "$module/manifest/AndroidManifest.xml" "$module/resources.pb" "$laid/"
  for name in dex root; do [ ! -d "$module/$name" ] || cp -R "$module/$name/." "$laid/"; done
  for name in res assets lib; do [ ! -d "$module/$name" ] || mv "$module/$name" "$laid/"; done
  (cd "$laid" && zip -q -r -D "$work/bundle-base-proto.apk" .)
  "$BUILD_TOOLS/aapt2" convert --output-format binary -o "$work/bundle-base.apk" "$work/bundle-base-proto.apk" ||
    refuse "aapt2 could not convert the base module of $aab to binary resources"
  echo "$work/bundle-base.apk"
}

check_signature() {
  local apk=$1 verified
  verified="$("$BUILD_TOOLS/apksigner" verify --verbose "$apk" 2>&1)" ||
    refuse "apksigner does not verify $apk: $verified"
  "$BUILD_TOOLS/zipalign" -c -P 16 4 "$apk" >/dev/null || refuse "$apk is not aligned (zipalign -c -P 16 4)"
}

# [apk] and [base], the bundle's base module as bundle_base_apk lays it out, against the tag and each other.
check_versions() {
  local tag=$1 apk=$2 base=$3 code expected from_apk from_aab
  code="$(sed -n 's/^versionCode=\([0-9][0-9]*\)$/\1/p' "$ROOT_DIR/version.properties")"
  [ -n "$code" ] || fatal "version.properties holds no versionCode"
  expected="$PACKAGE $code ${tag#v} no"
  from_apk="$(identity "$apk")"
  from_aab="$(identity "$base")"
  [ "$from_apk" = "$expected" ] ||
    refuse "the APK is '$from_apk' (package, versionCode, versionName, debuggable), not '$expected'"
  [ "$from_aab" = "$from_apk" ] || refuse "the app bundle is '$from_aab', the APK '$from_apk': they disagree"
}

main() {
  [ $# -eq 2 ] || { echo "usage: verify_candidate.sh <vX.Y.Z> <dir>" >&2; exit 2; }
  local tag=$1 dir=$2 version apk aab mapping tool name base
  [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || fatal "'$tag' is not a release tag vMAJOR.MINOR.PATCH"
  for tool in awk cp grep mktemp paste sed sha256sum sort unzip zip; do
    command -v "$tool" >/dev/null || fatal "no $tool on the PATH"
  done
  [ -n "${ANDROID_HOME:-}" ] || fatal "ANDROID_HOME names no SDK"
  BUILD_TOOLS="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION"
  readonly BUILD_TOOLS
  version="${tag#v}"
  apk="fermix-android-$version.apk"
  aab="fermix-android-$version.aab"
  mapping="fermix-android-$version-mapping.txt"
  for name in "$apk" "$aab" "$mapping"; do [ -s "$dir/$name" ] || fatal "no $dir/$name"; done
  work="$(mktemp -d)" || fatal "could not make a scratch directory"
  trap 'rm -rf -- "${work:?}"' EXIT
  check_signature "$dir/$apk"
  "$ROOT_DIR/scripts/check_release_policy.sh" "$dir/$apk" || refuse "the signed APK fails the release policy"
  base="$(bundle_base_apk "$dir/$aab")"
  "$ROOT_DIR/scripts/check_release_policy.sh" "$base" || refuse "the app bundle's base module fails the release policy"
  check_versions "$tag" "$dir/$apk" "$base"
  (cd "$dir" && sha256sum "$apk" "$aab" "$mapping" >SHA256SUMS)
  echo "verify_candidate: $apk and $aab are $PACKAGE $version with version.properties' versionCode, signed," \
    "aligned and as the policy says; SHA256SUMS written"
}

main "$@"
