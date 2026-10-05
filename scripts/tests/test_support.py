"""The tests' own helpers keep a test's git to the test (AGENTS.md)."""
import unittest

from support import ROOT, Repository, ScriptTest, checked, git_environment, run, tool_environment

# What git init copies from a stock template: sample hooks, an exclude file, a description and a branches directory.
TEMPLATE_ENTRIES = ["hooks", "info", "description", "branches"]


class SupportTest(ScriptTest):
    def test_a_repository_a_test_or_a_script_makes_holds_nothing_of_the_machines_template(self):
        # git runs the hooks a template puts in a repository, so on any stock machine this shows whether the git of
        # a test's set-up, or of a script it runs, took anything of it.
        made = Repository(self.scratch / "repository").path
        checked(["git", "init", "--quiet", self.scratch / "scripted"], cwd=self.scratch, env=self.environment())
        for repository in [made, self.scratch / "scripted"]:
            found = [name for name in TEMPLATE_ENTRIES if (repository / ".git" / name).exists()]
            self.assertEqual([], found, repository)

    def test_a_git_in_a_scratch_directory_finds_no_repository_around_it(self):
        # As when the machine's temporary directory is in a home directory kept in git.
        Repository(self.scratch / "around")
        scratch = self.scratch / "around" / "scratch"
        scratch.mkdir()
        env = git_environment(tool_environment(), scratch)
        result = run(["git", "rev-parse", "--show-toplevel"], cwd=scratch, env=env)
        self.assertEqual(128, result.returncode, result.stdout)

    def test_a_script_runs_in_the_tests_scratch_directory_and_nowhere_else(self):
        # A git a script starts there finds the test's repository or none; in the developer's checkout it would
        # find theirs.
        with self.assertRaises(AssertionError) as refused:
            self.script("changelog_entry.sh", "--help", cwd=ROOT, stdin="")
        self.assertIn("is not under the test's scratch directory", str(refused.exception))


if __name__ == "__main__":
    unittest.main()
