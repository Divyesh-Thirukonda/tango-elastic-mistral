#!/usr/bin/env bash
# With a phone connected and USB debugging authorized: scripts/install.sh [-s SERIAL]
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ ! -f "$TASK_ROOT/dist/tango-debug.apk" ]] || [[ ! -x "$TASK_ROOT/tools/android-sdk/platform-tools/adb" ]]; then
  "$TASK_ROOT/scripts/build.sh"
fi
"$TASK_ROOT/tools/android-sdk/platform-tools/adb" "$@" install -r "$TASK_ROOT/dist/tango-debug.apk"
