"""build_candidate.sh: candidate.yml's sign builds the release with the owner's google-services.json, unsigned,
and refuses the placeholder's project, a file with no Android client for io.tezra.fermix, a release key offered to
Gradle, a checkout that holds a release file already, and an APK that carries another project (CI/CD design
sections 4.1 and 4.5). However it ends, the release file is gone.

The script runs from a tree of its own whose gradlew is a stand-in: it records what it was run with and what
the release file held while it ran, and leaves the outputs the build would, an APK aapt2 linked with the
project_id the test names."""
import json
import unittest

from support import Keys, ScriptTest, run, script_tree

GRADLEW = """#!/usr/bin/env python3
# A stand-in for gradlew: records its arguments, what the release file held and which secrets it was handed,
# then leaves the release build's outputs, the APK the test made.
import json, os, pathlib, shutil, sys
tree = pathlib.Path(__file__).resolve().parent
release = tree / "app/src/release/google-services.json"
seen = {
    "arguments": sys.argv[1:],
    "release_file": json.loads(release.read_text())["project_info"]["project_id"] if release.exists() else None,
    "secrets": sorted(
        name for name in os.environ if name == "GOOGLE_SERVICES_JSON" or name.startswith("FERMIX_RELEASE")
    ),
}
pathlib.Path(os.environ["FAKE_GRADLE_LOG"]).write_text(json.dumps(seen))
outputs = tree / "app/build/outputs"
for directory in ("apk/release", "bundle/release", "mapping/release"):
    (outputs / directory).mkdir(parents=True, exist_ok=True)
shutil.copy(os.environ["FAKE_GRADLE_APK"], outputs / "apk/release/app-release-unsigned.apk")
(outputs / "bundle/release/app-release.aab").write_bytes(b"the unsigned bundle")
(outputs / "mapping/release/mapping.txt").write_text("io.tezra.fermix.MainActivity -> a:\\n")
"""


def services(project="fermix-release", package="io.tezra.fermix"):
    """A google-services.json as the Firebase console gives one, for [project] with an Android client."""
    client = {"client_info": {"android_client_info": {"package_name": package}}}
    return json.dumps({"project_info": {"project_id": project}, "client": [client], "configuration_version": "1"})


class BuildCandidateTest(ScriptTest):
    def setUp(self):
        super().setUp()
        self.tree = script_tree(self.scratch, "build_candidate.sh")
        gradlew = self.tree / "gradlew"
        gradlew.write_text(GRADLEW)
        gradlew.chmod(0o755)
        self.release_file = self.tree / "app" / "src" / "release" / "google-services.json"
        self.gradle_log = self.scratch / "gradle.json"
        self.out = self.scratch / "unsigned"
        self.apk = Keys(self.scratch).linked_apk("app", project="fermix-release")

    def build(self, google_services=None, apk=None, **extra):
        env = self.environment(
            GOOGLE_SERVICES_JSON=services() if google_services is None else google_services,
            FAKE_GRADLE_LOG=self.gradle_log, FAKE_GRADLE_APK=apk or self.apk, **extra,
        )
        return run([self.tree / "scripts" / "build_candidate.sh", self.out], env=env)

    def gradle_ran(self):
        return json.loads(self.gradle_log.read_text()) if self.gradle_log.exists() else None

    def test_the_release_is_built_with_the_owners_file_which_is_gone_after(self):
        self.assertPassed(self.build())
        built = sorted(path.name for path in self.out.iterdir())
        self.assertEqual(["mapping.txt", "unsigned.aab", "unsigned.apk"], built)
        ran = self.gradle_ran()
        self.assertEqual("fermix-release", ran["release_file"])
        self.assertEqual([], ran["secrets"])
        self.assertIn(":app:assembleRelease", ran["arguments"])
        self.assertFalse(self.release_file.exists())

    def test_no_file_is_refused(self):
        self.assertRefused(self.build(google_services=""), "GOOGLE_SERVICES_JSON is empty")
        self.assertIsNone(self.gradle_ran())

    def test_a_file_that_is_not_json_is_refused_and_shredded(self):
        self.assertRefused(self.build(google_services="project_id: fermix-release"), "is not JSON")
        self.assertFalse(self.release_file.exists())
        self.assertIsNone(self.gradle_ran())

    def test_the_placeholders_project_is_refused_and_shredded(self):
        self.assertRefused(self.build(google_services=services("fermix-placeholder")), "the placeholder's project")
        self.assertFalse(self.release_file.exists())
        self.assertIsNone(self.gradle_ran())

    def test_a_file_without_the_apps_client_is_refused_and_shredded(self):
        result = self.build(google_services=services(package="io.tezra.other"))
        self.assertRefused(result, "has no Android client for io.tezra.fermix")
        self.assertFalse(self.release_file.exists())
        self.assertIsNone(self.gradle_ran())

    def test_a_release_key_offered_to_gradle_is_refused(self):
        result = self.build(FERMIX_RELEASE_STORE_FILE="/keys/fermix-release.jks")
        self.assertRefused(result, "FERMIX_RELEASE_STORE_FILE is set")
        self.assertFalse(self.release_file.exists())
        self.assertIsNone(self.gradle_ran())

    def test_a_checkout_with_a_release_file_already_is_refused_and_keeps_it(self):
        self.release_file.parent.mkdir(parents=True)
        self.release_file.write_text(services("someone-elses"))
        self.assertRefused(self.build(), "exists already; the candidate is built from a clean checkout")
        self.assertEqual(services("someone-elses"), self.release_file.read_text())
        self.assertIsNone(self.gradle_ran())

    def test_an_apk_of_another_project_is_refused_and_the_file_shredded(self):
        placeholder = Keys(self.scratch).linked_apk("placeholder", project="fermix-placeholder")
        result = self.build(apk=placeholder)
        self.assertRefused(result, "the APK carries the Firebase project 'fermix-placeholder', not the release file's")
        self.assertFalse(self.release_file.exists())
        self.assertFalse(self.out.joinpath("unsigned.apk").exists())

    def test_help_prints_the_usage(self):
        result = run([self.tree / "scripts" / "build_candidate.sh", "--help"], env=self.environment())
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("usage: GOOGLE_SERVICES_JSON=... build_candidate.sh <out-dir>", result.stderr)


if __name__ == "__main__":
    unittest.main()
