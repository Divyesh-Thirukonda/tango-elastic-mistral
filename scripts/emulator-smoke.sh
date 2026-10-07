#!/usr/bin/env bash
# Start a project-local emulator, install Tango, and save runtime evidence.
# Use KEEP_EMULATOR=1 to leave the device available for manual verification.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ ! -f "$TASK_ROOT/tools/java-home.txt" ]]; then "$TASK_ROOT/scripts/bootstrap-android.sh"; fi
export JAVA_HOME="$(cat "$TASK_ROOT/tools/java-home.txt")"
export ANDROID_HOME="$TASK_ROOT/tools/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$TASK_ROOT/tools/android-user"
export ANDROID_AVD_HOME="$TASK_ROOT/tools/avd"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME" "$TASK_ROOT/dist"
case "$(uname -m)" in
  arm64|aarch64) TASK_IMAGE_ARCH=arm64-v8a ;;
  x86_64|amd64) TASK_IMAGE_ARCH=x86_64 ;;
  *) echo "Unsupported emulator architecture." >&2; exit 1 ;;
esac
TASK_IMAGE="system-images;android-35;google_apis;$TASK_IMAGE_ARCH"
if [[ ! -x "$ANDROID_HOME/emulator/emulator" ]] || [[ ! -d "$ANDROID_HOME/system-images/android-35/google_apis/$TASK_IMAGE_ARCH" ]]; then
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" "emulator" "$TASK_IMAGE"
fi
if [[ ! -f "$ANDROID_AVD_HOME/Tango_API_35.ini" ]]; then
  printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
    --name Tango_API_35 --package "$TASK_IMAGE" --device pixel_7 \
    --path "$ANDROID_AVD_HOME/Tango_API_35.avd"
fi

TASK_SERIAL=emulator-5580
TASK_STARTED=0
if ! adb -s "$TASK_SERIAL" get-state >/dev/null 2>&1; then
  "$ANDROID_HOME/emulator/emulator" -avd Tango_API_35 -port 5580 \
    -no-window -no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect \
    -camera-back none -camera-front none -memory 2048 \
    > "$TASK_ROOT/tools/emulator.log" 2>&1 &
  TASK_EMULATOR_PID=$!
  TASK_STARTED=1
fi
cleanup() {
  if [[ "$TASK_STARTED" == 1 ]] && [[ "${KEEP_EMULATOR:-0}" != 1 ]]; then
    adb -s "$TASK_SERIAL" emu kill >/dev/null 2>&1 || true
    kill "$TASK_EMULATOR_PID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
TASK_BOOTED=0
for (( task_attempt=0; task_attempt<120; task_attempt++ )); do
  if [[ "$(adb -s "$TASK_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
    TASK_BOOTED=1
    break
  fi
  sleep 2
done
if [[ "$TASK_BOOTED" != 1 ]]; then
  tail -n 40 "$TASK_ROOT/tools/emulator.log" >&2
  echo "Emulator did not finish booting." >&2
  exit 1
fi
adb -s "$TASK_SERIAL" shell input keyevent 82
adb -s "$TASK_SERIAL" shell settings put global window_animation_scale 0
adb -s "$TASK_SERIAL" shell settings put global transition_animation_scale 0
adb -s "$TASK_SERIAL" shell settings put global animator_duration_scale 0
if [[ ! -f "$TASK_ROOT/dist/tango-debug.apk" ]]; then "$TASK_ROOT/scripts/build.sh"; fi
adb -s "$TASK_SERIAL" install -r "$TASK_ROOT/dist/tango-debug.apk"
adb -s "$TASK_SERIAL" shell pm grant com.tango.phone android.permission.POST_NOTIFICATIONS
adb -s "$TASK_SERIAL" shell pm grant com.tango.phone android.permission.RECORD_AUDIO
adb -s "$TASK_SERIAL" logcat -c
adb -s "$TASK_SERIAL" shell am start -W -n com.tango.phone/.MainActivity
sleep 3
adb -s "$TASK_SERIAL" exec-out screencap -p > "$TASK_ROOT/dist/emulator-smoke.png"
adb -s "$TASK_SERIAL" shell uiautomator dump /sdcard/tango-ui.xml >/dev/null
adb -s "$TASK_SERIAL" pull /sdcard/tango-ui.xml "$TASK_ROOT/dist/emulator-ui.xml" >/dev/null
adb -s "$TASK_SERIAL" logcat -d -b crash > "$TASK_ROOT/dist/emulator-crash.log"
if grep -q 'Process: com.tango.phone' "$TASK_ROOT/dist/emulator-crash.log"; then
  cat "$TASK_ROOT/dist/emulator-crash.log" >&2
  exit 1
fi
echo "Tango launched without a crash. Evidence in $TASK_ROOT/dist."
if [[ "${KEEP_EMULATOR:-0}" == 1 ]]; then echo "Emulator remains available at $TASK_SERIAL."; fi
