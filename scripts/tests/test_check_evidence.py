"""check_evidence.py: promote.yml releases a candidate only on a complete, passing record of it on real phones
(CI/CD design section 4.2). Each refusal is planted in an evidence file of a repository made for the test."""
import copy
import hashlib
import importlib.util
import json
import re
import unittest

from support import ENGINE, REPO, ROOT, SCRIPTS, Repository, ScriptTest, push_access

TAG = "v0.2.0"
APK = b"the draft's APK, as candidate.yml staged it"
ENGINE_COMMIT = "3b9a2c1d" + "0" * 32
NAMES = [
    "process_death", "doze", "lost_acknowledgement", "network_change",
    "daemon_restart", "approval_away", "frozen_socket", "two_devices",
]


def complete_evidence():
    scenarios = [
        {"n": n, "name": name, "passed": True, "handset": "p9", "at": "2026-10-12", "note": ""}
        for n, name in enumerate(NAMES, start=1)
    ]
    scenarios[7]["second_handset"] = "s24"
    return {
        "version": "0.2.0",
        "apk_sha256": hashlib.sha256(APK).hexdigest(),
        "engine": {"version": "0.14.0", "commit": ENGINE_COMMIT, "install": "homebrew"},
        "recorded_by": "owner",
        "handsets": [
            {"id": "p9", "model": "Pixel 9 Pro", "os_build": "BP4A.251205.006", "patch": "2026-09-05"},
            {"id": "s24", "model": "Galaxy S24", "os_build": "AP3A.240905.015", "patch": "2026-09-01"},
        ],
        "device_gate": {
            "handset": "p9", "pairing": True, "attestation": True, "locked_push": True, "at": "2026-10-12",
        },
        "scenarios": scenarios,
    }


def load_check_evidence():
    spec = importlib.util.spec_from_file_location("check_evidence", SCRIPTS / "check_evidence.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CheckEvidenceTest(ScriptTest):
    def setUp(self):
        super().setUp()
        self.repository = Repository(self.scratch / "repository")
        source = {"upstream": {"repository": ENGINE, "commit": "df8d8a4d" + "0" * 32, "release": "v0.12.1"}}
        schema = (ROOT / "release-evidence" / "schema.json").read_text()
        self.tagged = self.repository.commit({
            "contracts/SOURCE.json": json.dumps(source),
            "release-evidence/schema.json": schema,
        })
        self.repository.main_at(self.tagged)
        self.output = self.scratch / "github-output"
        draft = {"tag_name": TAG, "draft": True, "assets": [{"id": 42, "name": "fermix-android-0.2.0.apk"}]}
        self.answer(f"repos/{REPO}/releases", [{"tag_name": "v0.1.0", "draft": False, "assets": []}, draft])
        self.answer(f"repos/{REPO}/releases/assets/42", APK)
        published = {"tag_name": "v0.14.0", "draft": False, "prerelease": False}
        self.answer(f"repos/{ENGINE}/releases/tags/v0.14.0", published)
        self.answer(f"repos/{ENGINE}/commits/v0.14.0", {"sha": ENGINE_COMMIT})

    def check(self, evidence, ref=f"refs/tags/{TAG}", token=None):
        """[evidence] merged to main, then the check, as promote.yml's evidence job runs it on the tag, with the
        job's token unless told."""
        if evidence is not None:
            commit = self.repository.commit({f"release-evidence/{TAG}.json": json.dumps(evidence, indent=2)})
            self.repository.main_at(commit)
        access = token or push_access("promote.yml", "evidence")
        return self.script("check_evidence.py", TAG, ref, self.output, cwd=self.repository.path, **access)

    def planted(self, plant):
        evidence = complete_evidence()
        plant(evidence)
        return self.check(evidence)

    def test_a_complete_passing_record_of_the_drafts_apk_releases_it(self):
        result = self.check(complete_evidence())
        self.assertPassed(result)
        self.assertEqual(f"apk_sha256={hashlib.sha256(APK).hexdigest()}\n", self.output.read_text())

    def test_seven_scenarios_are_refused(self):
        def drop(evidence):
            del evidence["scenarios"][4]

        self.assertRefused(self.planted(drop), "scenario 5 (daemon_restart) is missing")
        self.assertFalse(self.output.exists())

    def test_a_failed_scenario_is_refused(self):
        def fail(evidence):
            evidence["scenarios"][2]["passed"] = False

        self.assertRefused(self.planted(fail), "scenario 3 (lost_acknowledgement) is marked failed")

    def test_scenario_eight_with_one_handset_is_refused(self):
        def one(evidence):
            del evidence["scenarios"][7]["second_handset"]

        self.assertRefused(self.planted(one), "scenario 8 (two_devices) names one handset")

    def test_scenario_eight_with_the_same_handset_twice_is_refused(self):
        def same(evidence):
            evidence["scenarios"][7]["second_handset"] = "p9"

        self.assertRefused(self.planted(same), "scenario 8's second handset, p9, is not another listed handset")

    def test_an_apk_sha256_that_is_not_the_drafts_is_refused(self):
        def other(evidence):
            evidence["apk_sha256"] = hashlib.sha256(b"a build from someone's laptop").hexdigest()

        self.assertRefused(self.planted(other), "is not the draft's APK")

    def test_an_engine_older_than_the_contract_is_refused(self):
        self.answer(f"repos/{ENGINE}/releases/tags/v0.12.0", {"draft": False, "prerelease": False})
        self.answer(f"repos/{ENGINE}/commits/v0.12.0", {"sha": ENGINE_COMMIT})

        def older(evidence):
            evidence["engine"]["version"] = "0.12.0"

        self.assertRefused(self.planted(older), "the engine 0.12.0 is older than 0.12.1")

    def test_an_engine_that_is_no_published_release_is_refused(self):
        def unpublished(evidence):
            evidence["engine"]["version"] = "0.15.0"

        self.assertRefused(self.planted(unpublished), "tezra-io/fermix has no published release v0.15.0")

    def test_an_engine_commit_that_is_not_the_releases_is_refused(self):
        def elsewhere(evidence):
            evidence["engine"]["commit"] = "f" * 40

        self.assertRefused(self.planted(elsewhere), f"tezra-io/fermix's v0.14.0 is {ENGINE_COMMIT}")

    def test_a_device_gate_fact_that_is_not_true_is_refused(self):
        def locked(evidence):
            evidence["device_gate"]["locked_push"] = False

        self.assertRefused(self.planted(locked), "the device gate's locked_push is not true")

    def test_a_scenario_on_an_unlisted_handset_is_refused(self):
        def unlisted(evidence):
            evidence["scenarios"][0]["handset"] = "emulator"

        self.assertRefused(self.planted(unlisted), "scenario 1 ran on emulator, which is not a listed handset")

    def test_a_scenario_under_another_name_is_refused(self):
        def renamed(evidence):
            evidence["scenarios"][1]["name"] = "process_death"

        self.assertRefused(self.planted(renamed), "scenario 2 is doze, not process_death")

    def test_a_field_the_schema_does_not_have_is_refused(self):
        def extra(evidence):
            evidence["skip"] = True

        self.assertRefused(self.planted(extra), "evidence: skip is not a field of it")

    def test_an_impossible_date_is_refused(self):
        def impossible(evidence):
            evidence["device_gate"]["at"] = "2026-02-30"

        self.assertRefused(self.planted(impossible), "device_gate.at, 2026-02-30, is not a date")

    def test_a_version_with_a_trailing_newline_is_refused(self):
        # A pattern's $ is the end of the text, as JSON Schema reads it, never the place before a last newline.
        def newline(evidence):
            evidence["engine"]["version"] = "0.14.0\n"

        self.assertRefused(self.planted(newline), "evidence.engine.version: '0.14.0\\n' does not match")

    def test_evidence_that_is_not_on_main_is_refused(self):
        self.repository.git("checkout", "--quiet", "-b", "evidence")
        self.repository.commit({f"release-evidence/{TAG}.json": json.dumps(complete_evidence())})
        self.assertRefused(self.check(None), f"main has no release-evidence/{TAG}.json")

    def test_a_run_on_a_branch_is_refused(self):
        result = self.check(complete_evidence(), ref="refs/heads/main")
        self.assertRefused(result, "promote.yml runs on refs/heads/main")

    def test_a_token_that_cannot_push_sees_no_draft(self):
        # GitHub lists a draft only to a token with push access, so promote.yml's evidence job holds contents: write.
        result = self.check(complete_evidence(), token={"FAKE_GH_CAN_PUSH": "0"})
        self.assertRefused(result, f"has no one draft release for {TAG}")

    def test_a_tag_with_no_draft_is_refused(self):
        published = {"tag_name": TAG, "draft": False, "assets": [{"id": 42, "name": "fermix-android-0.2.0.apk"}]}
        self.answer(f"repos/{REPO}/releases", [published])
        self.assertRefused(self.check(complete_evidence()), f"has no one draft release for {TAG}")

    def test_a_schema_keyword_the_check_does_not_check_stops_it(self):
        schema = json.loads((ROOT / "release-evidence" / "schema.json").read_text())
        schema["properties"]["version"]["format"] = "semver"
        (self.repository.path / "release-evidence" / "schema.json").write_text(json.dumps(schema))
        result = self.check(complete_evidence())
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("the schema uses ['format']", result.stderr)


class ReleasingExampleTest(unittest.TestCase):
    """docs/RELEASING.md's example evidence file parses against the schema and would release its build."""

    MARKER = "<!-- check_evidence's tests validate this example -->"

    def test_the_documented_example_validates_and_releases(self):
        text = (ROOT / "docs" / "RELEASING.md").read_text()
        self.assertIn(self.MARKER, text)
        block = re.search(r"```json\n(.*?)\n```", text[text.index(self.MARKER):], re.DOTALL).group(1)
        example = json.loads(block)
        check_evidence = load_check_evidence()
        schema = json.loads((ROOT / "release-evidence" / "schema.json").read_text())
        self.assertEqual([], check_evidence.schema_problems(example, schema, schema))
        facts = {
            "version": example["version"],
            "draft_apk_sha256": example["apk_sha256"],
            "engine_problems": [],
            "pinned_engine": check_evidence.pinned_engine_of(json.loads((ROOT / "contracts/SOURCE.json").read_text())),
        }
        self.assertEqual([], check_evidence.release_problems(copy.deepcopy(example), facts))


if __name__ == "__main__":
    unittest.main()
