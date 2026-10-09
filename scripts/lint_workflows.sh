#!/usr/bin/env bash
#
# The workflows and the scripts they call, linted (MILESTONE_51_ANDROID_CI_CD.md C11, C12): actionlint over
# every workflow, with shellcheck over each run: block, shellcheck over scripts/*.sh, ruff over the Python of
# scripts/ and scripts/tests (pyflakes' and pycodestyle's rules, lines of at most 120), and the house rules
# actionlint does not hold (check_workflows.py). Every finding fails it, as every warning fails the build.
#
# actionlint, shellcheck and ruff are pinned by version and by the sha256 of their release archives, downloaded
# into a scratch directory removed on every way out, and checked before they run: CI's runner image and a
# developer's machine run the same three binaries, whatever they have installed. Linux x86-64 only, as CI is.
#
#   lint_workflows.sh
set -euo pipefail
shopt -s inherit_errexit nullglob

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACTIONLINT_VERSION="1.7.12"
ACTIONLINT_SHA256="8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8"
SHELLCHECK_VERSION="0.11.0"
SHELLCHECK_SHA256="b7af85e41cc99489dcc21d66c6d5f3685138f06d34651e6d34b42ec6d54fe6f6"
RUFF_VERSION="0.16.10"
RUFF_SHA256="9567ff1201e2fb3da31ff04c35587d768c66d6cb42dfa84de474e2bfe360b608"
# A download slower than this many bytes per second for this many seconds is abandoned.
LOW_SPEED_BYTES=1000
LOW_SPEED_SECONDS=30
# The scratch directory main makes for the tools, which the EXIT trap removes.
work=""

fatal() {
  echo "lint_workflows: $*" >&2
  exit 2
}

# Downloads [url] to [file] and refuses it unless its sha256 is [sha256].
fetch() {
  local url=$1 file=$2 sha256=$3
  curl --fail --silent --show-error --location --speed-limit "$LOW_SPEED_BYTES" --speed-time "$LOW_SPEED_SECONDS" \
    --output "$file" "$url" || fatal "could not download $url"
  echo "$sha256  $file" | sha256sum --check --status || fatal "$url is not the pinned archive (sha256 $sha256)"
}

main() {
  [ $# -eq 0 ] || { echo "usage: lint_workflows.sh" >&2; exit 2; }
  local tool workflows python fake
  for tool in curl python3 sha256sum tar; do command -v "$tool" >/dev/null || fatal "no $tool on the PATH"; done
  [ "$(uname -sm)" = "Linux x86_64" ] || fatal "the pinned tools are Linux x86-64's, as CI's runner is"
  work="$(mktemp -d)" || fatal "could not make a scratch directory"
  trap 'rm -rf -- "${work:?}"' EXIT
  fetch "https://github.com/rhysd/actionlint/releases/download/v$ACTIONLINT_VERSION/actionlint_${ACTIONLINT_VERSION}_linux_amd64.tar.gz" \
    "$work/actionlint.tar.gz" "$ACTIONLINT_SHA256"
  fetch "https://github.com/koalaman/shellcheck/releases/download/v$SHELLCHECK_VERSION/shellcheck-v$SHELLCHECK_VERSION.linux.x86_64.tar.gz" \
    "$work/shellcheck.tar.gz" "$SHELLCHECK_SHA256"
  fetch "https://github.com/astral-sh/ruff/releases/download/$RUFF_VERSION/ruff-x86_64-unknown-linux-gnu.tar.gz" \
    "$work/ruff.tar.gz" "$RUFF_SHA256"
  tar -xzf "$work/actionlint.tar.gz" -C "$work" actionlint
  tar -xzf "$work/shellcheck.tar.gz" -C "$work" --strip-components=1 "shellcheck-v$SHELLCHECK_VERSION/shellcheck"
  tar -xzf "$work/ruff.tar.gz" -C "$work" --strip-components=1 ruff-x86_64-unknown-linux-gnu/ruff
  # GitHub runs a .yaml file as it runs a .yml one.
  workflows=("$ROOT_DIR"/.github/workflows/*.yml "$ROOT_DIR"/.github/workflows/*.yaml)
  # The fakes are Python with no extension, which ruff lints when it is named them, each by its own path: in a
  # directory it is given, ruff finds only .py files, and the emulator's fakes are a directory of fakes/.
  python=("$ROOT_DIR"/scripts/*.py "$ROOT_DIR"/scripts/tests/*.py)
  for fake in "$ROOT_DIR"/scripts/tests/fakes/* "$ROOT_DIR"/scripts/tests/fakes/emulator/*; do
    if [ -f "$fake" ]; then python+=("$fake"); fi
  done
  "$work/actionlint" -shellcheck="$work/shellcheck" "${workflows[@]}"
  "$work/shellcheck" "$ROOT_DIR"/scripts/*.sh
  "$work/ruff" check --no-cache --isolated --select E,F,W --line-length 120 "${python[@]}"
  "$ROOT_DIR/scripts/check_workflows.py" "${workflows[@]}"
  echo "lint_workflows: actionlint $ACTIONLINT_VERSION, shellcheck $SHELLCHECK_VERSION, ruff $RUFF_VERSION and" \
    "the house rules pass"
}

main "$@"
