#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TASK_JAVA="$(cat "$TASK_ROOT/tools/java-home.txt")"
TASK_TEST="$TASK_ROOT/tools/codex-connection-test"
mkdir -p "$TASK_TEST"
"$TASK_JAVA/bin/javac" -source 17 -target 17 -d "$TASK_TEST" \
  "$TASK_ROOT/app/src/main/java/com/tango/phone/CodexConnectionHttp.java" \
  "$TASK_ROOT/scripts/test/connection/CodexConnectionHttpChecks.java"
"$TASK_JAVA/bin/java" -cp "$TASK_TEST" com.tango.phone.CodexConnectionHttpChecks
