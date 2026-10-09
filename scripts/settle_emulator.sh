#!/usr/bin/env bash
#
# Wait for an emulator that has just booted to settle before the instrumented tests run.
#
# sys.boot_completed comes before the phone is usable: on a cold boot, System UI can be slow
# enough that the system shows "System UI isn't responding", and that dialog's window holds the
# focus a test's key event and its read of the clipboard need. Settled means the home screen's
# window has the focus. Each poll wakes the phone, dismisses the keyguard and closes system
# dialogs; after 30 polls 2 s apart the script fails, naming the window that has the focus.
#
# Once settled, it puts Android's own setting for a test device, hide_error_dialogs: no "isn't
# responding" and no "has stopped" dialog is drawn after it, and am still logs each. A slow runner
# can make an app miss its input deadline once the tests start, the launcher among them, and the
# system draws that dialog over everything: on 2026-10-09 (CI run 37919058881) one of the launcher's
# held the focus through eighteen of onboarding's tests. A setting that cannot be put fails it.
# The device keeps the setting, so the script settles only the emulator ANDROID_SERIAL names, never
# a phone or whatever device adb would pick alone.
#
#   settle_emulator.sh            the emulator $ANDROID_SERIAL names, emulator-<port>, as the
#                                 emulator runner sets it for its script
#
# CI's ui job runs it after the boot and before the tests (README.md, Instrumented tests).
# Exit status: 0 settled, 1 never settled, 2 a tool failed, or ANDROID_SERIAL names no emulator.
set -euo pipefail

POLLS=30
POLL_SECONDS=2

die() {
  echo "settle_emulator: $*" >&2
  exit 2
}

[[ "${ANDROID_SERIAL:-}" =~ ^emulator-[0-9]+$ ]] ||
  die "ANDROID_SERIAL names no emulator: '${ANDROID_SERIAL:-}'; the setting this puts stays on the device"
command -v adb >/dev/null || die "adb is not on the PATH"

# The home activity, as the package manager resolves the HOME intent now, e.g.
# com.google.android.apps.nexuslauncher/.NexusLauncherActivity. Until the user's storage is unlocked
# it is Settings' FallbackHome, which stands in for the home screen and is not it.
home_activity() {
  adb shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -n 1
}

# The window with the focus, as dumpsys names it. awk reads to the end: leaving early would break the
# pipe, which pipefail turns into a failure.
focused_window() {
  adb shell dumpsys window | tr -d '\r' | awk '/mCurrentFocus/ && !seen { print; seen = 1 }'
}

home=""
focus=""
for try in $(seq "$POLLS"); do
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell wm dismiss-keyguard
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null
  home="$(home_activity)"
  focus="$(focused_window)"
  if [[ "$home" == */* && "$home" != */.FallbackHome && "$focus" == *"${home%%/*}/"* ]]; then
    adb shell settings put global hide_error_dialogs 1 ||
      die "could not hide the system's error dialogs: adb shell settings put global hide_error_dialogs 1 failed"
    echo "settled after poll $try: ${focus#"${focus%%[![:space:]]*}"}; the system's error dialogs are hidden" \
      "(settings put global hide_error_dialogs 1)"
    exit 0
  fi
  sleep "$POLL_SECONDS"
done
echo "settle_emulator: the home screen never had the focus in $((POLLS * POLL_SECONDS)) s;" \
  "the home: ${home:-none}; the focus: ${focus:-none}" >&2
exit 1
