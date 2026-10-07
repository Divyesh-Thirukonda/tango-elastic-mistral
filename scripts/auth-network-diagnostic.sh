#!/usr/bin/env bash
# Native Android HTTPS: public OpenAI discovery/JWKS and explicitly invalid dummy OAuth exchange.
# Does not use a real authorization code, identity, access token, refresh token, or model API.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="$(cat "$TASK_ROOT/tools/java-home.txt")"
export ANDROID_HOME="$TASK_ROOT/tools/android-sdk"
export ANDROID_USER_HOME="$TASK_ROOT/tools/android-user"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
TASK_TEST="$TASK_ROOT/tools/auth-network-diagnostic"
TASK_BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
TASK_ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
mkdir -p "$TASK_TEST/classes" "$TASK_TEST/dex" "$TASK_ROOT/dist"
cat > "$TASK_TEST/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.tango.authnetwork">
  <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="35" />
  <instrumentation android:name="com.tango.authsmoke.LiveAuthDiagnostic" android:targetPackage="com.tango.phone" android:label="Tango native HTTPS diagnostic" />
  <application android:label="Tango native HTTPS diagnostic" />
</manifest>
XML
javac -source 17 -target 17 -classpath "$TASK_ANDROID_JAR" -d "$TASK_TEST/classes" "$TASK_ROOT/scripts/test/auth/LiveAuthDiagnostic.java"
"$TASK_BUILD_TOOLS/d8" --lib "$TASK_ANDROID_JAR" --min-api 30 --output "$TASK_TEST/dex" "$TASK_TEST/classes/com/tango/authsmoke/"*.class
"$TASK_BUILD_TOOLS/aapt2" link -I "$TASK_ANDROID_JAR" --manifest "$TASK_TEST/AndroidManifest.xml" -o "$TASK_TEST/unsigned.apk"
(cd "$TASK_TEST/dex" && zip -q "$TASK_TEST/unsigned.apk" classes.dex)
"$TASK_BUILD_TOOLS/zipalign" -f 4 "$TASK_TEST/unsigned.apk" "$TASK_TEST/aligned.apk"
TASK_KEYSTORE="$ANDROID_USER_HOME/debug.keystore"
if [[ ! -f "$TASK_KEYSTORE" ]]; then TASK_KEYSTORE="$HOME/.android/debug.keystore"; fi
"$TASK_BUILD_TOOLS/apksigner" sign --ks "$TASK_KEYSTORE" --ks-pass pass:android --key-pass pass:android --out "$TASK_TEST/smoke.apk" "$TASK_TEST/aligned.apk"
TASK_SERIAL=emulator-5580
if [[ "$(adb -s "$TASK_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then echo "Tests require emulator-5580." >&2; exit 1; fi
adb -s "$TASK_SERIAL" install -r "$TASK_ROOT/dist/tango-debug.apk"
adb -s "$TASK_SERIAL" install -r "$TASK_TEST/smoke.apk"
TASK_INSTRUMENT_ARGS=()
if [[ "${TANGO_LOCAL_AUTH_FIXTURE:-0}" == 1 ]]; then
  if [[ ! -f "$TASK_ROOT/tools/auth-http-fixture/cert.pem" ]]; then echo "Start the controlled HTTPS fixture before enabling local probes." >&2; exit 1; fi
  adb -s "$TASK_SERIAL" push "$TASK_ROOT/tools/auth-http-fixture/cert.pem" /data/local/tmp/tango-fixture-cert.pem >/dev/null
  TASK_INSTRUMENT_ARGS=(-e localFixture true)
else adb -s "$TASK_SERIAL" shell rm -f /data/local/tmp/tango-fixture-cert.pem
fi
adb -s "$TASK_SERIAL" shell am instrument -w "${TASK_INSTRUMENT_ARGS[@]}" com.tango.authnetwork/com.tango.authsmoke.LiveAuthDiagnostic | tee "$TASK_ROOT/dist/auth-network-diagnostic.txt"
if ! grep -q 'INSTRUMENTATION_RESULT: success=true' "$TASK_ROOT/dist/auth-network-diagnostic.txt"; then exit 1; fi
echo "Native Android HTTPS diagnostic completed without user credentials or inference."
