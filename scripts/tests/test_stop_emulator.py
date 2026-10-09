"""stop_emulator.sh: the emulator ANDROID_SERIAL names is asked to exit, waited for 30 polls 1 s apart, and ended with
SIGKILL if it is still there, so the emulator runner's own kill finds none and its step ends (CI run 37956921652:
an emulator that answered the kill and never exited held a green step until the job's timeout). It finds the process
as the listener of the serial's console port, never by a process's name, and stops nothing that is not an emulator's
qemu; an emulator already gone, or a kill that fails on it, is no failure, and neither is one that exits as its
SIGKILL is sent, while ps failing on one still there is. And both emulator steps of ci.yml settle the emulator first
and stop it on every way out of their script, and a settle that could not hide the error dialogs is told apart from an
emulator that never settled: it is not run again, and the job's summary names it."""
import itertools
import json
import pathlib
import re
import shutil
import signal
import subprocess
import tempfile
import threading
import unittest

from support import ROOT, SERIAL, EmulatorTest, ScriptTest, run

PORT = "5554"
KILL = ["-s", SERIAL, "emu", "kill"]
# The emulator runner, as ci.yml pins it.
RUNNER = "reactivecircus/android-emulator-runner@"
SCRIPT_HEADER = re.compile(r"^ +script: \|$")
RUN_HEADER = re.compile(r"^ +run: \|$")
WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
# What every line of an emulator step's script but its first and its last ends with: the action runs the script a line
# at a time and ends it at the first line that fails, so that line stops the emulator before it fails the step.
STOP_ON_FAILURE = " || { scripts/stop_emulator.sh; exit 1; }"
# The first line, which also marks a settle whose tool failed (exit 2: on CI, the setting that hides the error
# dialogs) apart from one that never settled (exit 1).
SETTLE_FIRST = (
    'scripts/settle_emulator.sh || { [ $? -ne 2 ] || touch "$RUNNER_TEMP/settle-tool-failed";'
    " scripts/stop_emulator.sh; exit 1; }"
)
# A stand-in emulator lives at most this long, and is ended by the test's cleanup in any case.
STAND_IN_SECONDS = "600"


class StopEmulatorTest(EmulatorTest):
    def emulator(self, name="qemu-system-x86_64"):
        """A process standing in for the emulator: the real sleep, run as [name], since the emulator's process is its
        qemu, which the kernel names qemu-system-x86 (fifteen characters)."""
        binary = self.scratch / name
        shutil.copy2(shutil.which("sleep"), binary)
        process = subprocess.Popen([binary, STAND_IN_SECONDS])
        self.addCleanup(self.end, process)
        return process

    @staticmethod
    def end(process):
        if process.poll() is None:
            process.kill()
        process.wait(timeout=10)

    def stop(self, emu_kill, listeners=(), exits=None, **extra):
        """The script with [listeners] on the console's port and adb's emu kill answering as [emu_kill] says (the fake
        adb), ending the process [exits] when it is to exit."""
        return self.emulator_script(
            "stop_emulator.sh",
            FAKE_EMU_KILL=emu_kill,
            FAKE_CONSOLE_PORT=PORT,
            FAKE_LISTENERS=" ".join(str(process.pid) for process in listeners),
            FAKE_EMULATOR_PID=exits.pid if exits else "",
            **extra,
        )

    def ps_answers(self, states):
        """ps answers each of [states] in turn, one a call, and then the real ps (the fake ps)."""
        (self.device / "ps.json").write_text(json.dumps(states))

    def test_an_emulator_that_exits_is_waited_for_and_not_killed(self):
        emulator = self.emulator()
        result = self.stop("exit", [emulator], exits=emulator)
        self.assertPassed(result)
        self.assertEqual(-signal.SIGTERM, emulator.wait(timeout=10))
        self.assertRegex(
            result.stdout,
            "^OK: killing emulator, bye bye\n"
            rf"stop_emulator: {SERIAL} \(pid {emulator.pid}\) exited [0-9]+ s after its kill\n$",
        )
        self.assertNotIn("SIGKILL", result.stdout)
        self.assertLess(len(self.sleeps()), 30)
        self.assertEqual([KILL], [call["arguments"] for call in self.adb_calls()])
        self.assertEqual([["-Hltnp", f"sport = :{PORT}"]], self.ss_calls())

    def test_an_emulator_that_stays_is_killed_after_30_s_and_the_script_passes(self):
        emulator = self.emulator()
        result = self.stop("stay", [emulator])
        self.assertPassed(result)
        self.assertEqual(-signal.SIGKILL, emulator.wait(timeout=10))
        self.assertEqual(
            "OK: killing emulator, bye bye\n"
            f"stop_emulator: {SERIAL} (pid {emulator.pid}) still ran 30 s after its kill: ended it with SIGKILL\n",
            result.stdout,
        )
        # 30 polls a second apart before the SIGKILL, and at most one more for the process to be gone after it.
        sleeps = self.sleeps()
        self.assertEqual(["1"] * 30, sleeps[:30])
        self.assertLessEqual(len(sleeps), 31)

    def test_a_kill_with_no_answer_is_given_up_and_the_emulator_killed(self):
        emulator = self.emulator()
        result = self.stop("hang", [emulator])
        self.assertPassed(result)
        self.assertEqual(-signal.SIGKILL, emulator.wait(timeout=10))
        self.assertIn(f"stop_emulator: adb -s {SERIAL} emu kill gave no answer in 10 s", result.stderr)
        self.assertIn("ended it with SIGKILL", result.stdout)

    def test_a_kill_that_fails_on_an_emulator_going_away_is_no_failure(self):
        emulator = self.emulator()
        result = self.stop("gone", [emulator], exits=emulator)
        self.assertPassed(result)
        self.assertEqual(-signal.SIGTERM, emulator.wait(timeout=10))
        self.assertIn(f"stop_emulator: adb -s {SERIAL} emu kill failed (exit 1)", result.stderr)
        self.assertIn(f"(pid {emulator.pid}) exited", result.stdout)
        self.assertNotIn("SIGKILL", result.stdout)

    def test_a_zombie_whose_threads_are_still_ending_is_waited_for(self):
        emulator = self.emulator()
        self.ps_answers(["Zl", "Zl", "Zl", "Z"])
        result = self.stop("stay", [emulator])
        self.assertPassed(result)
        self.assertEqual(
            "OK: killing emulator, bye bye\n"
            f"stop_emulator: {SERIAL} (pid {emulator.pid}) exited 3 s after its kill\n",
            result.stdout,
        )
        self.assertEqual(["1"] * 3, self.sleeps())
        self.assertIsNone(emulator.poll())

    def test_an_emulator_that_outlives_its_sigkill_fails_the_script(self):
        emulator = self.emulator()
        # Every poll, before the SIGKILL and after it, and the last words' state.
        self.ps_answers(["Zl"] * 60)
        result = self.stop("stay", [emulator])
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertEqual(-signal.SIGKILL, emulator.wait(timeout=10))
        self.assertIn("ended it with SIGKILL", result.stdout)
        self.assertIn(
            f"stop_emulator: {SERIAL} (pid {emulator.pid}) still runs 10 s after SIGKILL, in state Zl\n", result.stderr
        )
        self.assertEqual(["1"] * 40, self.sleeps())

    def test_a_ps_that_cannot_answer_for_an_emulator_still_there_fails_the_script(self):
        emulator = self.emulator()
        self.ps_answers(["fail"])
        result = self.stop("stay", [emulator])
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn(
            f"stop_emulator: ps could not read the state of pid {emulator.pid}, which still runs", result.stderr
        )
        self.assertNotIn("exited", result.stdout)
        self.assertIsNone(emulator.poll())

    def test_an_emulator_that_exits_as_its_sigkill_is_sent_is_gone_not_a_failure(self):
        emulator = self.emulator()
        # Reaped the moment it exits, so that the SIGKILL finds no process.
        reaper = threading.Thread(target=emulator.wait, daemon=True)
        reaper.start()
        self.addCleanup(reaper.join, 10)
        # Running at each of the 31 polls of the wait, so that the script sends its SIGKILL to an emulator gone since.
        self.ps_answers(["S"] * 31)
        result = self.stop("exit", [emulator], exits=emulator)
        self.assertPassed(result)
        self.assertEqual(-signal.SIGTERM, emulator.wait(timeout=10))
        self.assertEqual(
            "OK: killing emulator, bye bye\n"
            f"stop_emulator: {SERIAL} (pid {emulator.pid}) exited as it was to be ended with SIGKILL,"
            " 30 s after its kill\n",
            result.stdout,
        )
        self.assertEqual(["1"] * 30, self.sleeps())

    def test_no_emulator_on_the_port_is_nothing_to_stop(self):
        result = self.stop("gone")
        self.assertPassed(result)
        self.assertEqual(f"stop_emulator: nothing listens on {SERIAL}'s console port {PORT}: no emulator to stop\n",
                         result.stdout)
        self.assertEqual([], self.adb_calls())

    def test_a_listener_that_is_no_emulator_is_left_running(self):
        other = self.emulator(name="sleep")
        result = self.stop("stay", [other])
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn(
            f"stop_emulator: the listener of port {PORT}, pid {other.pid}, is sleep, not an emulator's qemu",
            result.stderr,
        )
        self.assertIsNone(other.poll())
        self.assertEqual([], self.adb_calls())

    def test_two_listeners_on_the_port_stop_it(self):
        first, second = self.emulator(), self.emulator(name="qemu-system-x86_64-headless")
        result = self.stop("stay", [first, second])
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn(f"port {PORT} has more than one listener", result.stderr)
        self.assertIsNone(first.poll())
        self.assertIsNone(second.poll())

    def test_a_serial_that_names_no_emulator_stops_it(self):
        for serial in ("", "R5CT10ABCDE", "emulator-", "emulator-55a4"):
            with self.subTest(serial=serial):
                result = self.stop("stay", ANDROID_SERIAL=serial)
                self.assertEqual(2, result.returncode, result.stdout + result.stderr)
                self.assertIn("stop_emulator: ANDROID_SERIAL names no emulator", result.stderr)
        self.assertEqual([], self.ss_calls())


def indent_of(line):
    return len(line) - len(line.lstrip(" "))


def block_after(lines, start, header):
    """The lines of the first block scalar after line [start] whose key line [header] matches."""
    at = next(index for index in range(start, len(lines)) if header.match(lines[index]))
    return list(
        itertools.takewhile(lambda text: not text.strip() or indent_of(text) > indent_of(lines[at]), lines[at + 1:])
    )


def script_after(lines, start):
    """The first script: block after line [start], as the action reads it: a command a line, each line trimmed, with
    no blank line or comment."""
    body = block_after(lines, start, SCRIPT_HEADER)
    return [text.strip() for text in body if text.strip() and not text.strip().startswith("#")]


def emulator_scripts(workflow):
    """The script: of each step of [workflow] that runs the emulator runner."""
    lines = workflow.read_text().split("\n")
    return [script_after(lines, index) for index, line in enumerate(lines) if RUNNER in line]


def run_of(workflow, step):
    """The run: of the step named [step] in [workflow], as the runner hands it to bash: the block's indent taken off."""
    lines = workflow.read_text().split("\n")
    start = next(index for index, line in enumerate(lines) if re.fullmatch(rf" *- name: {re.escape(step)}", line))
    body = block_after(lines, start, RUN_HEADER)
    margin = min(indent_of(text) for text in body if text.strip())
    return "\n".join(text[margin:] for text in body) + "\n"


class EmulatorStepsTest(ScriptTest):
    def test_both_emulator_steps_settle_first_and_stop_the_emulator_on_every_way_out(self):
        scripts = emulator_scripts(WORKFLOW)
        self.assertEqual(2, len(scripts))
        for script in scripts:
            self.assertEqual(SETTLE_FIRST, script[0])
            self.assertEqual("scripts/stop_emulator.sh", script[-1])
            for line in script[1:-1]:
                self.assertTrue(line.endswith(STOP_ON_FAILURE), line)

    def first_line(self, line, settle_exit):
        """[line] run as the action runs each line, sh -c, in a tree whose settle exits [settle_exit] and whose stop
        records that it ran: its result, and what it left in a RUNNER_TEMP of its own."""
        temp = pathlib.Path(tempfile.mkdtemp(prefix="runner-temp-", dir=self.scratch))
        scripts = self.scratch / "scripts"
        scripts.mkdir(exist_ok=True)
        (scripts / "settle_emulator.sh").write_text(f"#!/bin/sh\nexit {settle_exit}\n")
        (scripts / "stop_emulator.sh").write_text('#!/bin/sh\necho stopped >> "$RUNNER_TEMP/stopped"\n')
        for script in scripts.iterdir():
            script.chmod(0o755)
        result = run(["sh", "-c", line], cwd=self.scratch, env=self.environment(RUNNER_TEMP=temp))
        return result, sorted(path.name for path in temp.iterdir())

    def test_a_settle_whose_tool_failed_is_marked_apart_and_both_stop_the_emulator(self):
        # The settle's exit, the line's, and what the line left in RUNNER_TEMP.
        cases = ((0, 0, []), (1, 1, ["stopped"]), (2, 1, ["settle-tool-failed", "stopped"]))
        for script in emulator_scripts(WORKFLOW):
            for settle_exit, status, left in cases:
                with self.subTest(settle_exit=settle_exit):
                    result, found = self.first_line(script[0], settle_exit)
                    self.assertEqual(status, result.returncode, result.stdout + result.stderr)
                    self.assertEqual(left, found)

    def stage(self, *marks):
        """The stage the step "How far the emulator got" names, with [marks] in a RUNNER_TEMP of its own."""
        temp = pathlib.Path(tempfile.mkdtemp(prefix="runner-temp-", dir=self.scratch))
        for mark in marks:
            (temp / mark).touch()
        output = self.scratch / "github-output"
        output.write_text("")
        result = run(
            ["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c", run_of(WORKFLOW, "How far the emulator got")],
            cwd=self.scratch,
            env=self.environment(RUNNER_TEMP=temp, GITHUB_OUTPUT=output),
        )
        self.assertPassed(result)
        return output.read_text()

    def test_a_settle_whose_tool_failed_is_a_stage_of_its_own_and_not_run_again(self):
        self.assertEqual("stage=settle-tool-failed\n", self.stage("avd-made", "settle-tool-failed"))
        self.assertEqual("stage=booted\n", self.stage("avd-made", "emulator-booted"))
        self.assertEqual("stage=no-boot\n", self.stage("avd-made"))
        self.assertEqual("stage=no-avd\n", self.stage())

    def summary(self, stage, second, *marks):
        """The step "Summary and result" after a first run that failed at [stage] and a second run whose outcome is
        [second], with [marks] in a RUNNER_TEMP of its own: its result, and what it wrote to the job's summary."""
        temp = pathlib.Path(tempfile.mkdtemp(prefix="runner-temp-", dir=self.scratch))
        for mark in marks:
            (temp / mark).touch()
        summary = self.scratch / "step-summary"
        summary.write_text("")
        result = run(
            ["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c", run_of(WORKFLOW, "Summary and result")],
            cwd=self.scratch,
            env=self.environment(
                API_LEVEL="36", PROFILE="pixel_fold", FIRST="failure", STAGE=stage, SECOND=second,
                RUNNER_TEMP=temp, GITHUB_STEP_SUMMARY=summary,
            ),
        )
        return result, summary.read_text()

    def test_the_summary_says_the_dialogs_could_not_be_hidden_and_fails_the_job(self):
        result, summary = self.summary("settle-tool-failed", "", "avd-made", "settle-tool-failed")
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertRegex(
            result.stdout,
            r"^the emulator settled, but the system's error dialogs could not be hidden \(scripts/settle_emulator\.sh"
            r" exit 2, the step's log names the command\), so the tests did not run, nor again \(",
        )
        self.assertTrue(summary.startswith("### ui, API 36, pixel_fold: the emulator settled, but "))

    def test_the_summary_says_so_of_a_second_run_whose_settle_could_not_hide_the_dialogs(self):
        # The first run never settled and left no mark of its own: "How far" would have named its stage otherwise.
        result, summary = self.summary("no-boot", "failure", "avd-made", "settle-tool-failed")
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertRegex(
            result.stdout,
            r"^the emulator did not boot, or never settled; on the second run it settled, but the system's error"
            r" dialogs could not be hidden \(scripts/settle_emulator\.sh exit 2, the step's log names the command\),"
            r" so the tests did not run \(",
        )
        self.assertTrue(summary.startswith("### ui, API 36, pixel_fold: the emulator did not boot, or never settled;"))

    def test_a_second_run_with_no_such_mark_is_summarised_by_its_outcome(self):
        for second, status in (("failure", 1), ("success", 0)):
            with self.subTest(second=second):
                result, _ = self.summary("no-boot", second, "avd-made", "emulator-booted")
                self.assertEqual(status, result.returncode, result.stdout + result.stderr)
                self.assertRegex(
                    result.stdout,
                    rf"^the emulator did not boot, or never settled, so the tests ran once more: {second} \(",
                )


if __name__ == "__main__":
    unittest.main()
