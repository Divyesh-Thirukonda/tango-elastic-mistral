#!/usr/bin/env bash
# Fixture-only tests for Instant/Medium/High; no credentials or provider traffic.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TASK_TEST="$TASK_ROOT/tools/model-mode-smoke"
TASK_JAVA="$(cat "$TASK_ROOT/tools/java-home.txt")/bin"
TASK_ANDROID_JAR="$TASK_ROOT/tools/android-sdk/platforms/android-35/android.jar"
TASK_JSON="$TASK_ROOT/tools/inference-smoke/json-20240303.jar"
mkdir -p "$TASK_TEST/src/com/tango/phone" "$TASK_TEST/classes" "$TASK_ROOT/dist"
if [[ ! -f "$TASK_JSON" ]]; then
  mkdir -p "$(dirname "$TASK_JSON")"
  curl -fsSL https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o "$TASK_JSON"
fi
cat > "$TASK_TEST/src/com/tango/phone/ChatGptAuth.java" <<'JAVA'
package com.tango.phone;
import android.content.Context;
final class ChatGptAuth {
    static ChatGptAuth get(Context context) { throw new AssertionError("No authentication in this test."); }
    boolean hasPlanAccess() { return false; }
    boolean isSignedIn() { return false; }
    String activeClientId() { return ""; }
    String accessToken() { throw new AssertionError("No tokens in this test."); }
    void invalidateCredentials(String account,String reason) { throw new AssertionError("No credentials in this test."); }
}
JAVA
"$TASK_JAVA/javac" -source 17 -target 17 -cp "$TASK_JSON:$TASK_ANDROID_JAR" -d "$TASK_TEST/classes" \
  "$TASK_TEST/src/com/tango/phone/ChatGptAuth.java" \
  "$TASK_ROOT/app/src/main/java/com/tango/phone/ActionGuard.java" \
  "$TASK_ROOT/app/src/main/java/com/tango/phone/FastPlan.java" \
  "$TASK_ROOT/app/src/main/java/com/tango/phone/FastDecision.java" \
  "$TASK_ROOT/app/src/main/java/com/tango/phone/AgentPolicy.java" \
  "$TASK_ROOT/app/src/main/java/com/tango/phone/ApiClient.java" \
  "$TASK_ROOT/scripts/test/model/ModelModeSmoke.java"
"$TASK_JAVA/java" -cp "$TASK_TEST/classes:$TASK_JSON:$TASK_ANDROID_JAR" com.tango.phone.ModelModeSmoke | tee "$TASK_ROOT/dist/model-mode-smoke.txt"
