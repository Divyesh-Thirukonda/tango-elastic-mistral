#!/usr/bin/env bash
# Offline auth and login-gate integration in the actual Android application.
# Requires the project emulator; never contacts OpenAI or spends model quota.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="$(cat "$TASK_ROOT/tools/java-home.txt")"
export ANDROID_HOME="$TASK_ROOT/tools/android-sdk"
export ANDROID_USER_HOME="$TASK_ROOT/tools/android-user"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
TASK_TEST="$TASK_ROOT/tools/auth-smoke"
TASK_BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
TASK_ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
mkdir -p "$TASK_TEST/classes" "$TASK_TEST/dex" "$TASK_ROOT/dist"
cat > "$TASK_TEST/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.tango.authsmoke">
  <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="35" />
  <instrumentation android:name=".AuthSmoke" android:targetPackage="com.tango.phone" android:label="Tango offline auth smoke" />
  <application android:label="Tango auth smoke" />
</manifest>
XML
javac -source 17 -target 17 -classpath "$TASK_ANDROID_JAR" -d "$TASK_TEST/classes" "$TASK_ROOT/scripts/test/auth/"*.java
"$TASK_BUILD_TOOLS/d8" --lib "$TASK_ANDROID_JAR" --min-api 30 --output "$TASK_TEST/dex" "$TASK_TEST/classes/com/tango/authsmoke/"*.class
"$TASK_BUILD_TOOLS/aapt2" link -I "$TASK_ANDROID_JAR" --manifest "$TASK_TEST/AndroidManifest.xml" -o "$TASK_TEST/unsigned.apk"
(cd "$TASK_TEST/dex" && zip -q "$TASK_TEST/unsigned.apk" classes.dex)
"$TASK_BUILD_TOOLS/zipalign" -f 4 "$TASK_TEST/unsigned.apk" "$TASK_TEST/aligned.apk"
TASK_KEYSTORE="$ANDROID_USER_HOME/debug.keystore"
if [[ ! -f "$TASK_KEYSTORE" ]]; then TASK_KEYSTORE="$HOME/.android/debug.keystore"; fi
"$TASK_BUILD_TOOLS/apksigner" sign --ks "$TASK_KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$TASK_TEST/smoke.apk" "$TASK_TEST/aligned.apk"
TASK_SERIAL=emulator-5580
TASK_SCREENS=(signedout idle running waiting paused done account sidebar model keyboard attachments-menu account-advanced task-details)
if [[ "${VOICE_ONLY:-0}" == 1 ]]; then
  TASK_SCREENS=()
elif [[ "${UI_ONLY:-0}" == 1 ]]; then
  TASK_SCREENS=(idle running waiting paused done account sidebar model keyboard attachments-menu account-advanced task-details)
fi
if [[ "$(adb -s "$TASK_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then echo "Tests require emulator-5580." >&2; exit 1; fi
adb -s "$TASK_SERIAL" install -r "$TASK_ROOT/dist/tango-debug.apk"
adb -s "$TASK_SERIAL" install -r "$TASK_TEST/smoke.apk"
# Start with an empty emulator-only account store, including after a failed fixture run.
adb -s "$TASK_SERIAL" shell pm clear com.tango.phone >/dev/null
adb -s "$TASK_SERIAL" shell pm grant com.tango.phone android.permission.POST_NOTIFICATIONS
adb -s "$TASK_SERIAL" shell pm grant com.tango.phone android.permission.RECORD_AUDIO
(
  sleep 3
  adb -s "$TASK_SERIAL" shell settings delete secure enabled_accessibility_services >/dev/null
  adb -s "$TASK_SERIAL" shell settings put secure enabled_accessibility_services com.tango.phone/com.tango.phone.PhoneAccessibilityService
  adb -s "$TASK_SERIAL" shell settings put secure accessibility_enabled 1
) &
TASK_ENABLE_PID=$!
if [[ "${VOICE_ONLY:-0}" == 1 ]]; then
  adb -s "$TASK_SERIAL" shell am instrument -w -e voiceOnly true com.tango.authsmoke/.AuthSmoke | tee "$TASK_ROOT/dist/auth-smoke.txt"
elif [[ "${UI_ONLY:-0}" == 1 ]]; then
  adb -s "$TASK_SERIAL" shell am instrument -w -e uiOnly true com.tango.authsmoke/.AuthSmoke | tee "$TASK_ROOT/dist/auth-smoke.txt"
else
  adb -s "$TASK_SERIAL" shell am instrument -w com.tango.authsmoke/.AuthSmoke | tee "$TASK_ROOT/dist/auth-smoke.txt"
fi
wait "$TASK_ENABLE_PID"
if ! grep -q 'INSTRUMENTATION_RESULT: success=true' "$TASK_ROOT/dist/auth-smoke.txt"; then exit 1; fi
for TASK_SCREEN in ${TASK_SCREENS[@]+"${TASK_SCREENS[@]}"}; do
  adb -s "$TASK_SERIAL" exec-out run-as com.tango.phone cat "files/ui-$TASK_SCREEN.png" > "$TASK_ROOT/dist/tango-ui-$TASK_SCREEN.png"
done
if [[ "${UI_ONLY:-0}" != 1 && "${VOICE_ONLY:-0}" != 1 ]]; then
  adb -s "$TASK_SERIAL" exec-out run-as com.tango.phone cat files/fast-benchmark.json > "$TASK_ROOT/dist/fast-benchmark.json"
fi
echo "Offline Android OAuth and login-gate checks passed."
