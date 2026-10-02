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
#   settle_emulator.sh            the device adb picks: the one attached, or $ANDROID_SERIAL
#
# CI's ui job runs it after the boot and before the tests (README.md, Instrumented tests).
# Exit status: 0 settled, 1 never settled, 2 a tool failed.
set -euo pipefail

POLLS=30
POLL_SECONDS=2

die() {
  echo "settle_emulator: $*" >&2
  exit 2
}

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
    echo "settled after poll $try: ${focus#"${focus%%[![:space:]]*}"}"
    exit 0
  fi
  sleep "$POLL_SECONDS"
done
echo "settle_emulator: the home screen never had the focus in $((POLLS * POLL_SECONDS)) s;" \
  "the home: ${home:-none}; the focus: ${focus:-none}" >&2
exit 1
