"""The draft and the published release: stage_candidate.sh stages what verify hashed, publish_release.sh
publishes the draft the evidence named once its files are checked again, and check_release.sh refuses a draft or
a published release whose files are not the signed, tested candidate's (CI/CD design sections 4.1 and 4.3).
cosign is the fake one, which checks a bundle against the blob's sha256 and the identity asked for, as no test
has a workflow identity to sign with. Each script runs with the token of the job that runs it."""
import json
import pathlib
import unittest

from support import REPO, Keys, ScriptTest, checked, push_access, sha256_of, signers

TAG = "v0.2.0"
VERSION = "0.2.0"
IDENTITY = f"https://github.com/{REPO}/.github/workflows/candidate.yml@refs/tags/{TAG}"
FILES = [
    f"fermix-android-{VERSION}.apk",
    f"fermix-android-{VERSION}.aab",
    f"fermix-android-{VERSION}-mapping.txt",
    "SHA256SUMS",
    "SHA256SUMS.cosign.bundle",
]


class ReleaseFilesTest(ScriptTest):
    def setUp(self):
        super().setUp()
        self.keys = Keys(self.scratch)
        self.release_key = self.keys.keystore("release")
        self.signers_file = self.scratch / "android_signers.json"
        self.signers_file.write_text(json.dumps(signers(self.keys.digest(self.release_key))))
        self.candidate = self.candidate_signed_by(self.release_key)

    def candidate_signed_by(self, store):
        """A verified candidate's directory: signed files, SHA256SUMS over three, and its cosign bundle."""
        directory = self.scratch / f"candidate-{store.stem}"
        directory.mkdir()
        self.keys.sign_apk(self.keys.unsigned_apk(store.stem), store, directory / FILES[0])
        self.keys.sign_aab(self.keys.unsigned_aab(store.stem), store, directory / FILES[1])
        (directory / FILES[2]).write_text("io.tezra.fermix.MainActivity -> a:\n")
        sums = checked(["sha256sum", *FILES[:3]], cwd=directory)
        (directory / "SHA256SUMS").write_text(sums)
        env = self.environment(FAKE_COSIGN_IDENTITY=IDENTITY)
        checked(["cosign", "sign-blob", "--yes", "--bundle", directory / FILES[4], directory / "SHA256SUMS"], env=env)
        return directory

    def serve(self, directory, draft):
        """GitHub holds [directory]'s five files as the tag's release, a draft or published."""
        assets = [{"id": index, "name": name} for index, name in enumerate(FILES, start=1)]
        for asset in assets:
            self.answer(f"repos/{REPO}/releases/assets/{asset['id']}", (directory / asset["name"]).read_bytes())
        release = {"id": 77, "tag_name": TAG, "draft": draft, "assets": assets}
        self.answer(f"repos/{REPO}/releases", [release])
        self.answer(f"repos/{REPO}/releases/77", {**release, "draft": False})

    def apk_sha256(self, directory=None):
        return sha256_of((directory or self.candidate) / FILES[0])

    def check_the_published(self, expected=None):
        """check_release.sh on the published release, as promote.yml's after-publish runs it."""
        downloaded = self.scratch / "downloaded"
        return self.script(
            "check_release.sh", TAG, "published", expected or self.apk_sha256(), self.signers_file, downloaded,
            **push_access("promote.yml", "after-publish"),
        )

    def publish(self, expected=None):
        """publish_release.sh, as promote.yml's publish runs it."""
        return self.script(
            "publish_release.sh", TAG, expected or self.apk_sha256(), self.signers_file,
            **push_access("promote.yml", "publish"),
        )

    def patches(self):
        return [call for call in self.calls() if "PATCH" in call]

    # check_release.sh on the published release

    def test_the_published_candidate_passes(self):
        self.serve(self.candidate, draft=False)
        self.assertPassed(self.check_the_published())

    def test_a_changed_byte_in_sha256sums_is_refused(self):
        sums = self.candidate / "SHA256SUMS"
        text = sums.read_text()
        sums.write_text(text[:5] + ("0" if text[5] != "0" else "1") + text[6:])
        self.serve(self.candidate, draft=False)
        self.assertRefused(self.check_the_published(), "cosign does not verify SHA256SUMS")

    def test_a_changed_byte_in_a_file_is_refused(self):
        mapping = self.candidate / FILES[2]
        mapping.write_text(mapping.read_text().replace("a:", "b:"))
        self.serve(self.candidate, draft=False)
        self.assertRefused(self.check_the_published(), "the files do not hash to SHA256SUMS")

    def test_a_bundle_signed_as_another_workflow_is_refused(self):
        bundle = self.candidate / FILES[4]
        forged = json.loads(bundle.read_text())
        forged["identity"] = f"https://github.com/{REPO}/.github/workflows/ci.yml@refs/heads/dev"
        bundle.write_text(json.dumps(forged))
        self.serve(self.candidate, draft=False)
        self.assertRefused(self.check_the_published(), f"as signed by {IDENTITY}")

    def test_an_apk_that_is_not_the_evidences_is_refused(self):
        self.serve(self.candidate, draft=False)
        self.assertRefused(self.check_the_published(expected="0" * 64), "not " + "0" * 64 + " as the evidence names")

    def test_a_candidate_signed_with_the_wrong_key_is_refused(self):
        wrong = self.candidate_signed_by(self.keys.keystore("throwaway"))
        self.serve(wrong, draft=False)
        result = self.check_the_published(expected=self.apk_sha256(wrong))
        self.assertRefused(result, "not by the release certificate")

    def test_a_draft_is_not_a_published_release(self):
        self.serve(self.candidate, draft=True)
        self.assertRefused(self.check_the_published(), f"has no one published release for {TAG}")

    def test_the_play_job_checks_the_published_release_with_its_own_token(self):
        self.serve(self.candidate, draft=False)
        result = self.script(
            "check_release.sh", TAG, "published", self.apk_sha256(), self.signers_file, self.scratch / "play",
            **push_access("promote.yml", "play"),
        )
        self.assertPassed(result)

    def test_an_extra_file_in_the_release_is_refused(self):
        self.serve(self.candidate, draft=False)
        listing = json.loads((self.github / "api" / f"repos/{REPO}/releases" / "_").read_text())
        listing[0]["assets"].append({"id": 9, "name": "app-debug.apk"})
        self.answer(f"repos/{REPO}/releases", listing)
        self.assertRefused(self.check_the_published(), "not the five files candidate.yml staged")

    # publish_release.sh, which checks the draft as check_release.sh does before it publishes anything

    def test_publish_publishes_the_draft_the_evidence_names(self):
        self.serve(self.candidate, draft=True)
        self.assertPassed(self.publish())
        self.assertEqual([["api", "-X", "PATCH", f"repos/{REPO}/releases/77", "-F", "draft=false"]], self.patches())

    def test_publish_refuses_a_draft_whose_apk_changed_after_the_evidence(self):
        self.serve(self.candidate, draft=True)
        self.assertRefused(self.publish(expected="0" * 64), "not " + "0" * 64 + " as the evidence names")
        self.assertEqual([], self.patches())

    def test_publish_refuses_a_draft_whose_bundle_was_swapped(self):
        # Another build, signed with the release key too: only SHA256SUMS tells it from the tested one.
        unsigned = self.keys.unsigned_aab("swapped")
        other = self.scratch / "other-build"
        (other / "base").mkdir(parents=True)
        (other / "base" / "classes.pb").write_bytes(b"another build's code")
        checked(["zip", "-q", unsigned, "base/classes.pb"], cwd=other)
        swapped = self.keys.sign_aab(unsigned, self.release_key, self.scratch / "swapped.aab")
        (self.candidate / FILES[1]).write_bytes(swapped.read_bytes())
        self.serve(self.candidate, draft=True)
        self.assertRefused(self.publish(), "the files do not hash to SHA256SUMS")
        self.assertEqual([], self.patches())

    def test_publish_refuses_a_draft_whose_cosign_bundle_is_not_candidates(self):
        bundle = self.candidate / FILES[4]
        forged = json.loads(bundle.read_text())
        forged["identity"] = f"https://github.com/{REPO}/.github/workflows/ci.yml@refs/heads/dev"
        bundle.write_text(json.dumps(forged))
        self.serve(self.candidate, draft=True)
        self.assertRefused(self.publish(), f"as signed by {IDENTITY}")
        self.assertEqual([], self.patches())

    def test_publish_refuses_a_draft_with_an_extra_file(self):
        self.serve(self.candidate, draft=True)
        listing = json.loads((self.github / "api" / f"repos/{REPO}/releases" / "_").read_text())
        listing[0]["assets"].append({"id": 9, "name": "app-debug.apk"})
        self.answer(f"repos/{REPO}/releases", listing)
        self.assertRefused(self.publish(), "not the five files candidate.yml staged")
        self.assertEqual([], self.patches())

    def swapped_apk(self, release_id=77):
        """Release [release_id] as it reads after someone deleted the checked APK and uploaded another under its
        name: GitHub gives an upload a new id, and no asset's bytes change under its id."""
        listing = json.loads((self.github / "api" / f"repos/{REPO}/releases" / "_").read_text())
        assets = [asset if asset["name"] != FILES[0] else {"id": 6, "name": FILES[0]} for asset in listing[0]["assets"]]
        return {**listing[0], "id": release_id, "draft": False, "assets": assets}

    def test_publish_refuses_a_draft_whose_files_changed_after_the_check(self):
        self.serve(self.candidate, draft=True)
        self.answer(f"repos/{REPO}/releases/77", self.swapped_apk())
        self.assertRefused(self.publish(), "release 77's files changed after they were checked; nothing is published")
        self.assertEqual([], self.patches())

    def test_publish_takes_back_a_release_whose_files_changed_as_it_was_published(self):
        self.serve(self.candidate, draft=True)
        after = self.github / "api" / f"repos/{REPO}/releases/77" / "_.after"
        after.write_text(json.dumps(self.swapped_apk()))
        result = self.publish()
        self.assertRefused(result, "release 77's files changed as it was published; it is a draft again")
        draft_again = ["api", "-X", "PATCH", f"repos/{REPO}/releases/77", "-F", "draft=true"]
        self.assertEqual(draft_again, self.patches()[-1])

    # stage_candidate.sh

    def stage(self, directory):
        """Stages [directory] as verify leaves it, with no bundle yet, from a checkout of the tag."""
        (directory / FILES[4]).unlink()
        self.answer(f"repos/{REPO}/releases", [])
        checkout = self.scratch / "checkout"
        (checkout / "contracts").mkdir(parents=True)
        (checkout / "CHANGELOG.md").write_text(f"# Changelog\n\n## [{VERSION}] - 2026-10-05\n\n- What changed.\n")
        window = {"minimum": 1, "maximum": 2}
        contract = {"name": "mobile", "supported_version_range": window}
        source = {"upstream": {"release": "v0.14.0"}, "contracts": [contract]}
        (checkout / "contracts" / "SOURCE.json").write_text(json.dumps(source))
        session = checkout / "core-session/src/main/kotlin/io/tezra/fermix/session/SecureChannel.kt"
        session.parent.mkdir(parents=True)
        session.write_text("package io.tezra.fermix.session\n\ninternal const val SESSION_VERSION = 2\n")
        return self.script(
            "stage_candidate.sh", TAG, directory, cwd=checkout, FAKE_COSIGN_IDENTITY=IDENTITY,
            **push_access("candidate.yml", "stage"),
        )

    def test_stage_makes_a_draft_of_the_verified_files_signed_as_the_workflow(self):
        self.assertPassed(self.stage(self.candidate))
        create = next(call for call in self.calls() if call[:2] == ["release", "create"])
        self.assertIn("--draft", create)
        self.assertIn("--verify-tag", create)
        staged = sorted(path.name for path in (self.github / "created").iterdir())
        self.assertEqual(sorted(FILES), staged)
        notes = pathlib.Path(create[create.index("--notes-file") + 1]).read_text()
        self.assertIn(f"--certificate-identity {IDENTITY}", notes)
        # The app's own protocol, not the contract's newest: the notes say what this build speaks.
        self.assertIn("Speaks mobile protocol 2, which the Fermix engine v0.14.0 serves", notes)

    def test_stage_refuses_files_that_are_not_the_verified_ones(self):
        (self.candidate / FILES[2]).write_text("another build's mapping\n")
        self.assertRefused(self.stage(self.candidate), "the files are not the ones verify hashed")
        self.assertFalse((self.github / "created").exists())


if __name__ == "__main__":
    unittest.main()
