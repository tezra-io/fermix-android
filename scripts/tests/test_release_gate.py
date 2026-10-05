"""release_gate.sh: a candidate is built only from a commit whose ci.yml gate is green (CI/CD design section 4.1).
A gate check run counts only when it is ci.yml's: its check suite is one of ci.yml's runs of the commit."""
import unittest

from support import REPO, ScriptTest

COMMIT = "1f35c2fe" + "0" * 32
CI_SUITE = 501
OTHER_SUITE = 777


def run_of(run_id, status="completed", conclusion="success", app="github-actions", name="gate", suite=CI_SUITE):
    return {
        "id": run_id, "name": name, "status": status, "conclusion": conclusion, "app": {"slug": app},
        "check_suite": {"id": suite},
    }


class ReleaseGateTest(ScriptTest):
    def gate(self, *runs, ci_suites=(CI_SUITE,)):
        self.answer(f"repos/{REPO}/commits/{COMMIT}/check-runs", {"total_count": len(runs), "check_runs": list(runs)})
        workflow_runs = [{"id": 9000 + suite, "path": ".github/workflows/ci.yml", "check_suite_id": suite}
                         for suite in ci_suites]
        self.answer(
            f"repos/{REPO}/actions/workflows/ci.yml/runs",
            {"total_count": len(workflow_runs), "workflow_runs": workflow_runs},
        )
        return self.script("release_gate.sh", COMMIT)

    def test_a_green_gate_passes(self):
        self.assertPassed(self.gate(run_of(7)))

    def test_a_green_rerun_after_a_failure_passes(self):
        self.assertPassed(self.gate(run_of(7, conclusion="failure"), run_of(9)))

    def test_a_failed_latest_gate_is_refused(self):
        self.assertRefused(self.gate(run_of(7), run_of(9, conclusion="failure")), "concluded failure")

    def test_a_gate_still_running_is_refused(self):
        self.assertRefused(self.gate(run_of(9, status="in_progress", conclusion=None)), "is in_progress")

    def test_no_gate_is_refused(self):
        self.assertRefused(self.gate(), f"ci has no gate check run on {COMMIT}")

    def test_a_gate_another_app_made_is_refused(self):
        self.assertRefused(self.gate(run_of(9, app="someone-else")), "ci has no gate check run")

    def test_a_green_gate_of_another_workflow_is_no_ci_gate(self):
        # ci.yml's gate failed; a later job named gate in another workflow on the commit went green.
        result = self.gate(run_of(7, conclusion="failure"), run_of(9, suite=OTHER_SUITE))
        self.assertRefused(result, "concluded failure")

    def test_a_gate_only_another_workflow_ran_is_refused(self):
        self.assertRefused(self.gate(run_of(9, suite=OTHER_SUITE)), "ci has no gate check run")

    def test_help_prints_the_usage(self):
        result = self.script("release_gate.sh", "--help")
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("usage: release_gate.sh <commit>", result.stderr)


if __name__ == "__main__":
    unittest.main()
