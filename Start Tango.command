#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")" && pwd)"
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
if ! command -v node >/dev/null 2>&1; then
  echo "Copy dist/tango-debug.apk to your Android phone to install Tango."
  echo "Optional Wi-Fi transfer requires Node.js 20+ from https://nodejs.org."
  read -r -p "Press Return to close. "
  exit 1
fi
cd "$TASK_ROOT"
echo "Tango runs on your phone. This window only shares its installation APK."
echo "After installing, close this window and sign in with ChatGPT on Android."
exec node scripts/share-apk.mjs --lan
