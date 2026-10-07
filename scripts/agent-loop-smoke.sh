#!/usr/bin/env bash
# Real AgentService + encrypted pairing + deterministic localhost provider.
# Verifies ask/resume and cancellation while a provider response is pending.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="$(cat "$TASK_ROOT/tools/java-home.txt")"
export ANDROID_HOME="$TASK_ROOT/tools/android-sdk"
export ANDROID_USER_HOME="$TASK_ROOT/tools/android-user"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
TASK_TEST="$TASK_ROOT/tools/agent-loop-smoke"
TASK_BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
TASK_ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
mkdir -p "$TASK_TEST/src/com/tango/loop" "$TASK_TEST/classes" "$TASK_TEST/dex" "$TASK_ROOT/dist"
cat > "$TASK_TEST/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.tango.loop">
  <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="35" />
  <instrumentation android:name=".AgentLoopSmoke" android:targetPackage="com.tango.phone" android:label="Tango agent loop smoke" />
  <application android:label="Tango loop smoke" />
</manifest>
XML
cat > "$TASK_TEST/src/com/tango/loop/AgentLoopSmoke.java" <<'JAVA'
package com.tango.loop;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.BooleanSupplier;
public final class AgentLoopSmoke extends Instrumentation {
    private Context context;
    private Class<?> agent;
    private Method observe;
    private final JSONObject checks=new JSONObject();
    private String readState() {
        try { return (String)agent.getField("state").get(null); }
        catch(Exception e) { throw new RuntimeException(e); }
    }
    private void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    private void waitFor(BooleanSupplier condition,long duration,String message) {
        long until=SystemClock.uptimeMillis()+duration;
        while(SystemClock.uptimeMillis()<until) { if(condition.getAsBoolean())return; SystemClock.sleep(100); }
        throw new AssertionError(message+"; state="+readState());
    }
    private JSONObject backend() throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://10.0.2.2:8788/status").openConnection();
        connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
        try(InputStream in=connection.getInputStream()) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] buffer=new byte[4096]; int n;
            while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);
            return new JSONObject(bytes.toString("UTF-8"));
        } finally { connection.disconnect(); }
    }
    private void waitBackend(String key,int value) throws Exception {
        long until=SystemClock.uptimeMillis()+15000;
        while(SystemClock.uptimeMillis()<until) { if(backend().optInt(key)>=value)return; SystemClock.sleep(100); }
        throw new AssertionError("Provider never reached "+key+"; state="+readState());
    }
    private void foreground() {
        runOnMainSync(()->context.startActivity(new Intent().setClassName("com.tango.phone","com.tango.phone.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP)));
        SystemClock.sleep(350);
    }
    private void settings() {
        runOnMainSync(()->context.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }
    private void startTask(String goal) {
        foreground();
        runOnMainSync(()->context.startForegroundService(new Intent().setClassName("com.tango.phone","com.tango.phone.AgentService")
                .putExtra("goal",goal)));
        settings();
    }
    private void resume() {
        // Start from the visible MainActivity, as the user's Continue control does.
        foreground();
        runOnMainSync(()->context.startForegroundService(new Intent().setClassName("com.tango.phone","com.tango.phone.AgentService")
                .setAction("resume").putExtra("reply","Board Game Club")));
        settings();
    }
    private void control(String method) throws Exception {
        Object instance=agent.getField("instance").get(null);
        require(instance!=null,"Agent service not alive.");
        Method callback=method.equals("pauseTask")?agent.getMethod(method,String.class):agent.getMethod(method);
        runOnMainSync(()->{
            try { if(method.equals("pauseTask"))callback.invoke(instance,"Paused in native test"); else callback.invoke(instance); }
            catch(Exception error) { throw new RuntimeException(error); }
        });
    }
    private JSONObject screen() throws Exception { return (JSONObject)observe.invoke(null,context); }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle results=new Bundle(); Method putSecret=null; String oldToken="",oldProvider="",oldUrl="";
        SharedPreferences prefs=null;
        try {
            context=getTargetContext(); ClassLoader loader=context.getClassLoader();
            agent=loader.loadClass("com.tango.phone.AgentService");
            observe=loader.loadClass("com.tango.phone.PhoneController").getMethod("observe",Context.class);
            Class<?> secrets=loader.loadClass("com.tango.phone.SecretStore");
            putSecret=secrets.getMethod("put",Context.class,String.class,String.class);
            Method getSecret=secrets.getMethod("get",Context.class,String.class);
            oldToken=(String)getSecret.invoke(null,context,"bridge_token");
            prefs=context.getSharedPreferences("tango",0);
            oldProvider=prefs.getString("provider","chatgpt"); oldUrl=prefs.getString("bridge_url","http://10.0.2.2:8787");
            putSecret.invoke(null,context,"bridge_token","tango-test-only-8788");
            require("tango-test-only-8788".equals(getSecret.invoke(null,context,"bridge_token")),"Encrypted token roundtrip failed.");
            checks.put("keystoreRoundtrip",true);
            prefs.edit().putString("provider","chatgpt").putString("bridge_url","http://10.0.2.2:8788").commit();
            Class<?> access=loader.loadClass("com.tango.phone.PhoneAccessibilityService");
            long accessUntil=SystemClock.uptimeMillis()+12000;
            while(access.getField("instance").get(null)==null && SystemClock.uptimeMillis()<accessUntil)SystemClock.sleep(100);
            require(access.getField("instance").get(null)!=null,"Accessibility service did not connect.");
            startTask("SMOKE_ASK_RESUME Find the club registration form.");
            waitFor(()->readState().equals("waiting"),15000,"Agent never asked");
            require("Which club name?".equals(agent.getField("question").get(null)),"Unexpected blocker: "+agent.getField("question").get(null));
            checks.put("askReachedWaiting",true);
            resume();
            waitFor(()->readState().equals("done"),15000,"Resumed agent did not complete");
            require(((String)agent.getField("goal").get(null)).contains("Follow-up instruction from user: Board Game Club"),"User reply not preserved as trusted follow-up.");
            require(backend().optInt("resumedRequests")==1,"Backend did not receive resumed goal.");
            checks.put("resumeReachedDone",true);
            checks.put("trustedFollowupPreserved",true);
            SystemClock.sleep(500);
            startTask("SMOKE_PAUSE_DELAY Keep the Settings page open.");
            waitBackend("pausePending",1);
            String pausePackage=screen().optString("packageName");
            require(pausePackage.contains("settings"),"Pause fixture is not a Settings page: "+pausePackage);
            control("pauseTask");
            require(readState().equals("paused"),"Pause not reflected in state.");
            waitBackend("pauseDelivered",1); SystemClock.sleep(700);
            require(readState().equals("paused"),"Late reply changed paused state.");
            require(agent.getField("steps").getInt(null)==0,"Late paused reply dispatched an action.");
            require(pausePackage.equals(screen().optString("packageName")),"Late paused Home action escaped cancellation.");
            checks.put("pausedLateReplyDiscarded",true);
            startTask("SMOKE_STOP_DELAY Keep the Settings page open.");
            waitBackend("stopPending",1);
            String stopPackage=screen().optString("packageName");
            require(stopPackage.contains("settings"),"Stop fixture is not a Settings page: "+stopPackage);
            control("stopTask");
            require(readState().equals("stopped"),"Stop not reflected in state.");
            waitBackend("stopDelivered",1); SystemClock.sleep(700);
            require(readState().equals("stopped"),"Late reply changed stopped state.");
            require(agent.getField("steps").getInt(null)==0,"Late stopped reply dispatched an action.");
            require(stopPackage.equals(screen().optString("packageName")),"Late stopped Home action escaped cancellation.");
            checks.put("stoppedLateReplyDiscarded",true);
            checks.put("providerRequests",backend().optInt("requests"));
            results.putString("success","true");
        } catch(Throwable error) {
            results.putString("success","false"); results.putString("error",error.toString());
        } finally {
            try {
                if(context!=null && agent!=null) {
                    runOnMainSync(()->context.stopService(new Intent().setClassName("com.tango.phone","com.tango.phone.AgentService")));
                    SystemClock.sleep(600);
                    agent.getField("state").set(null,"idle"); agent.getField("status").set(null,"Ready when you are.");
                    agent.getField("goal").set(null,""); agent.getField("question").set(null,""); agent.getField("steps").setInt(null,0);
                    agent.getField("journal").set(null,new JSONArray()); context.deleteFile("task.json");
                }
                if(putSecret!=null)putSecret.invoke(null,context,"bridge_token",oldToken);
                if(prefs!=null)prefs.edit().putString("provider",oldProvider).putString("bridge_url",oldUrl).commit();
            } catch(Throwable cleanup) { results.putString("cleanupError",cleanup.toString()); }
        }
        results.putString("checks",checks.toString());
        finish("true".equals(results.getString("success"))?0:1,results);
    }
}
JAVA
javac -source 17 -target 17 -classpath "$TASK_ANDROID_JAR" -d "$TASK_TEST/classes" "$TASK_TEST/src/com/tango/loop/"*.java
"$TASK_BUILD_TOOLS/d8" --lib "$TASK_ANDROID_JAR" --min-api 30 --output "$TASK_TEST/dex" "$TASK_TEST/classes/com/tango/loop/"*.class
"$TASK_BUILD_TOOLS/aapt2" link -I "$TASK_ANDROID_JAR" --manifest "$TASK_TEST/AndroidManifest.xml" -o "$TASK_TEST/unsigned.apk"
(cd "$TASK_TEST/dex" && zip -q "$TASK_TEST/unsigned.apk" classes.dex)
"$TASK_BUILD_TOOLS/zipalign" -f 4 "$TASK_TEST/unsigned.apk" "$TASK_TEST/aligned.apk"
TASK_KEYSTORE="$ANDROID_USER_HOME/debug.keystore"
if [[ ! -f "$TASK_KEYSTORE" ]]; then TASK_KEYSTORE="$HOME/.android/debug.keystore"; fi
"$TASK_BUILD_TOOLS/apksigner" sign --ks "$TASK_KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$TASK_TEST/smoke.apk" "$TASK_TEST/aligned.apk"
TASK_SERIAL=emulator-5580
if [[ "$(adb -s "$TASK_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then echo "Tests require emulator-5580." >&2; exit 1; fi
node "$TASK_ROOT/scripts/fake-bridge.mjs" > "$TASK_ROOT/tools/fake-bridge.log" 2>&1 &
TASK_BRIDGE_PID=$!
cleanup() { kill "$TASK_BRIDGE_PID" >/dev/null 2>&1 || true; }
trap cleanup EXIT
for (( task_attempt=0; task_attempt<30; task_attempt++ )); do
  if curl -fSs http://127.0.0.1:8788/status >/dev/null 2>&1; then break; fi
  sleep 0.2
done
kill -0 "$TASK_BRIDGE_PID"
adb -s "$TASK_SERIAL" install -r "$TASK_ROOT/dist/tango-debug.apk"
adb -s "$TASK_SERIAL" install -r "$TASK_TEST/smoke.apk"
adb -s "$TASK_SERIAL" shell pm grant com.tango.phone android.permission.POST_NOTIFICATIONS
adb -s "$TASK_SERIAL" shell pm grant com.tango.phone android.permission.RECORD_AUDIO
(
  sleep 3
  adb -s "$TASK_SERIAL" shell settings delete secure enabled_accessibility_services >/dev/null
  adb -s "$TASK_SERIAL" shell settings put secure enabled_accessibility_services com.tango.phone/com.tango.phone.PhoneAccessibilityService
  adb -s "$TASK_SERIAL" shell settings put secure accessibility_enabled 1
) &
TASK_ENABLE_PID=$!
adb -s "$TASK_SERIAL" shell am instrument -w com.tango.loop/.AgentLoopSmoke | tee "$TASK_ROOT/dist/agent-loop-smoke.txt"
wait "$TASK_ENABLE_PID"
curl -fSs http://127.0.0.1:8788/status > "$TASK_ROOT/dist/fake-bridge-result.json"
if ! grep -q 'INSTRUMENTATION_RESULT: success=true' "$TASK_ROOT/dist/agent-loop-smoke.txt"; then exit 1; fi
adb -s "$TASK_SERIAL" shell settings delete secure enabled_accessibility_services >/dev/null
adb -s "$TASK_SERIAL" shell settings put secure enabled_accessibility_services com.tango.phone/com.tango.phone.PhoneAccessibilityService
adb -s "$TASK_SERIAL" shell settings put secure accessibility_enabled 1
sleep 1
adb -s "$TASK_SERIAL" shell am start -W -n com.tango.phone/.MainActivity >/dev/null
echo "Real agent-loop integration checks passed without paid inference."
