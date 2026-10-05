"""verify_candidate.sh: candidate.yml's verify refuses a signed candidate that apksigner does not verify, that is
not aligned, that is debuggable or another package, whose APK or bundle is not the tag's versionName with
version.properties' versionCode, whose APK and bundle disagree, or whose APK or bundle fails the release policy
(CI/CD design section 4.1); then writes SHA256SUMS over what it verified.

Each test plants its failure in a small APK and bundle that aapt2 links and a throwaway key signs. The script
runs from a tree of its own, with version.properties at versionCode 7 and a stand-in for
check_release_policy.sh, whose own checks need the app's whole manifest and are the policy job's to prove: the
stand-in records each APK it was given and passes or fails as the test says."""
import shutil
import unittest

from support import Keys, ScriptTest, checked, run, script_tree

TAG = "v0.2.0"
APK = "fermix-android-0.2.0.apk"
AAB = "fermix-android-0.2.0.aab"
MAPPING = "fermix-android-0.2.0-mapping.txt"
POLICY = """#!/usr/bin/env bash
# A stand-in for check_release_policy.sh: records the APK it is given with its manifest's first two bytes, 0300
# for the binary XML the policy reads, and exits FAKE_POLICY_STATUS for the candidate's APK and
# FAKE_BUNDLE_POLICY_STATUS for any other, the bundle's base module.
echo "$1 $(unzip -p "$1" AndroidManifest.xml | head -c 2 | od -An -tx1 | tr -d ' \\n')" >>"$FAKE_POLICY_LOG"
case "$1" in
  "$FAKE_CANDIDATE"/*) status=$FAKE_POLICY_STATUS ;;
  *) status=$FAKE_BUNDLE_POLICY_STATUS ;;
esac
echo "check_release_policy: the stand-in exits $status"
exit "$status"
"""


class VerifyCandidateTest(ScriptTest):
    def setUp(self):
        super().setUp()
        self.keys = Keys(self.scratch)
        self.store = self.keys.keystore("release")
        self.tree = script_tree(self.scratch, "verify_candidate.sh")
        policy = self.tree / "scripts" / "check_release_policy.sh"
        policy.write_text(POLICY)
        policy.chmod(0o755)
        (self.tree / "version.properties").write_text("# versionCode only ever goes up.\nversionCode=7\n")
        self.policy_log = self.scratch / "policy.log"
        self.candidate = self.scratch / "candidate"
        self.candidate.mkdir()

    def place(self, apk=None, aab=None, preserve_alignment=False):
        """The signed candidate, as sign leaves it, with [apk] and [aab] in place of the right ones when given."""
        unsigned = apk or self.keys.linked_apk("app")
        self.keys.sign_apk(unsigned, self.store, self.candidate / APK, preserve_alignment)
        self.keys.sign_aab(aab or self.keys.linked_aab("app"), self.store, self.candidate / AAB)
        (self.candidate / MAPPING).write_text("io.tezra.fermix.MainActivity -> a:\n")

    def verify(self, policy_status=0, bundle_policy_status=0):
        env = self.environment(
            FAKE_POLICY_LOG=self.policy_log, FAKE_CANDIDATE=self.candidate, FAKE_POLICY_STATUS=policy_status,
            FAKE_BUNDLE_POLICY_STATUS=bundle_policy_status,
        )
        return run([self.tree / "scripts" / "verify_candidate.sh", TAG, self.candidate], env=env)

    def test_a_signed_aligned_candidate_of_the_tags_version_passes(self):
        self.place()
        self.assertPassed(self.verify())
        apk, bundle = self.policy_log.read_text().splitlines()
        self.assertEqual(f"{self.candidate / APK} 0300", apk)
        # The bundle's base module as Play builds from it, its manifest converted to the binary XML the policy reads.
        self.assertFalse(bundle.startswith(str(self.candidate)), bundle)
        self.assertTrue(bundle.endswith(" 0300"), bundle)
        sums = (self.candidate / "SHA256SUMS").read_text()
        self.assertEqual(sorted([APK, AAB, MAPPING]), sorted(line.split()[1] for line in sums.splitlines()))
        checked(["sha256sum", "--check", "--strict", "SHA256SUMS"], cwd=self.candidate)

    def test_an_unsigned_apk_is_refused(self):
        self.place()
        shutil.copy(self.keys.linked_apk("bare"), self.candidate / APK)
        self.assertRefused(self.verify(), "apksigner does not verify")

    def test_an_unaligned_apk_is_refused(self):
        # An uncompressed native library off a 16 KB page, signed as it lay: apksigner verifies it all the same.
        unsigned = self.keys.linked_apk("app")
        library = self.scratch / "library"
        (library / "lib" / "arm64-v8a").mkdir(parents=True)
        (library / "lib" / "arm64-v8a" / "libplanted.so").write_bytes(bytes(1000))
        checked(["zip", "-q", "-0", unsigned, "lib/arm64-v8a/libplanted.so"], cwd=library)
        self.place(apk=unsigned, preserve_alignment=True)
        self.assertRefused(self.verify(), "is not aligned (zipalign -c -P 16 4)")

    def test_an_apk_that_fails_the_release_policy_is_refused(self):
        self.place()
        self.assertRefused(self.verify(policy_status=1), "the signed APK fails the release policy")
        self.assertFalse((self.candidate / "SHA256SUMS").exists())

    def test_a_bundle_that_fails_the_release_policy_is_refused(self):
        # Play builds every split a phone installs from the bundle, so the policy holds its base module too.
        self.place()
        result = self.verify(bundle_policy_status=1)
        self.assertRefused(result, "the app bundle's base module fails the release policy")
        self.assertFalse((self.candidate / "SHA256SUMS").exists())

    def test_a_bundle_with_a_module_besides_base_is_refused(self):
        aab = self.keys.linked_aab("featured")
        content = self.scratch / "feature-module"
        (content / "feature" / "manifest").mkdir(parents=True)
        (content / "feature" / "manifest" / "AndroidManifest.xml").write_bytes(b"a module the policy never read")
        checked(["zip", "-q", "-r", aab, "feature"], cwd=content)
        self.place(aab=aab)
        self.assertRefused(self.verify(), "holds a module besides base: feature")

    def test_a_debuggable_apk_is_refused(self):
        self.place(apk=self.keys.linked_apk("debuggable", debuggable=True))
        result = self.verify()
        self.assertRefused(result, "the APK is 'io.tezra.fermix 7 0.2.0 yes'", "not 'io.tezra.fermix 7 0.2.0 no'")

    def test_another_package_is_refused(self):
        self.place(apk=self.keys.linked_apk("other", package="io.tezra.other"))
        self.assertRefused(self.verify(), "the APK is 'io.tezra.other 7 0.2.0 no'")

    def test_a_versionname_that_is_not_the_tags_is_refused(self):
        self.place(apk=self.keys.linked_apk("later", version="0.2.1"))
        self.assertRefused(self.verify(), "the APK is 'io.tezra.fermix 7 0.2.1 no'")

    def test_a_versioncode_that_is_not_version_properties_is_refused(self):
        self.place(apk=self.keys.linked_apk("eight", code=8))
        self.assertRefused(self.verify(), "the APK is 'io.tezra.fermix 8 0.2.0 no'")

    def test_a_bundle_whose_versioncode_disagrees_with_the_apk_is_refused(self):
        self.place(aab=self.keys.linked_aab("eight", code=8))
        self.assertRefused(self.verify(), "the app bundle is 'io.tezra.fermix 8 0.2.0 no'", "they disagree")

    def test_a_debuggable_bundle_is_refused(self):
        self.place(aab=self.keys.linked_aab("debuggable", debuggable=True))
        self.assertRefused(self.verify(), "the app bundle is 'io.tezra.fermix 7 0.2.0 yes'", "they disagree")

    def test_a_bundle_without_a_base_module_is_refused(self):
        self.place(aab=self.keys.unsigned_aab("bare"))
        self.assertRefused(self.verify(), "has no base/manifest/AndroidManifest.xml and base/resources.pb")


if __name__ == "__main__":
    unittest.main()
