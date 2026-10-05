"""sign_candidate.sh: the release key signs the APK and the bundle as sign_check.sh wants them, and is shredded
however the signing ends (CI/CD design section 4.5). A throwaway key stands for the owner's."""
import base64
import json
import shutil
import unittest

from support import Keys, ScriptTest, signers


class SignCandidateTest(ScriptTest):
    def setUp(self):
        super().setUp()
        self.keys = Keys(self.scratch)
        self.store = self.keys.keystore("release")
        self.unsigned = self.scratch / "unsigned"
        self.unsigned.mkdir()
        shutil.copy(self.keys.unsigned_apk("app"), self.unsigned / "unsigned.apk")
        shutil.copy(self.keys.unsigned_aab("app"), self.unsigned / "unsigned.aab")
        (self.unsigned / "mapping.txt").write_text("io.tezra.fermix.MainActivity -> a:\n")
        self.runner_temp = self.scratch / "runner-temp"
        self.runner_temp.mkdir()
        self.out = self.scratch / "signed"

    def sign(self, **overrides):
        secrets = {
            "RELEASE_KEYSTORE_BASE64": base64.b64encode(self.store.read_bytes()).decode(),
            "RELEASE_STORE_PASSWORD": Keys.PASSWORD,
            "RELEASE_KEY_ALIAS": "release",
            "RELEASE_KEY_PASSWORD": Keys.PASSWORD,
            "RUNNER_TEMP": self.runner_temp,
        }
        secrets.update(overrides)
        return self.script("sign_candidate.sh", "v0.2.0", self.unsigned, self.out, **secrets)

    def test_the_signed_candidate_passes_the_signer_check_and_the_key_is_gone(self):
        self.assertPassed(self.sign())
        signers_file = self.scratch / "android_signers.json"
        signers_file.write_text(json.dumps(signers(self.keys.digest(self.store))))
        apk, aab = self.out / "fermix-android-0.2.0.apk", self.out / "fermix-android-0.2.0.aab"
        self.assertPassed(self.script("sign_check.sh", signers_file, apk, aab))
        self.assertTrue((self.out / "fermix-android-0.2.0-mapping.txt").is_file())
        self.assertEqual([], list(self.runner_temp.iterdir()))

    def test_a_signing_that_fails_leaves_no_key_behind(self):
        result = self.sign(RELEASE_STORE_PASSWORD="not-the-password")
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual([], list(self.runner_temp.iterdir()))

    def test_a_missing_secret_is_refused_before_anything_is_written(self):
        self.assertRefused(self.sign(RELEASE_KEY_ALIAS=""), "RELEASE_KEY_ALIAS is empty")
        self.assertEqual([], list(self.runner_temp.iterdir()))


if __name__ == "__main__":
    unittest.main()
