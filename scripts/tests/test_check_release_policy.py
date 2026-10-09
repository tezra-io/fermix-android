"""check_release_policy.sh's check 10: nothing of the debug app's demo (README, "The demo") is in a release, no class
of io.tezra.fermix.demo in any dex, no name of it in the merged manifest and no resource named as the demo's are;
and, given R8's mapping of the release, the mapping is the APK's own and maps no class of the package, whatever name
R8 gave it.

Each APK is made for its test with the SDK's own tools: a manifest linked by aapt2, and a dex that d8 makes of one
class javac compiles, or that R8 makes in release mode, with its mapping, as the release build does. An APK this
small fails the other nine checks, whose proof is the policy job's on the real release (test_verify_candidate.py says
why), so these tests read check 10's own lines alone, and each refusal they expect, the clean APK's included, is
planted beside a near miss: a package whose name starts as the demo's does.
"""
import pathlib
import unittest

from support import PACKAGE, ROOT, ScriptTest, build_tool, checked, java_tool, platform_jar

DEMO = "io.tezra.fermix.demo"
# A package that starts as the demo's and is not it: check 10 must pass it by.
NEAR_MISS = "io.tezra.fermix.demonstration"
# A string that starts as the demo's resources are named and is not one of them.
NEAR_MISS_STRING = "demonstration"


class ReleaseDemoCheckTest(ScriptTest):
    def apk(self, name, classes=(), components=(), application=None, dex=True, strings=()):
        """An APK of [PACKAGE] whose application is named [application] when given, whose manifest declares each
        activity of [components], not exported, whose resources hold a string of each name of [strings], and whose
        classes.dex defines each class of [classes] (none when not [dex])."""
        work = self.scratch / name
        work.mkdir()
        named = f' android:name="{application}"' if application else ""
        activities = "".join(f'<activity android:name="{it}" android:exported="false"/>' for it in components)
        (work / "manifest.xml").write_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            f'package="{PACKAGE}"><application{named}>{activities}</application></manifest>\n'
        )
        apk = self.scratch / f"{name}.apk"
        checked([
            build_tool("aapt2"), "link", "-I", platform_jar(), "-o", apk, "--manifest", work / "manifest.xml",
            "--min-sdk-version", "35", "--target-sdk-version", "36", *self.resources(work, strings),
        ])
        if dex:
            checked(["zip", "-q", apk, "classes.dex"], cwd=self.dex(work, classes))
        return apk

    def resources(self, work, strings):
        """aapt2's compiled resources of a strings.xml holding each name of [strings], as link takes them; none
        when there are none."""
        if not strings:
            return []
        values = work / "res" / "values"
        values.mkdir(parents=True)
        entries = "".join(f'<string name="{it}">words</string>' for it in strings)
        (values / "strings.xml").write_text(f"<resources>{entries}</resources>\n")
        compiled = work / "res.zip"
        checked([build_tool("aapt2"), "compile", "--dir", work / "res", "-o", compiled])
        return [compiled]

    def dex(self, work, classes):
        """A directory holding classes.dex, d8's of [classes], each an empty public class, and one of the app's."""
        sources = work / "src"
        names = [f"{PACKAGE}.Shipped", *classes]
        for name in names:
            package, simple = name.rsplit(".", 1)
            source = sources / pathlib.Path(*package.split(".")) / f"{simple}.java"
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text(f"package {package};\npublic class {simple} {{}}\n")
        compiled = work / "classes"
        checked([java_tool("javac"), "--release", "17", "-d", compiled, *sorted(sources.rglob("*.java"))])
        out = work / "dex"
        out.mkdir()
        checked([
            build_tool("d8"), "--lib", platform_jar(), "--min-api", "35", "--output", out,
            *sorted(compiled.rglob("*.class")),
        ])
        return out

    def shrunk_apk(self, name, classes, rules=()):
        """An APK of [PACKAGE] whose classes.dex R8 made in release mode, as the release build makes it, and R8's
        mapping of it: the app's class Shipped, kept, holds an instance of each class of [classes], and [rules] are R8's
        beside that keep; R8 renames any other class that no rule keeps."""
        apk = self.apk(name, dex=False)
        work = self.scratch / name
        sources = work / "src"
        held = ", ".join(f"new {it}()" for it in classes)
        self.java(sources, f"{PACKAGE}.Shipped", f"public static Object[] held = {{ {held} }};")
        for it in classes:
            self.java(sources, it, f'public String toString() {{ return "{it}"; }}')
        compiled = work / "classes"
        checked([java_tool("javac"), "--release", "17", "-d", compiled, *sorted(sources.rglob("*.java"))])
        (work / "rules.pro").write_text("\n".join([f"-keep class {PACKAGE}.Shipped {{ *; }}", *rules]) + "\n")
        out, mapping = work / "dex", work / "mapping.txt"
        out.mkdir()
        checked([
            java_tool("java"), "-cp", build_tool("lib") / "d8.jar", "com.android.tools.r8.R8", "--release",
            "--min-api", "35", "--lib", platform_jar(), "--output", out, "--pg-map-output", mapping,
            "--pg-conf", work / "rules.pro", *sorted(compiled.rglob("*.class")),
        ])
        checked(["zip", "-q", apk, "classes.dex"], cwd=out)
        return apk, mapping

    @staticmethod
    def java(sources, name, body):
        """The source of the public class [name] under [sources], with [body] as its members."""
        package, simple = name.rsplit(".", 1)
        source = sources / pathlib.Path(*package.split(".")) / f"{simple}.java"
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(f"package {package};\npublic class {simple} {{ {body} }}\n")

    def check(self, apk, mapping=None):
        return self.script("check_release_policy.sh", apk, *([mapping] if mapping else []))

    def demo_lines(self, result):
        """What check 10 said: its violations and the names under them, which every other check's lines end."""
        lines = (result.stdout + result.stderr).splitlines()
        said, inside = [], False
        for line in lines:
            if line.startswith("check_release_policy: "):
                inside = line.startswith("check_release_policy: demo: ")
            if inside:
                said.append(line)
        return said

    def test_a_release_with_a_class_of_the_demo_is_refused_by_name(self):
        apk = self.apk("classes", classes=[f"{DEMO}.DemoDaemon", f"{DEMO}.wire.Frames", f"{NEAR_MISS}.Kept"])

        result = self.check(apk)
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertEqual(
            [
                f"check_release_policy: demo: expected no class of {DEMO} in any dex, found:",
                "Lio/tezra/fermix/demo/DemoDaemon;",
                "Lio/tezra/fermix/demo/wire/Frames;",
            ],
            self.demo_lines(result),
        )

    def test_a_release_whose_manifest_names_the_demo_is_refused(self):
        apk = self.apk(
            "manifest", components=[f"{DEMO}.DemoEntry", f"{NEAR_MISS}.Entry"], application=f"{DEMO}.DemoApplication",
        )

        result = self.check(apk)
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertEqual(
            [
                f"check_release_policy: demo: expected no name of {DEMO} in the merged manifest, found:",
                f"{DEMO}.DemoApplication",
                f"{DEMO}.DemoEntry",
            ],
            self.demo_lines(result),
        )

    def test_a_release_with_a_resource_named_as_the_demos_is_refused(self):
        apk = self.apk("strings", strings=["demo_launcher_label", "demo_clip_label", NEAR_MISS_STRING])

        result = self.check(apk)
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertEqual(
            [
                "check_release_policy: demo: expected no resource named as the demo's, demo_, found:",
                "string/demo_clip_label",
                "string/demo_launcher_label",
            ],
            self.demo_lines(result),
        )

    def test_a_release_with_none_of_the_demo_says_nothing_of_it(self):
        apk = self.apk(
            "clean", classes=[f"{NEAR_MISS}.Kept"], components=[f"{NEAR_MISS}.Entry"], strings=[NEAR_MISS_STRING],
        )

        result = self.check(apk)
        # The other nine checks refuse an APK this small; check 10 alone is read.
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertEqual([], self.demo_lines(result))

    def test_a_release_whose_mapping_maps_a_class_of_the_demo_under_another_name_is_refused(self):
        # R8 renames a class no rule keeps, so the dex holds no name of the demo: the mapping alone says what it was.
        apk, mapping = self.shrunk_apk(
            "renamed", [f"{DEMO}.Leak", f"{NEAR_MISS}.Kept"], rules=[f"-keep class {NEAR_MISS}.Kept"],
        )
        self.assertIn(f"{NEAR_MISS}.Kept -> {NEAR_MISS}.Kept:", mapping.read_text().splitlines())

        said = self.demo_lines(self.check(apk, mapping))
        self.assertEqual(
            f"check_release_policy: demo: expected R8's mapping to map no class of {DEMO}, found:", said[0], said,
        )
        self.assertEqual(2, len(said), said)
        self.assertRegex(said[1], rf"^{DEMO}\.Leak -> (?!{DEMO}\.)[^ ]+:$")
        self.assertEqual([], self.demo_lines(self.check(apk)), "the dex alone shows nothing of a renamed class")

    def test_the_apps_own_r8_rules_keep_a_class_of_the_demo_by_its_name_so_the_dex_shows_it_too(self):
        # app/proguard-rules.pro as the release build reads it: without it the dex alone would see nothing above.
        rules = (ROOT / "app" / "proguard-rules.pro").read_text().splitlines()
        apk, mapping = self.shrunk_apk("kept", [f"{DEMO}.Leak"], rules)

        self.assertEqual(
            [
                f"check_release_policy: demo: expected no class of {DEMO} in any dex, found:",
                "Lio/tezra/fermix/demo/Leak;",
                f"check_release_policy: demo: expected R8's mapping to map no class of {DEMO}, found:",
                f"{DEMO}.Leak -> {DEMO}.Leak:",
            ],
            self.demo_lines(self.check(apk, mapping)),
        )

    def test_a_release_whose_mapping_maps_none_of_the_demo_says_nothing_of_it(self):
        apk, mapping = self.shrunk_apk("clean-r8", [f"{NEAR_MISS}.Kept"], rules=[f"-keep class {NEAR_MISS}.Kept"])

        result = self.check(apk, mapping)
        # The other nine checks refuse an APK this small; check 10 alone is read.
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertEqual([], self.demo_lines(result))

    def test_a_mapping_that_is_not_the_apks_is_refused(self):
        apk, _ = self.shrunk_apk("one", [f"{NEAR_MISS}.Kept"])
        _, other = self.shrunk_apk("two", [f"{NEAR_MISS}.Kept", f"{NEAR_MISS}.Other"])

        result = self.check(apk, other)
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn(f"{other} is not the mapping of the APK's dex", result.stderr)

    def test_a_file_that_is_no_r8_mapping_is_refused(self):
        apk = self.apk("plain", classes=[f"{NEAR_MISS}.Kept"])
        mapping = self.scratch / "mapping.txt"
        mapping.write_text(f"{DEMO}.Leak -> a:\n")

        result = self.check(apk, mapping)
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn(f"{mapping} names no pg_map_id: it is not R8's mapping", result.stderr)

    def test_an_apk_with_no_dex_to_search_is_refused(self):
        apk = self.apk("nodex", dex=False)

        result = self.check(apk)
        self.assertEqual(
            [f"check_release_policy: demo: expected the app's code in classes*.dex to search for {DEMO}, found no dex"],
            self.demo_lines(result),
        )


if __name__ == "__main__":
    unittest.main()
