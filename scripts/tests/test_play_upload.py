"""play_upload.py's dry run: the bundle, the versionCode and the service account are read and the token request
is signed, with nothing sent to Google. The account's key is made for the test. And what the real run decides
from Play's answers, read by importing the script: the bundle Play holds already is reused, this versionCode as
another bundle is refused, and a bundle Play took as another is refused."""
import importlib.util
import json
import unittest

from support import SCRIPTS, ScriptTest, checked, sha256_of

DIGEST = "9f2c4a7e1b3d5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8"
OTHER = "0" * 64


def load_play_upload():
    spec = importlib.util.spec_from_file_location("play_upload", SCRIPTS / "play_upload.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PlayUploadTest(ScriptTest):
    def setUp(self):
        super().setUp()
        self.checkout = self.scratch / "checkout"
        self.checkout.mkdir()
        (self.checkout / "version.properties").write_text("# a comment\nversionCode=7\n")
        self.aab = self.scratch / "fermix-android-0.2.0.aab"
        self.aab.write_bytes(b"the published release's bundle")
        key = self.scratch / "account.pem"
        checked(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", key])
        self.account = {
            "type": "service_account",
            "client_email": "play-upload@fermix-release.iam.gserviceaccount.com",
            "private_key": key.read_text(),
            "token_uri": "https://oauth2.googleapis.com/token",
        }

    def upload(self, account, *extra):
        return self.script(
            "play_upload.py", "v0.2.0", self.aab, "--dry-run", *extra,
            cwd=self.checkout, PLAY_SERVICE_ACCOUNT_JSON=json.dumps(account) if account is not None else "",
        )

    def test_a_dry_run_signs_the_token_request_and_says_what_it_would_send(self):
        result = self.upload(self.account)
        self.assertPassed(result)
        self.assertIn("dry run as play-upload@fermix-release.iam.gserviceaccount.com", result.stdout)
        self.assertIn(f"sha256 {sha256_of(self.aab)}", result.stdout)
        self.assertIn("as versionCode 7", result.stdout)
        self.assertIn("would put release 0.2.0 of versionCode 7, completed, on the internal track", result.stdout)
        self.assertNotIn("PRIVATE KEY", result.stdout + result.stderr)

    def test_no_service_account_is_refused(self):
        self.assertRefused(self.upload(None), "PLAY_SERVICE_ACCOUNT_JSON is empty")

    def test_a_key_that_is_not_a_service_accounts_is_refused(self):
        self.assertRefused(self.upload({**self.account, "type": "authorized_user"}), "is not a service account's key")

    def test_another_token_endpoint_is_refused(self):
        account = {**self.account, "token_uri": "https://example.com/token"}
        self.assertRefused(self.upload(account), "not Google's https://oauth2.googleapis.com/token")

    def test_a_private_key_openssl_cannot_sign_with_is_refused(self):
        account = {**self.account, "private_key": "not a key openssl reads"}
        self.assertRefused(self.upload(account), "openssl could not sign")


class PlayDecisionTest(unittest.TestCase):
    def setUp(self):
        self.play = load_play_upload()

    def test_a_versioncode_play_does_not_hold_is_uploaded(self):
        held = [{"versionCode": 6, "sha256": OTHER}]
        self.assertEqual(("upload", None), self.play.bundle_decision(held, 7, DIGEST))
        self.assertEqual(("upload", None), self.play.bundle_decision([], 7, DIGEST))

    def test_the_bundle_play_holds_already_is_reused(self):
        # The first release, uploaded by hand in the Play Console (docs/RELEASING.md), then the play job again.
        mine = {"versionCode": 7, "sha256": DIGEST}
        held = [{"versionCode": 6, "sha256": OTHER}, mine]
        self.assertEqual(("reuse", mine), self.play.bundle_decision(held, 7, DIGEST))

    def test_this_versioncode_as_another_bundle_is_refused(self):
        with self.assertRaisesRegex(self.play.Refusal, f"Play holds versionCode 7 as another bundle, {OTHER}"):
            self.play.bundle_decision([{"versionCode": 7, "sha256": OTHER}], 7, DIGEST)

    def test_a_bundle_play_took_as_this_one_passes(self):
        self.play.check_taken({"versionCode": 7, "sha256": DIGEST}, 7, DIGEST)

    def test_a_bundle_play_took_as_another_is_refused(self):
        for taken in ({"versionCode": 8, "sha256": DIGEST}, {"versionCode": 7, "sha256": OTHER}, {}):
            with self.subTest(taken), self.assertRaisesRegex(self.play.Refusal, "Play took the bundle as"):
                self.play.check_taken(taken, 7, DIGEST)


if __name__ == "__main__":
    unittest.main()
