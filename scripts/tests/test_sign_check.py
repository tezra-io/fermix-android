"""sign_check.sh: a candidate is signed by the release entry of android_signers.json, and by nothing else (C8).

The keys are made for each test: one stands for the owner's release key, one is a throwaway, as CI's own keys
are (C5), and none of them is the owner's.
"""
import json
import unittest

from support import Keys, ScriptTest, checked, signers


class SignCheckTest(ScriptTest):
    def setUp(self):
        super().setUp()
        self.keys = Keys(self.scratch)
        self.release = self.keys.keystore("release")
        self.throwaway = self.keys.keystore("throwaway")
        self.signers_file = self.scratch / "android_signers.json"
        self.write_signers(signers(self.keys.digest(self.release)))

    def write_signers(self, value):
        self.signers_file.write_text(json.dumps(value))

    def candidate(self, apk_key, aab_key, package="io.tezra.fermix"):
        apk = self.keys.sign_apk(self.keys.unsigned_apk("app", package), apk_key, self.scratch / "app.apk")
        aab = self.keys.sign_aab(self.keys.unsigned_aab("app"), aab_key, self.scratch / "app.aab")
        return apk, aab

    def check(self, apk, aab):
        return self.script("sign_check.sh", self.signers_file, apk, aab)

    def test_a_candidate_signed_with_the_release_key_passes(self):
        result = self.check(*self.candidate(self.release, self.release))
        self.assertPassed(result)
        self.assertIn(self.keys.digest(self.release), result.stdout)

    def test_a_candidate_signed_with_the_wrong_key_is_refused(self):
        apk, aab = self.candidate(self.throwaway, self.release)
        self.assertRefused(
            self.check(apk, aab),
            f"is signed by {self.keys.digest(self.throwaway)}, not by the release certificate",
        )

    def test_a_bundle_signed_with_the_wrong_key_is_refused(self):
        apk, aab = self.candidate(self.release, self.throwaway)
        self.assertRefused(self.check(apk, aab), f"app.aab is signed by {self.keys.digest(self.throwaway)}")

    def test_a_development_key_is_no_release_key(self):
        self.write_signers(signers("0" * 64, development_digest=self.keys.digest(self.throwaway)))
        apk, aab = self.candidate(self.throwaway, self.throwaway)
        self.assertRefused(self.check(apk, aab), "not by the release certificate " + "0" * 64)

    def test_android_signers_absent_is_refused(self):
        apk, aab = self.candidate(self.release, self.release)
        self.signers_file.unlink()
        self.assertRefused(self.check(apk, aab), "android_signers.json is absent", "stage D2")

    def test_a_file_with_two_release_entries_is_refused(self):
        twice = signers(self.keys.digest(self.release))
        twice["signers"].append({"role": "release", "sha256": "1" * 64, "added": "2026-10-05"})
        self.write_signers(twice)
        self.assertRefused(self.check(*self.candidate(self.release, self.release)), "2 release entries, not one")

    def test_an_upper_case_digest_is_refused(self):
        self.write_signers(signers(self.keys.digest(self.release).upper()))
        self.assertRefused(self.check(*self.candidate(self.release, self.release)), "not lower-case 64 hex")

    def test_another_package_is_refused(self):
        apk, aab = self.candidate(self.release, self.release, package="io.tezra.other")
        self.assertRefused(self.check(apk, aab), "is io.tezra.other, but")

    def test_an_unsigned_bundle_is_refused(self):
        apk, _ = self.candidate(self.release, self.release)
        self.assertRefused(self.check(apk, self.keys.unsigned_aab("bare")), "is not signed")

    def test_a_bundle_with_an_entry_its_signature_does_not_cover_is_refused(self):
        # jarsigner says "jar verified." of such a bundle, and only warns of the entry.
        apk, aab = self.candidate(self.release, self.release)
        added = self.scratch / "added"
        (added / "base").mkdir(parents=True)
        (added / "base" / "added.pb").write_bytes(b"put in after signing")
        checked(["zip", "-q", aab, "base/added.pb"], cwd=added)
        self.assertRefused(self.check(apk, aab), "holds entries its signature does not cover")


if __name__ == "__main__":
    unittest.main()
