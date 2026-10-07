package com.tango.authsmoke;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs inside Tango's process against real services and Android Keystore. No live account required. */
public final class AuthSmoke extends Instrumentation {
    private Context context;
    private ClassLoader loader;
    private Class<?> agent,secrets;
    private Object auth;
    private Activity main;
    private final JSONObject checks=new JSONObject();
    private final Bundle result=new Bundle();
    private boolean observeOnly;
    private boolean uiOnly,voiceOnly;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments);observeOnly=arguments!=null&&"true".equals(arguments.getString("observeOnly"));uiOnly=arguments!=null&&"true".equals(arguments.getString("uiOnly"));voiceOnly=arguments!=null&&"true".equals(arguments.getString("voiceOnly"));start(); }
    private void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private Object field(Object owner,String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private void setField(Object owner,String name,Object value) throws Exception {
        Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner,value);
    }
    private Object call(Object owner,String name,Class<?>[] types,Object... args) throws Exception {
        Method m=owner.getClass().getDeclaredMethod(name,types); m.setAccessible(true);
        try { return m.invoke(owner,args); }
        catch(InvocationTargetException e) { if(e.getCause() instanceof Exception)throw (Exception)e.getCause(); throw new RuntimeException(e.getCause()); }
    }
    private void mainCall(String name,Class<?>[] types,Object... args) throws Exception {
        final Exception[] error={null}; runOnMainSync(()->{try{call(main,name,types,args);}catch(Exception e){error[0]=e;}});
        if(error[0]!=null)throw error[0]; waitForIdleSync(); SystemClock.sleep(250);
    }
    private String state() throws Exception { return (String)agent.getField("state").get(null); }
    private void secret(String name,String value) throws Exception { secrets.getMethod("put",Context.class,String.class,String.class).invoke(null,context,name,value); }
    private String secret(String name) throws Exception { return (String)secrets.getMethod("get",Context.class,String.class).invoke(null,context,name); }
    private Intent entry(String action,boolean listen) {
        return new Intent(action).setClassName("com.tango.phone","com.tango.phone.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("listen",listen);
    }
    private void entryIntent(String action,boolean listen) throws Exception {
        runOnMainSync(()->context.startActivity(entry(action,listen))); waitForIdleSync(); SystemClock.sleep(650);
        require((boolean)field(main,"authVisible"),"Signed-out trigger escaped the sign-in screen: "+action);
        require(field(main,"recognizer")==null && !(boolean)field(main,"listening"),"Signed-out trigger activated microphone: "+action);
    }
    private String visibleText(View view) {
        StringBuilder text=new StringBuilder();
        if(view.getVisibility()!=View.VISIBLE)return "";
        if(view instanceof TextView)text.append(((TextView)view).getText()).append('\n');
        if(view instanceof ViewGroup) {ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)text.append(visibleText(g.getChildAt(i)));}
        return text.toString();
    }
    private void serviceIntent(String action) throws Exception {
        entryIntent(Intent.ACTION_MAIN,false);
        runOnMainSync(()->context.startForegroundService(new Intent().setClassName("com.tango.phone","com.tango.phone.AgentService")
            .setAction(action).putExtra("goal","Offline unauthorized test").putExtra("reply","Continue")));
        SystemClock.sleep(1000);
        require(!state().equals("running"),"Signed-out service "+action+" entered running");
        require(agent.getField("steps").getInt(null)==0,"Signed-out service dispatched an action");
    }
    private void signedOut() throws Exception {
        secret("bridge_token","legacy-test-token"); secret("api_key","legacy-test-key");
        context.getSharedPreferences("tango",0).edit().putString("provider","api").putBoolean("configured",true).commit();
        main=startActivitySync(entry(Intent.ACTION_MAIN,false)); waitForIdleSync(); SystemClock.sleep(700);
        require((boolean)field(main,"authVisible"),"First launch did not require sign-in");
        final String[] text={""};runOnMainSync(()->text[0]=visibleText(main.getWindow().getDecorView()));
        require(text[0].contains("Continue with ChatGPT"),"First screen is missing official login control");
        require(!text[0].contains("Run command"),"Signed-out first screen exposes command controls");
        require(secret("api_key").isEmpty()&&secret("bridge_token").isEmpty(),"Legacy credentials survived migration");
        checks.put("loginFirstScreen",true);checks.put("legacyCredentialsCannotBypassLogin",true);
        entryIntent(Intent.ACTION_MAIN,true);checks.put("shortcutIntentMicGated",true);
        entryIntent(Intent.ACTION_ASSIST,false);checks.put("assistantIntentMicGated",true);
        entryIntent("android.intent.action.VOICE_ASSIST",false);checks.put("voiceAssistIntentMicGated",true);
        mainCall("runCommand",new Class[]{String.class},"Offline typed test");
        require(!state().equals("running"),"Typed command ran signed out");checks.put("typedCommandGated",true);
        mainCall("listen",new Class[]{});require(field(main,"recognizer")==null,"Direct listen activated signed out");checks.put("microphoneMethodGated",true);
        mainCall("continueTask",new Class[]{});require(!state().equals("running"),"Continue ran signed out");checks.put("continueControlGated",true);
        mainCall("sendService",new Class[]{String.class,String.class},"steer","Do a task");require(!state().equals("running"),"Steering control ran signed out");checks.put("steeringControlGated",true);
        serviceIntent("start");checks.put("serviceStartGated",true);
        serviceIntent("resume");checks.put("serviceResumeGated",true);
        serviceIntent("steer");checks.put("serviceSteerGated",true);
        context.stopService(new Intent().setClassName("com.tango.phone","com.tango.phone.AgentService"));SystemClock.sleep(300);
        JSONObject recovered=new JSONObject().put("state","running").put("goal","Recovered test").put("taskAccount","oaiapp_fixture")
            .put("steps",0).put("sessionId","fixture").put("journal",new JSONArray()).put("history",new JSONArray().put(new JSONObject().put("userReply","saved fixture history")));
        try(java.io.OutputStream out=context.openFileOutput("task.json",0)){out.write(recovered.toString().getBytes(StandardCharsets.UTF_8));}
        serviceIntent("resume");checks.put("restoredTaskGated",true);
        try(java.io.InputStream in=context.openFileInput("task.json")){
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] bytes=new byte[1024];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
            JSONObject persisted=new JSONObject(out.toString("UTF-8"));
            require("oaiapp_fixture".equals(persisted.optString("taskAccount"))&&persisted.optJSONArray("history")!=null&&persisted.getJSONArray("history").toString().contains("saved fixture history"),"Blocked restoration erased saved task account/history");
        }
        checks.put("blockedRestorePreservesTaskAccountAndHistory",true);
        // All launchers converge on this exact shortcut intent before microphone/commands are available.
        Class<?> controller=loader.loadClass("com.tango.phone.PhoneController");
        Method shortcutMethod=controller.getDeclaredMethod("listenIntent",Context.class);shortcutMethod.setAccessible(true);
        Intent shortcut=(Intent)shortcutMethod.invoke(null,context);
        runOnMainSync(()->context.startActivity(shortcut));waitForIdleSync();SystemClock.sleep(650);
        require((boolean)field(main,"authVisible")&&field(main,"recognizer")==null,"Shared tile/assistant shortcut bypassed gate");checks.put("tileSharedIntentGated",true);
        Class<?> access=loader.loadClass("com.tango.phone.PhoneAccessibilityService");
        long until=SystemClock.uptimeMillis()+12000;while(access.getField("instance").get(null)==null&&SystemClock.uptimeMillis()<until)SystemClock.sleep(100);
        Object service=access.getField("instance").get(null);require(service!=null,"Accessibility service missing for volume handler check");
        Method keys=access.getDeclaredMethod("onKeyEvent",KeyEvent.class);keys.setAccessible(true);
        final Exception[] keyError={null};runOnMainSync(()->{try{
            keys.invoke(service,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_DOWN));
            keys.invoke(service,new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_VOLUME_DOWN));
            keys.invoke(service,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_DOWN));
            keys.invoke(service,new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_VOLUME_DOWN));
        }catch(Exception e){keyError[0]=e;}});if(keyError[0]!=null)throw keyError[0];waitForIdleSync();SystemClock.sleep(650);
        require((boolean)field(main,"authVisible")&&field(main,"recognizer")==null,"Double-volume handler bypassed login gate");checks.put("volumeHandlerMicGated",true);
        require(FixtureHttp.calls.isEmpty(),"Signed-out flows attempted provider HTTPS requests");checks.put("signedOutZeroProviderRequests",true);
    }
    @Override public void onStart() {
        if(observeOnly){observationBenchmark();return;}
        if(uiOnly||voiceOnly){uiOnlyChecks();return;}
        try {
            FixtureHttp.install();context=getTargetContext();loader=context.getClassLoader();
            agent=loader.loadClass("com.tango.phone.AgentService");secrets=loader.loadClass("com.tango.phone.SecretStore");
            auth=loader.loadClass("com.tango.phone.ChatGptAuth").getMethod("get",Context.class).invoke(null,context);
            ProtocolChecks protocol=new ProtocolChecks(loader,checks);protocol.run();
            signedOut();
            UiStateChecks.signedOut(this,context,main,checks);
            // LifecycleChecks exercises real OAuth exchanges and encrypted session refresh using fixtures.
            LifecycleChecks.run(this,context,auth,main,protocol,checks);
            result.putString("success","true");
        } catch(Throwable error) {
            result.putString("success","false");result.putString("error",error.toString());
            java.io.StringWriter stack=new java.io.StringWriter();error.printStackTrace(new java.io.PrintWriter(stack));result.putString("stack",stack.toString());
        } finally {
            try {if(context!=null){runOnMainSync(()->context.stopService(new Intent().setClassName("com.tango.phone","com.tango.phone.AgentService")));context.deleteFile("task.json");}}
            catch(Throwable cleanup){result.putString("cleanupError",cleanup.toString());}
        }
        result.putString("checks",checks.toString());finish("true".equals(result.getString("success"))?0:1,result);
    }
    /** Fast layout iteration with an in-memory test identity; no live sign-in or inference. */
    private void uiOnlyChecks(){
        Object previous=null;boolean captured=false;
        try{
            FixtureHttp.install();context=getTargetContext();loader=context.getClassLoader();agent=loader.loadClass("com.tango.phone.AgentService");
            auth=loader.loadClass("com.tango.phone.ChatGptAuth").getMethod("get",Context.class).invoke(null,context);previous=field(auth,"current");captured=true;
            Class<?> session=loader.loadClass("com.tango.phone.ChatGptAuth$Session");java.lang.reflect.Constructor<?> constructor=session.getDeclaredConstructors()[0];constructor.setAccessible(true);
            Object fixture=constructor.newInstance("oaiapp_fixture","ui-fixture-user","tango@example.test","Tango Test","","fixture-access-token","","openid profile email resource.invoke chatgpt.tokens.use.direct",System.currentTimeMillis()/1000+3600,0L,"");setField(auth,"current",fixture);setField(auth,"lastError","");
            FixtureHttp.responder=(url,method,body)->{throw new AssertionError("UI-only checks must not call provider HTTPS: "+url.getHost()+url.getPath());};
            context.getSharedPreferences("tango",0).edit().putBoolean("configured",true).putBoolean("welcome_oaiapp_fixture",true).putBoolean("listen_on_launch",false).commit();
            main=startActivitySync(entry(Intent.ACTION_MAIN,false));waitForIdleSync();if(voiceOnly)VoiceChecks.runControls(this,context,main,checks);else UiStateChecks.run(this,context,main,checks);require(FixtureHttp.calls.isEmpty(),"UI-only layout checks attempted provider requests");checks.put(voiceOnly?"voiceOnlyZeroProviderRequests":"uiOnlyZeroProviderRequests",true);result.putString("success","true");
        }catch(Throwable error){result.putString("success","false");result.putString("error",error.toString());java.io.StringWriter stack=new java.io.StringWriter();error.printStackTrace(new java.io.PrintWriter(stack));result.putString("stack",stack.toString());}
        finally{try{if(context!=null){runOnMainSync(()->context.stopService(new Intent().setClassName("com.tango.phone","com.tango.phone.AgentService")));context.deleteFile("task.json");}if(captured)setField(auth,"current",previous);}catch(Throwable error){result.putString("cleanupError",error.toString());}}
        result.putString("checks",checks.toString());finish("true".equals(result.getString("success"))?0:1,result);
    }
    private void observationBenchmark(){try{Context context=getTargetContext();ClassLoader loader=context.getClassLoader();Class<?> service=loader.loadClass("com.tango.phone.PhoneAccessibilityService");long until=SystemClock.uptimeMillis()+12000;while(service.getField("instance").get(null)==null&&SystemClock.uptimeMillis()<until)SystemClock.sleep(50);require(service.getField("instance").get(null)!=null,"Benchmark accessibility absent");Method observe=loader.loadClass("com.tango.phone.PhoneController").getMethod("observe",Context.class);JSONArray samples=new JSONArray();int captured=0,limited=0,errors=0;for(int i=0;i<4;i++){long began=SystemClock.elapsedRealtime();JSONObject observation=(JSONObject)observe.invoke(null,context);boolean image=!observation.optString("screenshot").isEmpty();String error=observation.optString("screenshotError")+observation.optString("observationError");if(image)captured++;String normalized=error.toLowerCase(java.util.Locale.ROOT);if(normalized.contains("interval")||normalized.contains("rate")||normalized.contains("android screenshot error 3"))limited++;if(!image)errors++;samples.put(new JSONObject().put("elapsedMs",SystemClock.elapsedRealtime()-began).put("screenshotCaptured",image).put("error",error.isEmpty()?"":error.substring(0,Math.min(250,error.length()))));}result.putString("success","true");result.putString("observationBenchmark",new JSONObject().put("samples",samples).put("captured",captured).put("rateLimited",limited).put("missingImages",errors).put("liveInferenceRequests",0).toString());}catch(Exception error){result.putString("success","false");result.putString("error",error.toString());}finish("true".equals(result.getString("success"))?0:1,result);}
}
