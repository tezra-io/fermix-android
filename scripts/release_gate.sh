#!/usr/bin/env bash
#
# candidate.yml's build, first: refuse to build a candidate from a commit whose pull-request gate is not
# green (MILESTONE_51_ANDROID_CI_CD.md section 4.1). ci.yml runs on every push to main, so the release's merge
# commit has a check run named gate (C3), made by GitHub Actions in the check suite of one of ci.yml's runs of
# the commit; a job named gate in any other workflow is no ci gate. The latest of ci.yml's gate runs must have
# completed with success: one still running, one that failed, a gate from another app or workflow, or none at
# all is refused, and the candidate is run again once ci's gate is green. It waits for nothing, so it holds no
# runner while ci runs.
#
# It asks GitHub through gh (GH_TOKEN, with checks: read and actions: read) about REPO (owner/name) and exits 1
# on a refusal, 2 on a malformed argument or a missing tool.
#
#   release_gate.sh <commit>
set -euo pipefail
shopt -s inherit_errexit

USAGE="usage: release_gate.sh <commit>"
CI_WORKFLOW="ci.yml"
# The runs GitHub lists on one page; more ci runs or gate runs than this on one commit is past what it reads.
PAGE=100

fatal() {
  echo "release_gate: $*" >&2
  exit 2
}

refuse() {
  echo "release_gate: $*" >&2
  exit 1
}

# The ids of the check suites of ci.yml's runs of [commit], as a JSON array.
ci_suites() {
  local commit=$1 runs total
  runs="$(gh api "repos/$REPO/actions/workflows/$CI_WORKFLOW/runs?head_sha=$commit&per_page=$PAGE")" ||
    fatal "gh could not list $CI_WORKFLOW's runs of $commit"
  total="$(jq -r '.total_count' <<<"$runs")"
  [ "$total" -le "$PAGE" ] || fatal "$commit has $total $CI_WORKFLOW runs, past the $PAGE one page holds"
  jq -c '[.workflow_runs[].check_suite_id]' <<<"$runs"
}

main() {
  [ $# -eq 1 ] && [[ "$1" != -* ]] || { echo "$USAGE" >&2; exit 2; }
  local commit=$1 tool suites runs total latest status conclusion
  for tool in gh jq; do command -v "$tool" >/dev/null || fatal "no $tool on the PATH"; done
  [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fatal "'$commit' is not a full commit id"
  [[ "${REPO:-}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || fatal "REPO names no owner/repository"
  suites="$(ci_suites "$commit")"
  runs="$(gh api "repos/$REPO/commits/$commit/check-runs?check_name=gate&filter=all&per_page=$PAGE")" ||
    fatal "gh could not list the check runs of $commit"
  total="$(jq -r '.total_count' <<<"$runs")"
  [ "$total" -le "$PAGE" ] || fatal "$commit has $total gate check runs, past the $PAGE one page holds"
  # Check run ids only grow, so the largest is the latest run, a re-run included.
  latest="$(jq -c --argjson suites "$suites" '[.check_runs[]
    | select(.name == "gate" and .app.slug == "github-actions" and (.check_suite.id as $id | $suites | index($id)))]
    | max_by(.id) // empty' <<<"$runs")"
  [ -n "$latest" ] ||
    refuse "ci has no gate check run on $commit: tag a commit ci ran on, the release pull request's merge to main"
  status="$(jq -r '.status' <<<"$latest")"
  conclusion="$(jq -r '.conclusion // ""' <<<"$latest")"
  [ "$status" = completed ] ||
    refuse "ci's gate on $commit is $status: run the candidate again once it is green"
  [ "$conclusion" = success ] ||
    refuse "ci's gate on $commit concluded $conclusion: a candidate is built only from a green commit"
  echo "release_gate: ci's gate on $commit is green"
}

main "$@"
