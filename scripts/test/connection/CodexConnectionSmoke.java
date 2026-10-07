package com.tango.authsmoke;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.EditText;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real loopback connector tests inside Tango. All HTTPS is intercepted by fixtures. */
public final class CodexConnectionSmoke extends Instrumentation {
    private Context context;
    private Class<?> connection,agent;
    private Object auth;
    private Object originalAccount;
    private final JSONObject checks=new JSONObject();
    private final AtomicInteger inference=new AtomicInteger();
    private final AtomicInteger delayedPending=new AtomicInteger(),delayedDelivered=new AtomicInteger();
    private Activity activity;
    private String token,thread;
    @Override public void onCreate(Bundle args) {super.onCreate(args);start();}
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    private Object call(Class<?> type,String name,Class<?>[] args,Object... values)throws Exception {
        try{return type.getMethod(name,args).invoke(null,values);}
        catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw(Exception)e.getCause();throw new RuntimeException(e.getCause());}
    }
    private interface Checked {void run()throws Exception;}
    private void main(Checked work)throws Exception {final Exception[] error={null};runOnMainSync(()->{try{work.run();}catch(Exception e){error[0]=e;}});if(error[0]!=null)throw error[0];waitForIdleSync();}
    private void account(Object value)throws Exception {Field current=auth.getClass().getDeclaredField("current");current.setAccessible(true);current.set(auth,value);}
    private Object fixture(String client)throws Exception {
        Class<?> session=context.getClassLoader().loadClass("com.tango.phone.ChatGptAuth$Session");
        Constructor<?> constructor=session.getDeclaredConstructors()[0];constructor.setAccessible(true);
        return constructor.newInstance(client,"test-user","tango@example.test","Tango Test","","fixture-access-token","", 
            "openid profile email resource.invoke chatgpt.tokens.use.direct",System.currentTimeMillis()/1000+3600,0L,"");
    }
    private static final class Reply {
        final int status;final JSONObject body;
        Reply(int status,JSONObject body){this.status=status;this.body=body;}
    }
    private Reply request(String method,String path,String authToken,JSONObject body,String extra)throws Exception {
        byte[] payload=body==null?new byte[0]:body.toString().getBytes(StandardCharsets.UTF_8);
        try(Socket socket=new Socket("127.0.0.1",8766)) {
            socket.setSoTimeout(15000);
            String head=method+" "+path+" HTTP/1.1\r\nHost: 127.0.0.1:8766\r\n"+
                (authToken==null?"":"Authorization: Bearer "+authToken+"\r\n")+
                (body==null?"":"Content-Type: application/json\r\nContent-Length: "+payload.length+"\r\n")+extra+"\r\n";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(payload);socket.getOutputStream().flush();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            while((n=socket.getInputStream().read(buffer))!=-1){require(bytes.size()+n<5000000,"Response too large");bytes.write(buffer,0,n);}
            String raw=bytes.toString(StandardCharsets.UTF_8.name());int split=raw.indexOf("\r\n\r\n");require(split>0,"HTTP response missing headers");
            return new Reply(Integer.parseInt(raw.substring(0,raw.indexOf("\r\n")).split(" ")[1]),new JSONObject(raw.substring(split+4)));
        }
    }
    private Reply get(String path)throws Exception{return request("GET",path,token,null,"");}
    private Reply post(String path,JSONObject body)throws Exception{return request("POST",path,token,body,"");}
    private JSONObject info()throws Exception{return(JSONObject)call(connection,"info",new Class[]{Context.class},context);}
    private String state()throws Exception{return(String)agent.getField("state").get(null);}
    private void waitState(String value)throws Exception {
        long until=SystemClock.uptimeMillis()+15000;
        while(SystemClock.uptimeMillis()<until){if(state().equals(value))return;SystemClock.sleep(100);}
        throw new AssertionError("Expected "+value+", got "+state()+": "+agent.getField("status").get(null));
    }
    private void connect()throws Exception {main(()->call(connection,"connect",new Class[]{Context.class},context));long until=SystemClock.uptimeMillis()+3000;while(!info().optBoolean("listening")&&SystemClock.uptimeMillis()<until)SystemClock.sleep(50);require(info().optBoolean("listening"),"Connector did not listen");token=info().getString("token");}
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            context=getTargetContext();connection=context.getClassLoader().loadClass("com.tango.phone.CodexConnection");agent=context.getClassLoader().loadClass("com.tango.phone.AgentService");
            Class<?> authType=context.getClassLoader().loadClass("com.tango.phone.ChatGptAuth");auth=call(authType,"get",new Class[]{Context.class},context);
            Field current=authType.getDeclaredField("current");current.setAccessible(true);originalAccount=current.get(auth);
            FixtureHttp.install();
            FixtureHttp.responder=(url,method,body)-> {
                require(url.getHost().equals("api.openai.com"),"Unexpected HTTPS host: "+url.getHost());
                if(url.getPath().equals("/v1/models"))return new FixtureHttp.Response(200,new JSONObject().put("models",new JSONArray().put(new JSONObject().put("slug","fixture-model").put("display_name","Fixture model").put("visibility","list"))).toString());
                require(url.getPath().equals("/v1/responses"),"Unexpected HTTPS path");inference.incrementAndGet();
                JSONObject action=body.contains("HOLD_FOR_PAUSE")?new JSONObject().put("type","ask").put("question","Which test screen should I open?"):new JSONObject().put("type","done").put("summary","Connector fixture task complete.");
                if(body.contains("Finish the fixture"))action=new JSONObject().put("type","done").put("summary","Connector fixture task complete.");
                if(body.contains("WAIT_FOR_REMOTE_STOP")) {
                    delayedPending.incrementAndGet();long until=SystemClock.uptimeMillis()+1800;
                    while(SystemClock.uptimeMillis()<until)try{Thread.sleep(75);}catch(InterruptedException ignored){}
                    delayedDelivered.incrementAndGet();action=new JSONObject().put("type","home").put("reason","Delayed fixture action");
                }
                return new FixtureHttp.Response(200,new JSONObject().put("object","response").put("status","completed").put("output",new JSONArray().put(new JSONObject().put("type","message").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",new JSONObject().put("action",action).toString()))))).toString());
            };
            account(null);
            try {call(connection,"connect",new Class[]{Context.class},context);throw new AssertionError("Signed-out connection enabled");}
            catch(IllegalStateException expected){checks.put("signedOutCannotConnect",true);}
            Object fixture=fixture("oaiapp_connector_fixture");account(fixture);
            context.getSharedPreferences("tango",0).edit().putBoolean("configured",true).putBoolean("welcome_oaiapp_connector_fixture",true).putBoolean("listen_on_launch",false).putBoolean("fast_mode",false).commit();
            activity=startActivitySync(new Intent().setClassName("com.tango.phone","com.tango.phone.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            connect();require(token.matches("[A-Za-z0-9_-]{43}"),"Pairing token entropy/format incorrect");
            require(request("GET","/v1/status",null,null,"").status==401,"Anonymous status accepted");
            require(request("GET","/v1/status","a".repeat(43),null,"").status==401,"Wrong token accepted");
            require(request("GET","/v1/status",token,null,"Origin: https://example.com\r\n").status==403,"Browser origin accepted");
            checks.put("realLoopbackTokenAndOriginChecks",true);
            Reply status=get("/v1/status");require(status.status==200&&status.body.optBoolean("connected"),"Authenticated status unavailable");
            String outgoingThread=status.body.getString("threadId");
            require(get("/v1/threads").status==200,"Thread listing unavailable");
            require(get("/v1/threads/missing").status==404,"Unknown thread wasn't rejected");
            Class<?> accessibility=context.getClassLoader().loadClass("com.tango.phone.PhoneAccessibilityService");
            long accessibilityUntil=SystemClock.uptimeMillis()+15000;
            while(accessibility.getField("instance").get(null)==null&&SystemClock.uptimeMillis()<accessibilityUntil)SystemClock.sleep(100);
            require(accessibility.getField("instance").get(null)!=null,"Test accessibility service did not attach after instrumentation restart");
            int before=inference.get();Reply observation=get("/v1/observe");require(observation.status==200&&observation.body.optJSONObject("observation")!=null,"Phone observation unavailable");require(inference.get()==before,"Observation used model inference");checks.put("statusThreadsAndObservationUseNoInference",true);
            main(()->{Field composer=activity.getClass().getDeclaredField("command");composer.setAccessible(true);((EditText)composer.get(activity)).setText("KEEP_OUTGOING_TYPED_DRAFT");});
            Reply prompt=post("/v1/prompts",new JSONObject().put("prompt","CONNECTOR_FIXTURE Check the current Android screen.").put("mode","high"));
            require(prompt.status==200&&prompt.body.optBoolean("accepted"),"Remote prompt rejected");thread=prompt.body.getString("threadId");waitState("done");
            require(inference.get()>0,"Prompt didn't run through fixture inference");
            Reply detail=get("/v1/threads/"+thread);require(detail.status==200&&detail.body.getJSONObject("thread").getJSONArray("journal").toString().contains("CONNECTOR_FIXTURE"),"Prompt didn't persist thread journal");checks.put("remotePromptForegroundHandoffAndDurableJournal",true);
            main(()->call(agent,"selectThread",new Class[]{Context.class,String.class},context,outgoingThread));
            require("KEEP_OUTGOING_TYPED_DRAFT".equals(call(agent,"threadDraft",new Class[]{Context.class},context)),"Remote prompt lost the foreground outgoing draft");
            main(()->call(agent,"selectThread",new Class[]{Context.class,String.class},context,thread));
            require(!"KEEP_OUTGOING_TYPED_DRAFT".equals(call(agent,"threadDraft",new Class[]{Context.class},context)),"Remote prompt copied the outgoing draft onto its new thread");checks.put("remoteNewThreadPreservesOutgoingDraft",true);
            require(post("/v1/steer",new JSONObject().put("threadId",thread).put("reply","HOLD_FOR_PAUSE")).status==200,"Remote steer rejected");waitState("waiting");
            require(post("/v1/prompts",new JSONObject().put("threadId",thread).put("prompt","Follow up with more checks")).status==200,"Same-thread follow-up rejected");waitState("waiting");
            require(post("/v1/pause",new JSONObject().put("threadId",thread)).status==200,"Remote pause rejected");waitState("paused");
            require(post("/v1/respond",new JSONObject().put("threadId",thread).put("reply","Finish the fixture")).status==200,"Remote response rejected");waitState("done");
            require(post("/v1/respond",new JSONObject().put("threadId",thread).put("reply","Continue")).status==409,"Response resumed a completed thread");
            require(post("/v1/stop",new JSONObject().put("threadId",thread)).status==200,"Remote stop rejected");waitState("stopped");checks.put("remoteSteerFollowUpPauseResponseStop",true);
            main(()->call(agent,"newThread",new Class[]{Context.class},context));
            require(post("/v1/stop",new JSONObject().put("threadId",thread)).status==409,"Stale client stopped a different thread");checks.put("staleControlsCannotAffectAnotherThread",true);
            Reply delayed=post("/v1/prompts",new JSONObject().put("prompt","WAIT_FOR_REMOTE_STOP"));require(delayed.status==200,"Delayed cancellation prompt rejected");thread=delayed.body.getString("threadId");
            long pendingUntil=SystemClock.uptimeMillis()+15000;while(delayedPending.get()==0&&SystemClock.uptimeMillis()<pendingUntil)SystemClock.sleep(50);require(delayedPending.get()>0,"Delayed fixture request did not start");
            require(post("/v1/prompts",new JSONObject().put("prompt","Different thread")).status==409,"New remote task silently replaced a running thread");
            int originalSteps=agent.getField("steps").getInt(null);require(post("/v1/stop",new JSONObject().put("threadId",thread)).status==200,"Delayed request stop rejected");waitState("stopped");
            long deliveredUntil=SystemClock.uptimeMillis()+4000;while(delayedDelivered.get()==0&&SystemClock.uptimeMillis()<deliveredUntil)SystemClock.sleep(50);SystemClock.sleep(250);
            require(delayedDelivered.get()>0&&state().equals("stopped")&&agent.getField("steps").getInt(null)==originalSteps,"Late model response dispatched after remote stop");checks.put("remoteStopCancelsLateActionsAndRunningThreadConflict",true);
            String previousToken=token;
            account(fixture("oaiapp_connector_other"));
            try {require(get("/v1/status").status==403,"Account switch kept prior bridge access");}
            catch(java.net.ConnectException expected) { /* Monitor may have already revoked the connection. */ }
            require(!info().optBoolean("connected"),"Account switch failed to revoke connection");checks.put("accountChangeRevokesAccess",true);
            account(fixture);main(()->context.startActivity(new Intent().setClassName("com.tango.phone","com.tango.phone.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));SystemClock.sleep(300);connect();
            require(!previousToken.equals(token),"Reconnect reused a revoked token");
            require(request("GET","/v1/status",previousToken,null,"").status==401,"Revoked token remained usable");require(get("/v1/status").status==200,"New token unusable");checks.put("reconnectRotatesToken",true);
            main(()->call(connection,"disconnect",new Class[]{Context.class},context));require(!info().optBoolean("connected"),"Disconnect failed");
            try {get("/v1/status");throw new AssertionError("Listener survived disconnect");}
            catch(java.net.ConnectException expected){checks.put("disconnectClosesListener",true);}
            result.putBoolean("success",true);result.putString("checks",checks.toString());
        } catch(Throwable error) {result.putBoolean("success",false);result.putString("failure",android.util.Log.getStackTraceString(error));}
        finally {
            try {main(()->{call(connection,"disconnect",new Class[]{Context.class},context);context.stopService(new Intent().setClassName("com.tango.phone","com.tango.phone.AgentService"));});account(originalAccount);}catch(Exception ignored){}
        }
        finish(result.getBoolean("success")?0:1,result);
    }
}
