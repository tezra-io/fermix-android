#!/usr/bin/env bash
#
# Stop the emulator $ANDROID_SERIAL names, emulator-<port>, and wait, bounded, until its process is gone.
#
# reactivecircus/android-emulator-runner starts CI's emulator in the background with the emulator's output
# piped to the step, and the step ends only once that output closes, as the emulator's process exits. The
# action's own end is one `adb emu kill`: on 2026-10-09 (CI run 37956921652, ui (35, pixel_fold)) an emulator
# answered it "OK: killing emulator, bye bye" and never exited, and the step, every test in it green, ran on
# until the job's timeout cancelled it. So each emulator step's script stops the emulator itself, on every way
# out (ci.yml), and the action's kill after it finds no emulator: that kill fails, and the action only logs it
# (killEmulator catches the failure).
#
# The emulator's process is the listener of its console port, the port in its serial, as ss names it, which is
# exact, where a process's name is not: a machine can run other emulators and other VMs' qemu (AGENTS.md: wait for
# its pid, never match a process's name). The listener must be an emulator's qemu, or nothing is stopped. The
# script asks it to exit with `adb emu kill`, given 10 s to answer, waits 30 polls 1 s apart for the process to be
# gone, and, if it is not, ends it with SIGKILL, says so, and waits 10 polls more. A kill that fails is said and
# waited out all the same: the emulator may be going already; and one that exits as its SIGKILL is sent is gone.
#
#   stop_emulator.sh            the emulator $ANDROID_SERIAL names, as the action sets it for its script
#
# Exit status: 0 the emulator is gone (it exited, it was killed, or nothing listened on its port), 1 it outlived
# SIGKILL, 2 a tool failed, or the serial or the port's listener is no emulator.
set -euo pipefail
shopt -s inherit_errexit

KILL_ANSWER_SECONDS=10
EXIT_POLLS=30
KILLED_POLLS=10
POLL_SECONDS=1

die() {
  echo "stop_emulator: $*" >&2
  exit 2
}

# Whether process [pid] still runs. ps fails alike for a pid that is gone and for an error of its own, so its failure
# is taken for gone only once /proc has no such pid, and is the script's failure otherwise. A zombie whose threads have
# all ended has closed its files, the action's pipe among them, so it is gone; one whose other threads are still
# ending ("l" in its state, as a killed emulator's qemu showed for a moment) holds them yet.
running() {
  local state
  if ! state="$(ps -o stat= -p "$1")"; then
    [ ! -e "/proc/$1" ] || die "ps could not read the state of pid $1, which still runs"
    return 1
  fi
  [[ "$state" != Z* || "$state" == *l* ]]
}

# Waits [polls] polls at most for process [pid] to be gone, and prints the seconds that took, or "running" when it
# still runs after them.
wait_gone() {
  local pid=$1 polls=$2 poll
  for poll in $(seq 0 "$polls"); do
    if ! running "$pid"; then
      echo "$((poll * POLL_SECONDS))"
      return 0
    fi
    if [ "$poll" -lt "$polls" ]; then sleep "$POLL_SECONDS"; fi
  done
  echo running
}

# The pid of the one process that listens on [port], or nothing when none does.
listener_of() {
  local port=$1 listening pids
  listening="$(ss -Hltnp "sport = :$port")" || die "ss could not list the listeners of port $port"
  [ -n "$listening" ] || return 0
  pids="$(grep -o 'pid=[0-9]*' <<<"$listening" | cut -d= -f2 | sort -u)" ||
    die "port $port has a listener ss names no process of: $listening"
  [[ "$pids" =~ ^[0-9]+$ ]] || die "port $port has more than one listener: $(echo "$pids" | tr '\n' ' ')"
  echo "$pids"
}

serial="${ANDROID_SERIAL:-}"
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || die "ANDROID_SERIAL names no emulator: '$serial'"
for tool in adb ps ss timeout; do command -v "$tool" >/dev/null || die "$tool is not on the PATH"; done
port="${serial#emulator-}"

pid="$(listener_of "$port")"
if [ -z "$pid" ]; then
  echo "stop_emulator: nothing listens on $serial's console port $port: no emulator to stop"
  exit 0
fi
name="$(cat "/proc/$pid/comm")" || die "could not read the name of pid $pid, the listener of port $port"
[[ "$name" == qemu-system-* ]] ||
  die "the listener of port $port, pid $pid, is $name, not an emulator's qemu: it is left running"

status=0
timeout "$KILL_ANSWER_SECONDS" adb -s "$serial" emu kill || status=$?
if [ "$status" -eq 124 ]; then
  echo "stop_emulator: adb -s $serial emu kill gave no answer in $KILL_ANSWER_SECONDS s; waiting for pid $pid" >&2
elif [ "$status" -ne 0 ]; then
  echo "stop_emulator: adb -s $serial emu kill failed (exit $status); waiting for pid $pid all the same" >&2
fi

waited="$(wait_gone "$pid" "$EXIT_POLLS")"
if [ "$waited" != running ]; then
  echo "stop_emulator: $serial (pid $pid) exited $waited s after its kill"
  exit 0
fi
# The emulator can exit between the last poll and its SIGKILL, which then finds no process: that is the emulator gone.
if ! kill -KILL "$pid"; then
  running "$pid" && die "could not send SIGKILL to pid $pid, which still runs"
  echo "stop_emulator: $serial (pid $pid) exited as it was to be ended with SIGKILL," \
    "$((EXIT_POLLS * POLL_SECONDS)) s after its kill"
  exit 0
fi
echo "stop_emulator: $serial (pid $pid) still ran $((EXIT_POLLS * POLL_SECONDS)) s after its kill: ended it with SIGKILL"
waited="$(wait_gone "$pid" "$KILLED_POLLS")"
if [ "$waited" != running ]; then
  exit 0
fi
echo "stop_emulator: $serial (pid $pid) still runs $((KILLED_POLLS * POLL_SECONDS)) s after SIGKILL," \
  "in state $(ps -o stat= -p "$pid")" >&2
exit 1
