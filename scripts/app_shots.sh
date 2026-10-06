#!/usr/bin/env bash
#
# The app's screens, in docs/app-shots, made from the screenshots `./gradlew recordRoborazziDebug` drew on this
# machine. Run it after a green record: the record's checkPreviewsDrawn fails while src/test/screenshots holds an
# image the record did not draw, the image of a preview that is gone, which this script would copy as it copies any.
#
# For every preview that has screenshots in a module's src/test/screenshots/, its compact window at font scale 1.0,
# light and dark, is copied byte for byte to docs/app-shots/<module>/<Preview>-light.png and -dark.png; and
# docs/app-shots/README.md is written, one section a module, each preview with its light image beside its dark one.
# git tracks neither directory (the owner, 2026-10-05): CI's screens job records, runs this script and uploads
# docs/app-shots as its app-shots artifact.
#
#   app_shots.sh    write docs/app-shots afresh, leaving nothing in it the script did not write
#
# Exit status: 0 written; 2 nothing recorded, a screenshot set it cannot read, a usage error or a tool that failed.
# Each but a failed write of docs/app-shots itself stops before it touches the directory.
set -euo pipefail
shopt -s inherit_errexit nullglob

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SHOTS="docs/app-shots"
# One order on every machine: modules, previews and files sort by their bytes.
export LC_ALL=C
# How wide README.md draws each image, in pixels, so a light one and a dark one fit side by side.
IMAGE_WIDTH=280
# The scratch directory main makes, which the EXIT trap removes.
work=""

fatal() {
  echo "app_shots: $*" >&2
  exit 2
}

usage() {
  echo "usage: app_shots.sh" >&2
  exit 2
}

# The preview a screenshot [file] was drawn for, as Roborazzi names it: its class and function, without the window,
# mode and font scale that follow (io.tezra.fermix.chat.ChatPreviewsKt.ThreadPreview).
preview_of() {
  basename "$1" | sed -E 's/\.(compact|medium|expanded)_.*$//'
}

# The one screenshot of [preview] in [dir] for its compact window in [mode] (light or dark) at font scale 1.0.
compact_of() {
  local dir=$1 preview=$2 mode=$3 found
  found=("$dir/$preview.compact_${mode}_1.0_"*.png)
  [ "${#found[@]}" -eq 1 ] || fatal "$dir holds ${#found[@]} compact $mode images at font scale 1.0 of $preview, not 1"
  echo "${found[0]}"
}

# The previews that have screenshots in [dir], each once, in order.
previews_in() {
  local dir=$1 file
  for file in "$dir"/*.png; do preview_of "$file"; done | sort -u
}

# The modules' screenshot directories that hold an image, one a line, in order; none when nothing is recorded.
recorded_in() {
  local screenshots found
  for screenshots in "$ROOT_DIR"/*/src/test/screenshots; do
    found=("$screenshots"/*.png)
    [ "${#found[@]}" -eq 0 ] || echo "$screenshots"
  done
}

# Copies [module]'s previews' compact images into [out]/<module>/ and prints its README section.
write_module() {
  local module=$1 out=$2 dir="$ROOT_DIR/$1/src/test/screenshots" listed preview name light dark
  local previews=()
  listed="$(previews_in "$dir")"
  mapfile -t previews <<<"$listed"
  mkdir -p "$out/$module" || fatal "could not make $out/$module"
  printf '\n## %s\n\n| Preview | Light | Dark |\n|---|---|---|\n' "$module"
  for preview in "${previews[@]}"; do
    name="${preview##*.}"
    [ ! -e "$out/$module/$name-light.png" ] || fatal "$module has two previews named $name"
    light="$(compact_of "$dir" "$preview" light)"
    dark="$(compact_of "$dir" "$preview" dark)"
    cp "$light" "$out/$module/$name-light.png" || fatal "could not copy $light"
    cp "$dark" "$out/$module/$name-dark.png" || fatal "could not copy $dark"
    printf '| %s | %s | %s |\n' "$name" "$(image "$module/$name" light)" "$(image "$module/$name" dark)"
  done
}

# README.md's cell for [shot]'s image in [mode].
image() {
  printf '<img src="%s-%s.png" alt="%s, %s" width="%s">' "$1" "$2" "${1#*/}" "$2" "$IMAGE_WIDTH"
}

# Writes the whole of docs/app-shots into [out], from [recorded], recorded_in's lines.
write_shots() {
  local recorded=$1 out=$2 screenshots module
  local directories=()
  mapfile -t directories <<<"$recorded"
  mkdir -p "$out" || fatal "could not make $out"
  {
    echo "# The app's screens"
    echo
    echo "Each preview's compact window (412 × 915 dp) at font scale 1.0, light and dark, copied from the"
    echo "screenshots \`./gradlew recordRoborazziDebug\` drew. \`scripts/app_shots.sh\` writes this directory, which git"
    echo "does not track; CI's \`screens\` job draws every preview on each run and keeps the directory as its"
    echo "\`app-shots\` artifact."
    echo
    echo "Where a screen holds a surface the system draws, its preview draws a stand-in: the attach sheet's photo"
    echo "grid stands for the system's Photo Picker, which numbers a picked photo in the system's own colour, not in"
    echo "the app's ink."
    for screenshots in "${directories[@]}"; do
      module="${screenshots#"$ROOT_DIR"/}"
      write_module "${module%%/*}" "$out"
    done
  } >"$out/README.md"
}

main() {
  [ $# -eq 0 ] || usage
  local tool recorded
  for tool in cp mktemp sed sort; do command -v "$tool" >/dev/null || fatal "no $tool on the PATH"; done
  recorded="$(recorded_in)"
  [ -n "$recorded" ] || fatal "no module has a recorded screenshot; run ./gradlew recordRoborazziDebug first"
  work="$(mktemp -d)" || fatal "could not make a scratch directory"
  trap 'rm -rf -- "${work:?}"' EXIT
  write_shots "$recorded" "$work/app-shots"
  rm -rf -- "${ROOT_DIR:?}/$SHOTS" || fatal "could not remove $SHOTS"
  mkdir -p "$ROOT_DIR/docs" || fatal "could not make docs"
  cp -R "$work/app-shots" "$ROOT_DIR/$SHOTS" || fatal "could not write $SHOTS"
  echo "app_shots: wrote $SHOTS"
}

main "$@"
