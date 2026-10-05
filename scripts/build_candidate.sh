#!/usr/bin/env bash
#
# candidate.yml's sign, its build: the release APK, the app bundle and R8's mapping with the owner's real
# google-services.json, unsigned (MILESTONE_51_ANDROID_CI_CD.md sections 4.1, 4.5). The file comes from the
# release environment in GOOGLE_SERVICES_JSON and goes to app/src/release/, where Google's services plugin reads
# it before the placeholder app/google-services.json (README.md, Firebase), of a clean checkout: one that
# already holds a file there is refused. It is shredded on every way out, and taken out of the environment
# before Gradle starts. The release key never reaches Gradle, or the plugins and libraries it runs: no
# FERMIX_RELEASE_* variable may be set, and sign_candidate.sh signs what this writes once the build has ended.
#
# The file is refused when it is the placeholder's project or has no Android client for io.tezra.fermix, and
# the APK is refused unless its project_id resource is the file's, so a release never ships the placeholder.
#
#   GOOGLE_SERVICES_JSON=<the file's text> build_candidate.sh <out-dir>
#
# writes <out-dir>/unsigned.apk, unsigned.aab and mapping.txt. Exits 1 on a refusal, 2 on a missing tool.
set -euo pipefail
shopt -s inherit_errexit

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
USAGE="usage: GOOGLE_SERVICES_JSON=... build_candidate.sh <out-dir>"
BUILD_TOOLS_VERSION="36.0.0"
PACKAGE="io.tezra.fermix"
PLACEHOLDER_PROJECT="fermix-placeholder"
RELEASE_FILE="$ROOT_DIR/app/src/release/google-services.json"
OUTPUTS="$ROOT_DIR/app/build/outputs"

fatal() {
  echo "build_candidate: $*" >&2
  exit 2
}

refuse() {
  echo "build_candidate: $*" >&2
  exit 1
}

# The project the file names, once it is shown to be a real project with the app's Android client.
project_of() {
  local file=$1 project
  project="$(jq -r '.project_info.project_id // ""' "$file")" || refuse "GOOGLE_SERVICES_JSON is not JSON"
  [ -n "$project" ] || refuse "GOOGLE_SERVICES_JSON names no project_info.project_id"
  [ "$project" != "$PLACEHOLDER_PROJECT" ] ||
    refuse "GOOGLE_SERVICES_JSON is the placeholder's project; the release environment holds the owner's"
  jq -e --arg package "$PACKAGE" \
    'any(.client[]?; .client_info.android_client_info.package_name == $package)' "$file" >/dev/null ||
    refuse "GOOGLE_SERVICES_JSON has no Android client for $PACKAGE"
  echo "$project"
}

# The EXIT trap: the release file is gone however the script ends, or the script fails saying so.
shred_release_file() {
  [ -e "$RELEASE_FILE" ] || return 0
  shred -u -- "$RELEASE_FILE" || { echo "build_candidate: could not shred $RELEASE_FILE" >&2; exit 2; }
}

# The project_id string resource the services plugin compiled into the APK.
apk_project() {
  "$AAPT2" dump resources "$1" | sed -n '/ string\/project_id$/{n;s/^ *() "\(.*\)"$/\1/p;}'
}

main() {
  [ $# -eq 1 ] && [[ "$1" != -* ]] || { echo "$USAGE" >&2; exit 2; }
  local out=$1 tool variable name project built
  for tool in jq sed shred; do command -v "$tool" >/dev/null || fatal "no $tool on the PATH"; done
  [ -n "${ANDROID_HOME:-}" ] || fatal "ANDROID_HOME names no SDK"
  AAPT2="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/aapt2"
  readonly AAPT2
  [ -n "${GOOGLE_SERVICES_JSON:-}" ] || refuse "GOOGLE_SERVICES_JSON is empty: the release environment holds the file"
  for variable in STORE_FILE STORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
    name="FERMIX_RELEASE_$variable"
    [ -z "${!name:-}" ] || refuse "$name is set: the release key never reaches Gradle; sign_candidate.sh signs"
  done
  [ ! -e "$RELEASE_FILE" ] || refuse "$RELEASE_FILE exists already; the candidate is built from a clean checkout"
  mkdir -p "$out" "$(dirname "$RELEASE_FILE")"
  trap shred_release_file EXIT
  (umask 077 && printf '%s' "$GOOGLE_SERVICES_JSON" >"$RELEASE_FILE")
  unset GOOGLE_SERVICES_JSON
  project="$(project_of "$RELEASE_FILE")"
  (cd "$ROOT_DIR" && ./gradlew --warning-mode fail --console=plain :app:assembleRelease :app:bundleRelease)
  built="$(apk_project "$OUTPUTS/apk/release/app-release-unsigned.apk")"
  [ "$built" = "$project" ] || refuse "the APK carries the Firebase project '${built}', not the release file's $project"
  cp "$OUTPUTS/apk/release/app-release-unsigned.apk" "$out/unsigned.apk"
  cp "$OUTPUTS/bundle/release/app-release.aab" "$out/unsigned.aab"
  cp "$OUTPUTS/mapping/release/mapping.txt" "$out/mapping.txt"
  echo "build_candidate: built the release of Firebase project $project, unsigned, into $out"
}

main "$@"
