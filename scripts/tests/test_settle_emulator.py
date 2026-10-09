"""settle_emulator.sh: once the home screen has the focus, it puts Android's hide_error_dialogs, so that no "isn't
responding" or "has stopped" dialog takes the focus from a test (CI run 37919058881: the launcher's held it through
eighteen of onboarding's tests), says so, and fails naming the command when the setting cannot be put; an emulator
that never settles is left without it, and as the device keeps the setting, a serial that names no emulator is refused
before adb is asked anything."""
import json
import unittest

from support import EmulatorTest

LAUNCHER = "com.google.android.apps.nexuslauncher/.NexusLauncherActivity"
LAUNCHER_WINDOW = (
    "Window{1c9e4a0 u0 com.google.android.apps.nexuslauncher/"
    "com.google.android.apps.nexuslauncher.NexusLauncherActivity}"
)
# The dialog's window as CI's run showed it, the one token in all six failures.
DIALOG_WINDOW = "Window{e9164cf u0 Application Not Responding: com.google.android.apps.nexuslauncher}"
HIDE = ["shell", "settings", "put", "global", "hide_error_dialogs", "1"]


class SettleEmulatorTest(EmulatorTest):
    def settle(self, windows, **extra):
        """The script on an emulator whose focus is each of [windows] in turn, one a poll."""
        (self.device / "focus.json").write_text(json.dumps(windows))
        return self.emulator_script("settle_emulator.sh", FAKE_HOME=LAUNCHER, **extra)

    def test_the_error_dialogs_are_hidden_once_after_the_launcher_has_the_focus(self):
        result = self.settle([DIALOG_WINDOW, DIALOG_WINDOW, LAUNCHER_WINDOW])
        self.assertPassed(result)
        calls = self.adb_calls()
        hides = [index for index, call in enumerate(calls) if call["arguments"] == HIDE]
        self.assertEqual(1, len(hides), calls)
        settled = next(index for index, call in enumerate(calls) if call["focus"] == 2)
        self.assertGreater(hides[0], settled, calls)
        self.assertEqual(HIDE, calls[-1]["arguments"])
        self.assertEqual(
            f"settled after poll 3: mCurrentFocus={LAUNCHER_WINDOW}; the system's error dialogs are hidden"
            " (settings put global hide_error_dialogs 1)\n",
            result.stdout,
        )

    def test_an_emulator_that_never_settles_is_left_without_the_setting(self):
        result = self.settle([DIALOG_WINDOW])
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn(f"the home screen never had the focus in 60 s; the home: {LAUNCHER}", result.stderr)
        calls = self.adb_calls()
        self.assertEqual(30, len([call for call in calls if call["focus"] is not None]))
        self.assertNotIn(HIDE, [call["arguments"] for call in calls])
        self.assertEqual(["2"] * 30, self.sleeps())
        self.assertEqual("", result.stdout)

    def test_a_setting_that_cannot_be_put_fails_naming_the_command(self):
        result = self.settle([LAUNCHER_WINDOW], FAKE_SETTINGS="fail")
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn(
            "settle_emulator: could not hide the system's error dialogs:"
            " adb shell settings put global hide_error_dialogs 1 failed",
            result.stderr,
        )
        self.assertEqual("", result.stdout)

    def test_a_serial_that_names_no_emulator_is_refused_before_adb_is_asked_anything(self):
        # The setting stays on the device: a phone on USB, or a device adb picked alone, is never given it.
        for serial in ("", "R5CT10ABCDE", "192.168.1.20:5555", "emulator-", "emulator-55a4"):
            with self.subTest(serial=serial):
                result = self.settle([LAUNCHER_WINDOW], ANDROID_SERIAL=serial)
                self.assertEqual(2, result.returncode, result.stdout + result.stderr)
                self.assertIn(f"settle_emulator: ANDROID_SERIAL names no emulator: '{serial}'", result.stderr)
                self.assertEqual("", result.stdout)
        self.assertEqual([], self.adb_calls())


if __name__ == "__main__":
    unittest.main()
