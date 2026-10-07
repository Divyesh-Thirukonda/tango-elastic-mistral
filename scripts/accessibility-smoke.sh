#!/usr/bin/env bash
# Native integration checks on the project's emulator. No model/network calls.
# Builds a separate, temporary instrumentation APK; app source is not modified.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="$(cat "$TASK_ROOT/tools/java-home.txt")"
export ANDROID_HOME="$TASK_ROOT/tools/android-sdk"
export ANDROID_USER_HOME="$TASK_ROOT/tools/android-user"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
TASK_TEST="$TASK_ROOT/tools/accessibility-smoke"
TASK_BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
TASK_ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
mkdir -p "$TASK_TEST/src/com/tango/smoke" "$TASK_TEST/classes" "$TASK_TEST/dex" "$TASK_ROOT/dist"
cat > "$TASK_TEST/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.tango.smoke">
  <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="35" />
  <instrumentation android:name=".AccessibilitySmoke" android:targetPackage="com.tango.phone" android:label="Tango native smoke" />
  <application android:label="Tango smoke" android:theme="@android:style/Theme.Material.Light.NoActionBar">
    <activity android:name=".PasswordActivity" android:exported="true" />
  </application>
</manifest>
XML
cat > "$TASK_TEST/src/com/tango/smoke/PasswordActivity.java" <<'JAVA'
package com.tango.smoke;
import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
public final class PasswordActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText("Tango secret-handling test");
        layout.addView(label);
        EditText password = new EditText(this);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText("tango-smoke-secret");
        password.setHint("Password");
        layout.addView(password);
        setContentView(layout);
    }
}
JAVA
cat > "$TASK_TEST/src/com/tango/smoke/AccessibilitySmoke.java" <<'JAVA'
package com.tango.smoke;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Method;
public final class AccessibilitySmoke extends Instrumentation {
    private Method observe;
    private Method execute;
    private Class<?> taskClass;
    private int ticket;
    private final JSONObject checks = new JSONObject();
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private JSONObject screen() throws Exception { return (JSONObject) observe.invoke(null, getTargetContext()); }
    private JSONObject act(JSONObject action) throws Exception { return (JSONObject) execute.invoke(null, getTargetContext(), action.put("_generation",ticket)); }
    private void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private String findText(JSONObject screen, String text) throws Exception {
        JSONArray nodes = screen.optJSONArray("nodes");
        if (nodes == null) return "";
        for (int i=0; i<nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if (text.equals(node.optString("text"))) return node.getString("id");
        }
        return "";
    }
    private boolean hasText(JSONObject screen, String text) { return screen.toString().contains(text); }
    private void waitScreen() { SystemClock.sleep(1600); }
    private void openSettings() {
        getTargetContext().startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitScreen();
    }
    private void testGuard(ClassLoader loader) throws Exception {
        Class<?> guard = loader.loadClass("com.tango.phone.ActionGuard");
        Method block = guard.getMethod("blockReason", String.class, JSONObject.class, JSONObject.class);
        JSONObject send = new JSONObject().put("id","send").put("text","Send").put("description","Send")
                .put("bounds",new JSONArray(new int[]{700,100,1000,300}));
        JSONObject observation = new JSONObject().put("width",1000).put("height",2000)
                .put("nodes",new JSONArray().put(send));
        JSONObject tap = new JSONObject().put("type","tap").put("target","send");
        require(block.invoke(null,"Keep that email as a draft",observation,tap)!=null,"Duplicated Send labels bypassed draft guard.");
        checks.put("duplicateSendBlocked",true);
        JSONObject coordinate = new JSONObject().put("type","tap").put("target",JSONObject.NULL).put("x",0.8).put("y",0.1);
        require(block.invoke(null,"Keep that email as a draft",observation,coordinate)!=null,"Coordinate Send bypassed draft guard.");
        checks.put("coordinateSendBlocked",true);
        require(block.invoke(null,"Keep that email as a draft",observation,new JSONObject(coordinate.toString()).put("x",0.2))==null,"Unrelated coordinate was blocked.");
        require(block.invoke(null,"Keep that email as a draft\nFollow-up instruction from user: send it",observation,tap)==null,"Explicit follow-up send authorization ignored.");
        require(block.invoke(null,"Keep that email as a draft\nFollow-up instruction from user: don't send it",observation,tap)!=null,"Negative follow-up bypassed draft guard.");
        checks.put("followupSendScope",true);
        Method validate = guard.getMethod("validate",JSONObject.class);
        boolean rejected=false;
        try { validate.invoke(null,new JSONObject().put("type","tap").put("target",JSONObject.NULL).put("x",1.5).put("y",0.5)); }
        catch(java.lang.reflect.InvocationTargetException expected) { rejected=true; }
        require(rejected,"Out-of-screen coordinates accepted.");
        checks.put("invalidCoordinateRejected",true);
    }
    @Override public void onStart() {
        Bundle results = new Bundle();
        try {
            Context context = getTargetContext();
            ClassLoader loader = context.getClassLoader();
            testGuard(loader);
            Class<?> controller = loader.loadClass("com.tango.phone.PhoneController");
            observe = controller.getMethod("observe", Context.class);
            execute = controller.getMethod("execute", Context.class, JSONObject.class);
            JSONObject unauthorized = (JSONObject) execute.invoke(null,context,new JSONObject().put("type","back"));
            require(!unauthorized.optBoolean("ok"),"Action without a task ticket was accepted.");
            checks.put("missingTaskTicketRejected",true);
            // Supply an isolated test task through reflection. This does not start
            // the model loop or change the production requirement for valid tickets.
            taskClass=loader.loadClass("com.tango.phone.AgentService");
            Object testTask=taskClass.getConstructor().newInstance();
            taskClass.getField("instance").set(null,testTask);
            taskClass.getField("state").set(null,"running");
            java.lang.reflect.Field generation=taskClass.getDeclaredField("generation");
            generation.setAccessible(true);
            ticket=((java.util.concurrent.atomic.AtomicInteger)generation.get(testTask)).get();
            Class<?> service = loader.loadClass("com.tango.phone.PhoneAccessibilityService");
            for (int i=0; i<40 && service.getField("instance").get(null)==null; i++) SystemClock.sleep(250);
            require(service.getField("instance").get(null)!=null, "Accessibility service did not connect.");
            openSettings();
            JSONObject initial = screen();
            require("com.android.settings".equals(initial.optString("packageName")), "Wrong observed package: " + initial);
            require(initial.optJSONArray("nodes")!=null && initial.getJSONArray("nodes").length()>0, "No accessible Settings nodes.");
            require(!initial.optString("screenshot").isEmpty(), "Screenshot failed: " + initial.optString("screenshotError"));
            checks.put("settingsNodes", initial.getJSONArray("nodes").length());
            checks.put("screenshot", initial.optInt("screenshotWidth") + "x" + initial.optInt("screenshotHeight"));
            String network = findText(initial, "Network & internet");
            require(!network.isEmpty(), "Settings network label not found.");
            JSONObject tapped = act(new JSONObject().put("type","tap").put("target",network));
            require(tapped.optBoolean("ok"), "Native tap failed: " + tapped);
            waitScreen();
            JSONObject networkScreen = screen();
            require(hasText(networkScreen,"Internet") || hasText(networkScreen,"Network & internet"), "Network page not visible.");
            checks.put("nodeTap", true);
            JSONObject stale = act(new JSONObject().put("type","tap").put("target",network));
            require(!stale.optBoolean("ok"), "Old snapshot target was accepted.");
            checks.put("staleTargetRejected", true);
            JSONObject backed = act(new JSONObject().put("type","back"));
            require(backed.optBoolean("ok"), "Native Back failed: " + backed);
            waitScreen();
            require(hasText(screen(), "Connected devices"), "Back did not return to Settings homepage.");
            checks.put("globalBack", true);
            JSONObject home = screen();
            String search = findText(home, "Search settings");
            require(!search.isEmpty(), "Search settings node not found.");
            require(act(new JSONObject().put("type","tap").put("target",search)).optBoolean("ok"), "Could not open Settings search.");
            waitScreen();
            JSONObject searchScreen = screen();
            JSONArray nodes = searchScreen.getJSONArray("nodes");
            String editable = "";
            for (int i=0; i<nodes.length(); i++) if (nodes.getJSONObject(i).optBoolean("editable")) { editable=nodes.getJSONObject(i).getString("id"); break; }
            require(!editable.isEmpty(), "Settings search field not exposed.");
            JSONObject typed = act(new JSONObject().put("type","type").put("target",editable).put("text","Bluetooth"));
            require(typed.optBoolean("ok"), "Native text input failed: " + typed);
            waitScreen();
            require(hasText(screen(),"Bluetooth"), "Typed query not visible.");
            checks.put("textInput", true);
            context.startActivity(new Intent().setClassName("com.tango.smoke","com.tango.smoke.PasswordActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitScreen();
            JSONObject password = screen();
            require(password.optBoolean("password"), "Password field not detected.");
            require(password.optString("screenshot").isEmpty(), "Password screen screenshot leaked.");
            require(!password.toString().contains("tango-smoke-secret"), "Password value leaked in observation.");
            checks.put("passwordRedacted", true);
            checks.put("passwordScreenshotWithheld", true);
            taskClass.getField("state").set(null,"paused");
            require(!act(new JSONObject().put("type","back")).optBoolean("ok"),"Paused task dispatched a phone action.");
            checks.put("pausedTaskRejected",true);
            taskClass.getField("state").set(null,"idle");
            taskClass.getField("instance").set(null,null);
            openSettings();
            results.putString("success", "true");
            results.putString("checks", checks.toString());
            finish(0, results);
        } catch (Throwable error) {
            results.putString("success", "false");
            results.putString("checks", checks.toString());
            results.putString("error", error.toString());
            finish(1, results);
        }
    }
}
JAVA
javac -source 17 -target 17 -classpath "$TASK_ANDROID_JAR" -d "$TASK_TEST/classes" "$TASK_TEST/src/com/tango/smoke/"*.java
"$TASK_BUILD_TOOLS/d8" --lib "$TASK_ANDROID_JAR" --min-api 30 --output "$TASK_TEST/dex" "$TASK_TEST/classes/com/tango/smoke/"*.class
"$TASK_BUILD_TOOLS/aapt2" link -I "$TASK_ANDROID_JAR" --manifest "$TASK_TEST/AndroidManifest.xml" -o "$TASK_TEST/unsigned.apk"
(cd "$TASK_TEST/dex" && zip -q "$TASK_TEST/unsigned.apk" classes.dex)
"$TASK_BUILD_TOOLS/zipalign" -f 4 "$TASK_TEST/unsigned.apk" "$TASK_TEST/aligned.apk"
TASK_KEYSTORE="$ANDROID_USER_HOME/debug.keystore"
if [[ ! -f "$TASK_KEYSTORE" ]]; then TASK_KEYSTORE="$HOME/.android/debug.keystore"; fi
if [[ ! -f "$TASK_KEYSTORE" ]]; then echo "Build the app first to create its debug signing key." >&2; exit 1; fi
"$TASK_BUILD_TOOLS/apksigner" sign --ks "$TASK_KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$TASK_TEST/smoke.apk" "$TASK_TEST/aligned.apk"
if [[ "${1:-}" == --prepare ]]; then echo "Instrumentation APK prepared: $TASK_TEST/smoke.apk"; exit 0; fi
TASK_SERIAL=emulator-5580
require_device="$(adb -s "$TASK_SERIAL" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')"
if [[ "$require_device" != 1 ]]; then echo "This test is restricted to emulator-5580." >&2; exit 1; fi
adb -s "$TASK_SERIAL" install -r "$TASK_ROOT/dist/tango-debug.apk"
adb -s "$TASK_SERIAL" install -r "$TASK_TEST/smoke.apk"
# Starting instrumentation replaces the target process. Re-enable the service
# after that replacement so Android clears its transient crashed-service state.
(
  sleep 3
  adb -s "$TASK_SERIAL" shell settings delete secure enabled_accessibility_services >/dev/null
  adb -s "$TASK_SERIAL" shell settings put secure enabled_accessibility_services com.tango.phone/com.tango.phone.PhoneAccessibilityService
  adb -s "$TASK_SERIAL" shell settings put secure accessibility_enabled 1
) &
TASK_ENABLE_PID=$!
adb -s "$TASK_SERIAL" shell am instrument -w com.tango.smoke/.AccessibilitySmoke | tee "$TASK_ROOT/dist/accessibility-smoke.txt"
wait "$TASK_ENABLE_PID"
if ! grep -q 'INSTRUMENTATION_RESULT: success=true' "$TASK_ROOT/dist/accessibility-smoke.txt"; then exit 1; fi
# Restore the ordinary app process and service after instrumentation exits.
adb -s "$TASK_SERIAL" shell am start -W -n com.tango.phone/.MainActivity >/dev/null
adb -s "$TASK_SERIAL" shell settings delete secure enabled_accessibility_services >/dev/null
adb -s "$TASK_SERIAL" shell settings put secure enabled_accessibility_services com.tango.phone/com.tango.phone.PhoneAccessibilityService
adb -s "$TASK_SERIAL" shell settings put secure accessibility_enabled 1
echo "Native accessibility integration checks passed."
