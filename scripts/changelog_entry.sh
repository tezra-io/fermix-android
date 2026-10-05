#!/usr/bin/env bash
#
# Print one version's entry of a Keep a Changelog CHANGELOG.md, read on stdin: every line under its
# "## [X.Y.Z]" heading up to the next "## [" heading, without the heading and the blank lines around it.
# The release pull request renames "## [Unreleased]" to the version's heading (docs/RELEASING.md), so
# release_preflight.sh refuses a tag whose commit has no entry, and stage_candidate.sh makes the draft's
# notes from it. A version with no heading, or one whose entry is empty, fails with status 1.
#
#   changelog_entry.sh <X.Y.Z> < CHANGELOG.md
set -euo pipefail
shopt -s inherit_errexit

fail() {
  echo "changelog_entry: $*" >&2
  exit 1
}

[ $# -eq 1 ] && [[ "$1" != -* ]] || { echo "usage: changelog_entry.sh <X.Y.Z> < CHANGELOG.md" >&2; exit 2; }
version=$1
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "'$version' is not a version MAJOR.MINOR.PATCH"

# The heading's version is compared as a string, never as an awk pattern, so its dots match only dots.
entry="$(awk -v version="$version" '
  /^## \[/ {
    heading = $0
    sub(/^## \[/, "", heading)
    sub(/\].*$/, "", heading)
    inside = (heading == version)
    if (inside) seen = 1
    next
  }
  inside { print }
  END { if (!seen) exit 3 }
')" || fail "CHANGELOG.md has no \"## [$version]\" heading: the release pull request writes the version's entry"
# Strip the blank lines around the entry; an entry of blank lines alone is no entry.
entry="$(sed -e '/./,$!d' <<<"$entry")"
[ -n "$entry" ] || fail "CHANGELOG.md's \"## [$version]\" entry is empty"
echo "$entry"
