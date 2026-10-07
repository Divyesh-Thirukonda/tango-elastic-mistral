#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ ! -f "$TASK_ROOT/tools/java-home.txt" ]] || [[ ! -x "$(cat "$TASK_ROOT/tools/java-home.txt" 2>/dev/null)/bin/java" ]] || [[ ! -d "$TASK_ROOT/tools/android-sdk/platforms/android-35" ]]; then
  "$TASK_ROOT/scripts/bootstrap-android.sh"
fi
export JAVA_HOME="$(cat "$TASK_ROOT/tools/java-home.txt")"
export ANDROID_HOME="$TASK_ROOT/tools/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$TASK_ROOT/tools/android-user"
export ANDROID_AVD_HOME="$TASK_ROOT/tools/avd"
export GRADLE_USER_HOME="$TASK_ROOT/tools/gradle-cache"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
cd "$TASK_ROOT"
./gradlew --no-daemon :app:assembleDebug "$@"
mkdir -p "$TASK_ROOT/dist"
cp "$TASK_ROOT/app/build/outputs/apk/debug/app-debug.apk" "$TASK_ROOT/dist/tango-debug.apk"
echo "Installable APK: $TASK_ROOT/dist/tango-debug.apk"
