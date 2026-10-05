#!/usr/bin/env bash
#
# The tests that start git, on a machine and under a caller that would break them (AGENTS.md: a git a test starts
# takes nothing from the machine's git configuration or the caller's GIT_ variables). It runs build-logic's tests
# and the script tests twice:
#
#   1. under a global git configuration that names no identity and refuses to guess one, signs every commit and
#      tag with a program that fails, names a default branch and a template of its own, runs hooks that fail and
#      lays lists out in columns, with an ignore file that ignores everything, and with git's words in German:
#      LANGUAGE=de under LC_ALL=C.UTF-8, which every glibc has, while Java's stay English, as sign_check.sh reads
#      jarsigner's English, a limit of its own; the check stops when this machine's git has no German words
#   2. with every variable that names a repository to git naming a scratch repository: GIT_DIR, GIT_WORK_TREE,
#      GIT_INDEX_FILE and GIT_CONFIG_PARAMETERS, which git exports to a hook, an alias or rebase --exec,
#      GIT_OBJECT_DIRECTORY and GIT_ALTERNATE_OBJECT_DIRECTORIES, which it sets for a pre-receive hook,
#      GIT_SHALLOW_FILE, which it gives the receive hooks, listing the repository's HEAD, and GIT_COMMON_DIR and
#      GIT_NAMESPACE; the repository must hold the same HEAD, refs, index, configuration, objects and files afterwards
#
# A test that takes its identity or its configuration from the machine or the caller, or writes outside its scratch
# directory, fails a run. It exits 1 when a run fails or the scratch repository changed, 2 when it cannot run.
# Neither run plants anything in the machine's own files, the system configuration, the system attributes and the
# template git's init copies, as that takes root: the tests' helpers name none of them to git (GIT_CONFIG_NOSYSTEM,
# GIT_ATTR_NOSYSTEM, an empty GIT_TEMPLATE_DIR), and the template's half shows on any stock machine, whose template
# holds sample hooks (ScratchGitTest, test_support.py).
# build-logic's tests run as a build of their own (-p build-logic): the app's build reads its version with a git
# that follows GIT_COMMON_DIR and GIT_OBJECT_DIRECTORY as any git does (README.md), so it would fail to configure
# under run 2 before a test ran. JAVA_HOME and ANDROID_HOME as for the build.
#
#   check_git_isolation.sh
set -euo pipefail
shopt -s inherit_errexit

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOOKS="pre-commit prepare-commit-msg commit-msg post-commit reference-transaction"

# git for the script's own scratch repository, with nothing of the machine's or the caller's.
own_git() {
  env -i PATH="$PATH" GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_ATTR_NOSYSTEM=1 GIT_TEMPLATE_DIR= \
    GIT_AUTHOR_NAME=Victim GIT_AUTHOR_EMAIL=victim@example.com GIT_COMMITTER_NAME=Victim \
    GIT_COMMITTER_EMAIL=victim@example.com git -C "$1" "${@:2}"
}

hostile_machine() {
  local dir=$1 hook
  mkdir -p "$dir/hooks" "$dir/templates" "$dir/xdg/git"
  for hook in $HOOKS; do
    printf '#!/bin/sh\necho "the hostile %s hook ran" >&2\nexit 1\n' "$hook" >"$dir/hooks/$hook"
    chmod +x "$dir/hooks/$hook"
  done
  cp -R "$dir/hooks" "$dir/templates/hooks"
  printf '*\n' >"$dir/xdg/git/ignore"
  printf '[user]\n\tuseConfigOnly = true\n[commit]\n\tgpgSign = true\n[tag]\n\tgpgSign = true\n' >"$dir/gitconfig"
  printf '\tforceSignAnnotated = true\n[gpg]\n\tprogram = /bin/false\n[column]\n\tui = always\n' >>"$dir/gitconfig"
  printf '[init]\n\tdefaultBranch = zzz\n\ttemplateDir = %s\n[core]\n\thooksPath = %s\n' \
    "$dir/templates" "$dir/hooks" >>"$dir/gitconfig"
}

# Whether git speaks German under run 1's locale: without its German words the run cannot show that no test reads
# git's words.
speaks_german() {
  ! env -i PATH="$PATH" LC_ALL=C.UTF-8 LANGUAGE=de git --git-dir "$1/none/.git" describe 2>"$1/words" &&
    grep -q 'Kein Git-Repository' "$1/words"
}

# A repository with a commit, a tag of each kind and a staged file, as a developer's might be.
victim() {
  mkdir -p "$1"
  own_git "$1" init --quiet
  echo first >"$1/first"
  own_git "$1" add first
  own_git "$1" commit --quiet -m first
  own_git "$1" tag v0.0.1
  own_git "$1" tag -a v0.0.2 -m v0.0.2
  echo staged >"$1/staged"
  own_git "$1" add staged
}

snapshot() {
  cat "$1/.git/HEAD"
  own_git "$1" for-each-ref --format='%(objectname) %(refname)'
  sha256sum <"$1/.git/index"
  sha256sum <"$1/.git/config"
  (cd "$1/.git/objects" && find . -type f | sort | xargs sha256sum)
  (cd "$1" && find . -path ./.git -prune -o -type f -print | sort | xargs sha256sum)
}

# Both suites, each to its end; it fails when either does.
run_tests() {
  local status=0
  (cd "$ROOT_DIR" && ./gradlew --no-configuration-cache --console=plain -p build-logic test --rerun --no-build-cache) ||
    status=1
  (cd "$ROOT_DIR" && python3 -m unittest discover --start-directory scripts/tests) || status=1
  return "$status"
}

# The body is a subshell, so its EXIT trap removes the scratch directory on every way out while $scratch is in scope.
main() (
  [ $# -eq 0 ] || { echo "usage: check_git_isolation.sh" >&2; exit 2; }
  local scratch status=0 before after repository
  scratch="$(mktemp -d)"
  trap 'rm -rf -- "$scratch"' EXIT
  if ! speaks_german "$scratch"; then
    echo "check_git_isolation: this machine's git has no German words (LANGUAGE=de), so run 1 cannot run" >&2
    exit 2
  fi
  hostile_machine "$scratch/machine"
  if ! GIT_CONFIG_GLOBAL="$scratch/machine/gitconfig" XDG_CONFIG_HOME="$scratch/machine/xdg" LC_ALL=C.UTF-8 \
    LANGUAGE=de run_tests; then
    echo "check_git_isolation: a test failed on a machine whose git configuration it must not read" >&2
    status=1
  fi
  repository="$scratch/victim"
  victim "$repository"
  own_git "$repository" rev-parse HEAD >"$scratch/shallow"
  before="$(snapshot "$repository")"
  if ! GIT_DIR="$repository/.git" GIT_WORK_TREE="$repository" GIT_INDEX_FILE="$repository/.git/index" \
    GIT_CONFIG_PARAMETERS="'core.hooksPath=$scratch/machine/hooks'" GIT_OBJECT_DIRECTORY="$repository/.git/objects" \
    GIT_ALTERNATE_OBJECT_DIRECTORIES="$repository/.git/objects" GIT_SHALLOW_FILE="$scratch/shallow" \
    GIT_COMMON_DIR="$repository/.git" GIT_NAMESPACE=victim run_tests; then
    echo "check_git_isolation: a test failed with the caller's GIT_ variables naming another repository" >&2
    status=1
  fi
  after="$(snapshot "$repository")"
  if ! diff <(echo "$before") <(echo "$after") >&2; then
    echo "check_git_isolation: a test wrote into the repository the caller's GIT_ variables named (above)" >&2
    status=1
  fi
  if [ "$status" -eq 0 ]; then
    echo "check_git_isolation: the tests took nothing of git's from the machine or the caller"
  fi
  exit "$status"
)

main "$@"
