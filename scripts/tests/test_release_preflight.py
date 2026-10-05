"""release_preflight.sh, each refusal planted in a repository made for the test (CI/CD design section 4.1)."""
import json
import unittest

from support import ENGINE, REPO, Repository, ScriptTest, push_access

PIN = "df8d8a4d911b64458a9c908a39de64d817fa5e07"
SECURE_CHANNEL = "core-session/src/main/kotlin/io/tezra/fermix/session/SecureChannel.kt"


def session(version=2):
    return f"package io.tezra.fermix.session\n\ninternal const val SESSION_VERSION = {version}\n"


def changelog(*versions):
    entries = "".join(f"## [{version}] - 2026-10-05\n\n- What {version} changed.\n\n" for version in versions)
    return f"# Changelog\n\n## [Unreleased]\n\n{entries}"


def source(release="v0.12.1", commit=PIN, window=(1, 2)):
    contract = {"name": "mobile", "protocol_version": window[1],
                "supported_version_range": {"minimum": window[0], "maximum": window[1]}}
    upstream = {"repository": ENGINE, "commit": commit, "release": release, "branch": "main"}
    return json.dumps({"upstream": upstream, "contracts": [contract]})


class ReleasePreflightTest(ScriptTest):
    def setUp(self):
        super().setUp()
        self.repository = Repository(self.scratch / "repository")
        first = self.repository.commit({
            "version.properties": "versionCode=1\n",
            "CHANGELOG.md": changelog("0.1.0"),
            "contracts/SOURCE.json": source(),
            SECURE_CHANNEL: session(),
        })
        self.repository.tag("v0.1.0", commit=first)
        self.answer(f"repos/{REPO}/releases", [{"tag_name": "v0.1.0", "draft": False}])
        published = {"tag_name": "v0.12.1", "draft": False, "prerelease": False}
        self.answer(f"repos/{ENGINE}/releases/tags/v0.12.1", published)
        self.answer(f"repos/{ENGINE}/commits/v0.12.1", {"sha": PIN})

    def release(self, code=2, version="0.2.0", **files):
        """The next release's commit on main, with its tag: as a release pull request leaves it, unless told."""
        contents = {"version.properties": f"versionCode={code}\n", "CHANGELOG.md": changelog(version, "0.1.0")}
        contents.update(files)
        commit = self.repository.commit(contents)
        self.repository.main_at(commit)
        return commit

    def preflight(self, tag, commit, **extra):
        """The check as candidate.yml's preflight job runs it, with the job's token and [extra] in its environment."""
        access = push_access("candidate.yml", "preflight")
        return self.script("release_preflight.sh", tag, commit, cwd=self.repository.path, **access, **extra)

    def test_a_release_tag_on_main_with_its_code_raised_and_its_entry_passes(self):
        commit = self.release()
        self.repository.tag("v0.2.0")
        result = self.preflight("v0.2.0", commit)
        self.assertPassed(result)
        self.assertIn("versionName 0.2.0 with versionCode 2", result.stdout)

    def test_a_first_release_with_no_earlier_tag_passes(self):
        # No earlier release tag, and so no versionCode to be above: the very first tag the owner pushes.
        for annotated in (False, True):
            with self.subTest(annotated=annotated):
                repository = Repository(self.scratch / f"first-{annotated}")
                commit = repository.commit({
                    "version.properties": "versionCode=1\n",
                    "CHANGELOG.md": changelog("1.0.0"),
                    "contracts/SOURCE.json": source(),
                    SECURE_CHANNEL: session(),
                })
                repository.main_at(commit)
                repository.tag("v1.0.0", annotated=annotated)
                self.answer(f"repos/{REPO}/releases", [])
                access = push_access("candidate.yml", "preflight")
                result = self.script("release_preflight.sh", "v1.0.0", commit, cwd=repository.path, **access)
                self.assertPassed(result)
                self.assertIn("versionName 1.0.0 with versionCode 1", result.stdout)

    def test_a_tag_off_main_is_refused(self):
        main = self.release()
        self.repository.git("checkout", "--quiet", "-b", "side")
        side = self.repository.commit({"version.properties": "versionCode=3\n", "CHANGELOG.md": changelog("0.3.0")})
        self.repository.tag("v0.3.0")
        self.repository.main_at(main)
        self.assertRefused(self.preflight("v0.3.0", side), f"the tagged commit {side} is not on main")

    def test_a_versioncode_not_raised_is_refused(self):
        commit = self.release(code=1)
        self.repository.tag("v0.2.0")
        self.assertRefused(self.preflight("v0.2.0", commit), "versionCode 1 at v0.2.0 is not above v0.1.0's 1")

    def test_a_versioncode_not_raised_is_refused_whatever_the_machines_git_configuration(self):
        # column.ui = always, a developer's taste, prints git tag's list in columns, several tags to a line.
        config = self.scratch / "columns.gitconfig"
        config.write_text("[column]\n\tui = always\n")
        commit = self.release(code=1)
        self.repository.tag("v0.2.0")
        result = self.preflight("v0.2.0", commit, GIT_CONFIG_GLOBAL=config)
        self.assertRefused(result, "versionCode 1 at v0.2.0 is not above v0.1.0's 1")

    def test_a_commit_without_version_properties_is_refused(self):
        self.repository.git("rm", "--quiet", "version.properties")
        commit = self.repository.commit({"CHANGELOG.md": changelog("0.2.0", "0.1.0")})
        self.repository.main_at(commit)
        self.repository.tag("v0.2.0")
        self.assertRefused(self.preflight("v0.2.0", commit), f"version.properties at {commit} holds no versionCode")

    def test_an_earlier_release_tag_without_a_versioncode_stops_at_once(self):
        commit = self.release()
        self.repository.tag("v0.2.0")
        empty_tree = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
        self.repository.tag("v0.0.9", commit=self.repository.git("commit-tree", "-m", "before", empty_tree))
        result = self.preflight("v0.2.0", commit)
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("version.properties at v0.0.9 holds no versionCode to compare with", result.stderr)

    def test_a_tag_that_is_not_the_versionname_is_refused(self):
        # Two release tags on one commit: describe, as the build runs it, takes the annotated one.
        commit = self.release()
        self.repository.tag("v0.2.0")
        self.repository.tag("v0.2.1", annotated=True)
        self.assertRefused(
            self.preflight("v0.2.0", commit),
            f"the build names {commit} by its nearest release tag, v0.2.1, not v0.2.0",
        )

    def test_a_tag_that_names_another_commit_is_refused(self):
        tagged = self.release()
        self.repository.tag("v0.2.0")
        later = self.repository.commit({"README.md": "later\n"})
        self.repository.main_at(later)
        self.assertRefused(self.preflight("v0.2.0", later), f"the tag v0.2.0 names {tagged}, not {later}")

    def test_a_published_release_with_the_tag_is_refused(self):
        commit = self.release()
        self.repository.tag("v0.2.0")
        self.answer(f"repos/{REPO}/releases", [{"tag_name": "v0.2.0", "draft": False}])
        self.assertRefused(self.preflight("v0.2.0", commit), "the v0.2.0 release is published already")

    def test_a_staged_draft_with_the_tag_is_refused(self):
        commit = self.release()
        self.repository.tag("v0.2.0")
        self.answer(f"repos/{REPO}/releases", [{"tag_name": "v0.2.0", "draft": True}])
        self.assertRefused(self.preflight("v0.2.0", commit), "a draft release for v0.2.0 exists")

    def test_a_contract_from_an_unpublished_engine_release_is_refused(self):
        commit = self.release(**{"contracts/SOURCE.json": source(release="v0.13.0")})
        self.repository.tag("v0.2.0")
        self.assertRefused(self.preflight("v0.2.0", commit), "tezra-io/fermix has no published release v0.13.0")

    def test_a_contract_pinned_at_another_commit_than_the_release_is_refused(self):
        other = "0" * 40
        commit = self.release(**{"contracts/SOURCE.json": source(commit=other)})
        self.repository.tag("v0.2.0")
        self.assertRefused(
            self.preflight("v0.2.0", commit),
            f"tezra-io/fermix's v0.12.1 is {PIN}, but contracts/SOURCE.json pins the contract at {other}",
        )

    def test_a_contract_that_names_no_engine_release_is_refused(self):
        unnamed = json.dumps({"upstream": {"repository": ENGINE, "commit": PIN, "branch": "main"}})
        commit = self.release(**{"contracts/SOURCE.json": unnamed})
        self.repository.tag("v0.2.0")
        self.assertRefused(self.preflight("v0.2.0", commit), "contracts/SOURCE.json names no engine release")

    def test_an_app_protocol_the_pinned_engine_does_not_serve_is_refused(self):
        # The app speaks protocol 2 (design section 7); engine v0.12.1 serves 1 alone.
        commit = self.release(**{"contracts/SOURCE.json": source(window=(1, 1))})
        self.repository.tag("v0.2.0")
        self.assertRefused(
            self.preflight("v0.2.0", commit),
            "the app speaks mobile protocol 2, which tezra-io/fermix v0.12.1 does not serve (its window is 1 to 1)",
        )

    def test_an_app_that_names_no_protocol_is_refused(self):
        commit = self.release(**{SECURE_CHANNEL: "package io.tezra.fermix.session\n"})
        self.repository.tag("v0.2.0")
        self.assertRefused(self.preflight("v0.2.0", commit), "declares no SESSION_VERSION")

    def test_a_changelog_without_the_version_is_refused(self):
        commit = self.release(**{"CHANGELOG.md": changelog("0.1.0")})
        self.repository.tag("v0.2.0")
        self.assertRefused(self.preflight("v0.2.0", commit), 'CHANGELOG.md has no "## [0.2.0]" heading')

    def test_a_prerelease_tag_stops_at_once(self):
        commit = self.release()
        self.repository.tag("v0.2.0-rc.1")
        result = self.preflight("v0.2.0-rc.1", commit)
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("is not a release tag vMAJOR.MINOR.PATCH", result.stderr)


class ChangelogEntryTest(ScriptTest):
    def test_an_entry_is_printed_without_its_heading(self):
        result = self.script("changelog_entry.sh", "0.2.0", stdin=changelog("0.2.0", "0.1.0"))
        self.assertPassed(result)
        self.assertEqual("- What 0.2.0 changed.\n", result.stdout)

    def test_help_prints_the_usage(self):
        result = self.script("changelog_entry.sh", "--help", stdin="")
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("usage: changelog_entry.sh <X.Y.Z> < CHANGELOG.md", result.stderr)


if __name__ == "__main__":
    unittest.main()
