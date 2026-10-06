"""app_shots.sh: docs/app-shots holds every preview's compact window at font scale 1.0, light and dark, copied byte for
byte from what a record drew in each module's src/test/screenshots, and a README.md that shows each light image beside
its dark one; with nothing recorded it stops, names the command that records, and leaves docs/app-shots as it was."""
import unittest

from support import ScriptTest, run, script_tree

# Roborazzi's names for a preview's twelve windows (@FermixPreviews), after its class and function.
WINDOWS = [
    f"{size}_{mode}_{scale}_{qualifiers}"
    for size, dimensions in (
        ("compact", "WIDTH_412DP_HEIGHT_915DP_DPI_320"),
        ("medium", "WIDTH_673DP_HEIGHT_841DP_DPI_320"),
        ("expanded", "WIDTH_841DP_HEIGHT_673DP_DPI_320"),
    )
    for mode, night in (("light", "DAY"), ("dark", "NIGHT"))
    for scale, qualifiers in (("1.0", f"{night}_{dimensions}"), ("2.0", f"FONT_2_0f_{night}_{dimensions}"))
]
SPECIMEN = "io.tezra.fermix.design.SpecimenKt"
CHAT = "io.tezra.fermix.chat.ChatPreviewsKt"


class AppShotsTest(ScriptTest):
    def setUp(self):
        super().setUp()
        self.tree = script_tree(self.scratch, "app_shots.sh")
        self.docs = self.tree / "docs" / "app-shots"

    def references(self, module, preview, windows=WINDOWS):
        """[preview]'s references in [module], each image's bytes naming its preview and window."""
        directory = self.tree / module / "src" / "test" / "screenshots"
        directory.mkdir(parents=True, exist_ok=True)
        for window in windows:
            (directory / f"{preview}.{window}.png").write_bytes(f"{preview} {window}".encode())
        return directory

    def shots(self, *arguments):
        return run([self.tree / "scripts" / "app_shots.sh", *arguments], env=self.environment())

    def two_modules(self):
        self.references("design", f"{SPECIMEN}.SpecimenType")
        self.references("design", f"{SPECIMEN}.SpecimenColour")
        self.references("feature-chat", f"{CHAT}.ThreadPreview")

    def test_each_previews_compact_light_and_dark_are_copied_byte_for_byte(self):
        self.two_modules()
        self.assertPassed(self.shots())
        light = self.docs / "design" / "SpecimenColour-light.png"
        dark = self.docs / "design" / "SpecimenColour-dark.png"
        references = self.tree / "design" / "src" / "test" / "screenshots"
        self.assertEqual((references / f"{SPECIMEN}.SpecimenColour.{WINDOWS[0]}.png").read_bytes(), light.read_bytes())
        self.assertEqual((references / f"{SPECIMEN}.SpecimenColour.{WINDOWS[2]}.png").read_bytes(), dark.read_bytes())
        written = sorted(str(path.relative_to(self.docs)) for path in self.docs.rglob("*") if path.is_file())
        self.assertEqual(
            [
                "README.md",
                "design/SpecimenColour-dark.png",
                "design/SpecimenColour-light.png",
                "design/SpecimenType-dark.png",
                "design/SpecimenType-light.png",
                "feature-chat/ThreadPreview-dark.png",
                "feature-chat/ThreadPreview-light.png",
            ],
            written,
        )

    def test_the_readme_has_a_section_a_module_and_each_light_image_beside_its_dark(self):
        self.two_modules()
        self.assertPassed(self.shots())
        readme = (self.docs / "README.md").read_text()
        self.assertLess(readme.index("\n## design\n"), readme.index("\n## feature-chat\n"))
        self.assertLess(readme.index("| SpecimenColour |"), readme.index("| SpecimenType |"))
        row = next(line for line in readme.splitlines() if line.startswith("| ThreadPreview |"))
        self.assertEqual(
            '| ThreadPreview | <img src="feature-chat/ThreadPreview-light.png" alt="ThreadPreview, light" width="280"> '
            '| <img src="feature-chat/ThreadPreview-dark.png" alt="ThreadPreview, dark" width="280"> |',
            row,
        )
        # Where the images come from, now that git keeps none: a record on the machine, or CI's artifact.
        self.assertIn("`./gradlew recordRoborazziDebug`", readme)
        self.assertIn("`app-shots` artifact", readme)
        self.assertNotIn("--check", readme)

    def test_a_write_keeps_nothing_of_the_last_but_what_is_recorded_now(self):
        self.two_modules()
        self.assertPassed(self.shots())
        for reference in (self.tree / "design/src/test/screenshots").glob(f"{SPECIMEN}.SpecimenType.*"):
            reference.unlink()
        (self.docs / "notes.txt").write_text("not the script's")
        self.assertPassed(self.shots())
        self.assertFalse((self.docs / "design" / "SpecimenType-light.png").exists())
        self.assertFalse((self.docs / "notes.txt").exists())
        self.assertNotIn("SpecimenType", (self.docs / "README.md").read_text())
        self.assertTrue((self.docs / "design" / "SpecimenColour-dark.png").exists())

    def test_nothing_recorded_stops_it_naming_the_command_that_records(self):
        (self.tree / "design" / "src" / "test" / "screenshots").mkdir(parents=True)
        result = self.shots()
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn("no module has a recorded screenshot; run ./gradlew recordRoborazziDebug first", result.stderr)
        self.assertFalse(self.docs.exists())

    def test_nothing_recorded_leaves_the_last_write_as_it_was(self):
        self.two_modules()
        self.assertPassed(self.shots())
        before = {path: path.read_bytes() for path in self.docs.rglob("*") if path.is_file()}
        for module in ("design", "feature-chat"):
            for reference in (self.tree / module / "src" / "test" / "screenshots").iterdir():
                reference.unlink()
        result = self.shots()
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn("run ./gradlew recordRoborazziDebug first", result.stderr)
        self.assertEqual(before, {path: path.read_bytes() for path in self.docs.rglob("*") if path.is_file()})

    def test_a_preview_without_its_compact_dark_image_stops_it(self):
        self.references("design", f"{SPECIMEN}.SpecimenType", windows=[w for w in WINDOWS if w != WINDOWS[2]])
        result = self.shots()
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn(f"holds 0 compact dark images at font scale 1.0 of {SPECIMEN}.SpecimenType, not 1", result.stderr)
        self.assertFalse(self.docs.exists())

    def test_a_screenshot_it_cannot_read_stops_it(self):
        self.two_modules()
        light = self.tree / "design" / "src" / "test" / "screenshots" / f"{SPECIMEN}.SpecimenType.{WINDOWS[0]}.png"
        light.unlink()
        light.symlink_to(self.scratch / "no-such-image.png")
        result = self.shots()
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn(f"app_shots: could not copy {light}", result.stderr)
        self.assertFalse(self.docs.exists())

    def test_two_previews_of_one_name_in_a_module_stop_it(self):
        self.references("feature-chat", f"{CHAT}.ThreadPreview")
        self.references("feature-chat", "io.tezra.fermix.chat.ControlsPreviewsKt.ThreadPreview")
        result = self.shots()
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn("feature-chat has two previews named ThreadPreview", result.stderr)

    def test_an_argument_prints_the_usage(self):
        self.two_modules()
        for argument in ("--write", "--check"):
            result = self.shots(argument)
            self.assertEqual(2, result.returncode, result.stderr)
            self.assertIn("usage: app_shots.sh", result.stderr)
            self.assertNotIn("[--check]", result.stderr)
        self.assertFalse(self.docs.exists())


if __name__ == "__main__":
    unittest.main()
