"""check_workflows.py: the house rules every workflow keeps, each finding planted in a workflow made for the test,
and the repository's own workflows keep them."""
import unittest

from support import ROOT, ScriptTest

CHECKOUT = "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1"

WORKFLOW = f"""name: probe

on:
  workflow_dispatch:
    inputs:
      tag:
        description: The tag
        required: true
        type: string

permissions:
  contents: read

jobs:
  first:
    runs-on: ubuntu-24.04
    timeout-minutes: 5
    steps:
      - uses: {CHECKOUT}
        with:
          persist-credentials: false
      - name: Step
        timeout-minutes: 2
        run: echo first

  second:
    needs: first
    runs-on: ubuntu-24.04
    timeout-minutes: 5
    steps:
      - name: Step
        uses: {CHECKOUT}
"""


TAG_INPUT = """  workflow_dispatch:
    inputs:
      tag:
        description: The tag
        required: true
        type: string
"""

TAG_ON = "on:\n" + TAG_INPUT

CANDIDATE_ON = """  push:
    tags: ["v*.*.*"]
  workflow_dispatch: *d
"""

# The jobs come before on:, as an anchor comes before its alias.
ALIASED_INPUT = f"""name: probe

permissions:
  contents: read

jobs:
  first:
    runs-on: ubuntu-24.04
    timeout-minutes: 5
    strategy:
      matrix:
        include:
          - wd: &d
              inputs:
                skip_evidence:
                  type: boolean
    steps:
      - uses: {CHECKOUT}

on:
{TAG_INPUT}  workflow_call: *d
"""


class CheckWorkflowsTest(ScriptTest):
    def check(self, text):
        workflow = self.scratch / "probe.yml"
        workflow.write_text(text)
        return self.script("check_workflows.py", workflow)

    def test_the_repositorys_workflows_keep_the_rules(self):
        # Every file there, as GitHub runs a .yaml file too: one the check never read would run unchecked.
        workflows = sorted((ROOT / ".github" / "workflows").glob("*"))
        self.assertEqual(["candidate.yml", "ci.yml", "promote.yml"], [path.name for path in workflows])
        self.assertPassed(self.script("check_workflows.py", *workflows))

    def test_a_workflow_that_keeps_them_passes(self):
        self.assertPassed(self.check(WORKFLOW))

    def test_a_job_without_timeout_minutes_is_refused(self):
        # The step's own timeout is no job's.
        planted = WORKFLOW.replace("    timeout-minutes: 5\n    steps:\n      - uses", "    steps:\n      - uses")
        self.assertRefused(self.check(planted), "probe.yml:15: job first has no timeout-minutes")

    def test_an_action_pinned_by_a_tag_is_refused(self):
        planted = WORKFLOW.replace(CHECKOUT, "actions/checkout@v7.0.1", 1)
        self.assertRefused(self.check(planted), "probe.yml:19: an action not pinned by a full commit")

    def test_an_action_pinned_without_its_version_is_refused(self):
        planted = WORKFLOW.replace(CHECKOUT, CHECKOUT.split(" #")[0], 1)
        self.assertRefused(self.check(planted), "probe.yml:19: an action not pinned by a full commit")

    def test_an_action_in_flow_style_is_refused(self):
        planted = WORKFLOW.replace(f"      - uses: {CHECKOUT}\n", f"      - {{ uses: {CHECKOUT} }}\n")
        self.assertRefused(self.check(planted), "an action not pinned by a full commit")

    def test_an_input_named_skip_evidence_is_refused(self):
        planted = WORKFLOW.replace(
            "        type: string\n",
            "        type: string\n      skip_evidence:\n        type: boolean\n        default: false\n",
        )
        self.assertRefused(self.check(planted), "input skip_evidence is named for a way around a check")

    def test_an_input_named_force_publish_is_refused(self):
        planted = WORKFLOW.replace("      tag:\n", "      force-publish:\n")
        self.assertRefused(self.check(planted), "input force-publish is named for a way around a check")

    def test_pull_request_target_is_refused(self):
        planted = WORKFLOW.replace("on:\n  workflow_dispatch:", "on:\n  pull_request_target:\n  workflow_dispatch:")
        self.assertRefused(self.check(planted), "pull_request_target, which runs a fork's code")

    def test_a_workflow_without_permissions_is_refused(self):
        planted = WORKFLOW.replace("permissions:\n  contents: read\n", "")
        self.assertRefused(self.check(planted), "no top-level permissions:")

    def test_a_line_it_cannot_place_is_refused(self):
        planted = WORKFLOW.replace("  second:\n", "  second: {}\n  third:\n")
        self.assertRefused(self.check(planted), "neither a job nor indented under one")

    def test_a_second_on_is_refused(self):
        # A parser that keeps the last of two keys reads the second on: alone, and the first's inputs not at all;
        # actionlint refuses the second key too, but the check does not lean on it.
        planted = WORKFLOW.replace("        type: string\n", "        type: string\n      skip_evidence:\n"
                                   "        type: boolean\n") + "\non:\n  push:\n"
        self.assertRefused(self.check(planted), "probe.yml:36: a second top-level on:")

    def test_a_tab_or_a_line_break_other_than_a_line_feed_is_refused(self):
        # Python's splitlines splits at the form feed and YAML does not: the line is one, and refused.
        for planted, line in (
            (WORKFLOW.replace("    runs-on: ubuntu-24.04\n", "\truns-on: ubuntu-24.04\n", 1), 16),
            (WORKFLOW.replace("        run: echo first\n", "        run: echo first\f        uses: x/y@v1\n"), 24),
        ):
            with self.subTest(line):
                self.assertRefused(self.check(planted), f"probe.yml:{line}: a tab, a control character")

    # Each of these is valid YAML that actionlint passes, in a layout other than the house's: the check refuses
    # it rather than read past it.

    def test_jobs_indented_by_four_are_refused(self):
        head, jobs = WORKFLOW.split("jobs:\n")
        planted = head + "jobs:\n" + "".join(f"  {line}\n" if line else "\n" for line in jobs.splitlines())
        self.assertRefused(self.check(planted), "probe.yml:15: a line in jobs: before any job")

    def test_an_input_nested_deeper_is_refused(self):
        planted = WORKFLOW.replace("    inputs:\n", "    inputs:\n        skip_evidence:\n          type: boolean\n")
        self.assertRefused(self.check(planted), "probe.yml:6: a line of inputs: deeper than an input")

    def test_inputs_in_flow_style_are_refused(self):
        block = "    inputs:\n      tag:\n        description: The tag\n        required: true\n        type: string\n"
        flow = "    inputs: {tag: {type: string}, skip_evidence: {type: boolean, default: false}}\n"
        self.assertRefused(self.check(WORKFLOW.replace(block, flow)), "probe.yml:5: inputs in a form")

    def test_a_workflow_dispatch_in_flow_style_is_refused(self):
        block = "  workflow_dispatch:\n    inputs:\n      tag:\n        description: The tag\n        required: true\n"
        planted = WORKFLOW.replace(block + "        type: string\n", "  workflow_dispatch: {inputs: {skip: {}}}\n")
        self.assertRefused(self.check(planted), "probe.yml:4: a flow mapping")

    def test_a_quoted_uses_key_is_refused(self):
        planted = WORKFLOW.replace(f"      - uses: {CHECKOUT}\n", '      - "uses": actions/checkout@v4\n')
        self.assertRefused(self.check(planted), "probe.yml:19: an action not pinned by a full commit")

    def test_a_quoted_key_with_an_escape_is_refused(self):
        planted = WORKFLOW.replace(f"      - uses: {CHECKOUT}\n", '      - "u\\x73es": actions/checkout@v4\n')
        self.assertRefused(self.check(planted), "probe.yml:19: a quoted value left open or with an escape, or a quoted")

    # Each of these is valid YAML that actionlint passes and a YAML parser reads as a way around a rule: a value
    # in a form the check would read as something it is not. The check refuses the form, whatever it hides.

    def test_an_input_brought_in_by_an_alias_is_refused(self):
        # An anchor in a job's matrix, in block form, and workflow_call takes it by its alias: skip_evidence.
        for name in ("probe.yml", "promote.yml"):
            with self.subTest(name):
                workflow = self.scratch / name
                workflow.write_text(ALIASED_INPUT)
                result = self.script("check_workflows.py", workflow)
                self.assertRefused(result, f"{name}:13: an anchor, an alias or a tag", f"{name}:27: an anchor")

    def test_an_alias_in_candidate_is_refused(self):
        workflow = self.scratch / "candidate.yml"
        workflow.write_text(ALIASED_INPUT.replace(f"{TAG_INPUT}  workflow_call: *d\n", CANDIDATE_ON))
        result = self.script("check_workflows.py", workflow)
        self.assertRefused(result, "candidate.yml:13: an anchor, an alias or a tag", "candidate.yml:23: an anchor")

    def test_an_escaped_uses_key_in_a_flow_sequence_is_refused(self):
        planted = WORKFLOW.replace("  second:\n", '  third:\n    runs-on: ubuntu-24.04\n    timeout-minutes: 5\n'
                                   '    steps: [{"u\\x73es": "actions/checkout@v4"}]\n\n  second:\n')
        self.assertRefused(self.check(planted), "probe.yml:29: a flow sequence of anything but plain or quoted words")

    def test_a_flow_mapping_is_refused(self):
        planted = WORKFLOW.replace("        with:\n          persist-credentials: false\n",
                                   "        with: {persist-credentials: false}\n")
        self.assertRefused(self.check(planted), "probe.yml:20: a flow mapping")

    def test_an_input_hidden_in_a_quoted_value_left_open_is_refused(self):
        # The description runs on to its closing quote, past a fake block scalar whose text hides an escaped key.
        planted = WORKFLOW.replace(
            "        description: The tag\n",
            '        description: "The candidate\'s tag\n    x: |\n        "\n      "\\x73kip_evidence":\n'
            "        description: skip\n",
        )
        self.assertRefused(self.check(planted), "probe.yml:7: a quoted value left open")

    def test_an_action_hidden_in_a_quoted_value_left_open_is_refused(self):
        planted = WORKFLOW.replace(
            f"      - name: Step\n        uses: {CHECKOUT}\n",
            '      -   name: "cosign\n        run: |\n          "\n          "\\x75ses": actions/checkout@v4\n',
        )
        self.assertRefused(self.check(planted), "probe.yml:31: a quoted value left open")

    def test_a_timeout_inside_a_quoted_name_is_refused(self):
        # The job's name runs over its timeout-minutes line, which is then no key of the job's.
        planted = WORKFLOW.replace(
            "    runs-on: ubuntu-24.04\n    timeout-minutes: 5\n    steps:\n      - name",
            '    runs-on: ubuntu-24.04\n    name: "second\n    timeout-minutes: 5\n    x: y"\n    steps:\n      - name',
        )
        self.assertRefused(self.check(planted), "probe.yml:29: a quoted value left open")

    def test_a_plain_value_continued_on_the_next_line_is_refused(self):
        # run is "echo first - second": the item is the value's words, not a list.
        planted = WORKFLOW.replace("        run: echo first\n", "        run: echo first\n          - second\n")
        self.assertRefused(self.check(planted), "probe.yml:25: a value continued from the line before")

    def test_an_escape_in_a_quoted_value_is_refused(self):
        planted = WORKFLOW.replace(TAG_ON, 'on: "pull_request_t\\x61rget"\n')
        self.assertRefused(self.check(planted), "probe.yml:3: a quoted value left open or with an escape")

    def test_an_escape_in_a_flow_sequence_is_refused(self):
        planted = WORKFLOW.replace(TAG_ON, 'on: [push, "pull_request_t\\x61rget"]\n')
        self.assertRefused(self.check(planted), "probe.yml:3: a flow sequence of anything but plain or quoted words")

    def test_promote_takes_the_tag_alone(self):
        workflow = self.scratch / "promote.yml"
        waiver = "        type: string\n      evidence_waiver:\n        type: boolean\n"
        workflow.write_text(WORKFLOW.replace("        type: string\n", waiver))
        result = self.script("check_workflows.py", workflow)
        self.assertRefused(result, "promote.yml takes the inputs evidence_waiver, tag, not tag")

    def test_candidate_takes_no_input(self):
        workflow = self.scratch / "candidate.yml"
        workflow.write_text(WORKFLOW)
        self.assertRefused(self.script("check_workflows.py", workflow), "candidate.yml takes the inputs tag, not none")


if __name__ == "__main__":
    unittest.main()
