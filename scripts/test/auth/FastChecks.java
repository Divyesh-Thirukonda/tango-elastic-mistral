package com.tango.authsmoke;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/** Matched real Android controls, intercepted OpenAI responses, explicitly simulated provider delay. */
public final class FastChecks {
    private final Instrumentation test;private final Context context;private final JSONObject checks;private final Class<?> agent,plan,choice;
    private final AtomicInteger requests=new AtomicInteger();private String mode;private JSONObject lastObs;private boolean mismatch;
    private FastChecks(Instrumentation t,Context c,JSONObject checks)throws Exception {test=t;context=c;this.checks=checks;ClassLoader l=c.getClassLoader();agent=l.loadClass("com.tango.phone.AgentService");plan=l.loadClass("com.tango.phone.FastPlan");choice=l.loadClass("com.tango.phone.FastDecision");}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static JSONObject selector(JSONObject node)throws Exception {return new JSONObject().put("viewId",node.optString("viewId").isBlank()?JSONObject.NULL:node.optString("viewId")).put("className",node.optString("className")).put("text",JSONObject.NULL).put("description",JSONObject.NULL);}
    private JSONObject step(JSONObject obs,String direction)throws Exception {
        JSONArray nodes=obs.getJSONArray("nodes");JSONObject target=null,anchor=null;
        for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);if(n.optBoolean("scrollable")&&!n.optString("viewId").isBlank())target=n;if(n.optString("text").equals("Search settings"))anchor=n;}
        require(target!=null&&anchor!=null,"Settings fixture lacks unique scroll target/title");
        return new JSONObject().put("type","scroll").put("packageName",obs.getString("packageName")).put("screen",selector(anchor).put("text","Search settings"))
            .put("target",selector(target)).put("text",JSONObject.NULL).put("direction",direction);
    }
    private static FixtureHttp.Response completed(JSONObject value)throws Exception {
        return new FixtureHttp.Response(200,new JSONObject().put("object","response").put("status","completed").put("output",new JSONArray().put(new JSONObject().put("type","message").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",value.toString()))))).toString());
    }
    private FixtureHttp.Response reply(String body)throws Exception {
        JSONObject envelope=new JSONObject(body),task=new JSONObject(envelope.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"));
        if(!task.optString("goal").startsWith("FAST_FIXTURE"))return null;
        requests.incrementAndGet();SystemClock.sleep(300);JSONObject obs=task.getJSONObject("observation");lastObs=obs;try(java.io.FileOutputStream out=context.openFileOutput("fast-observation.json",0)){out.write(obs.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        JSONArray history=task.getJSONArray("history");int executed=0;
        for(int i=0;i<history.length();i++){JSONObject entry=history.optJSONObject(i);if(entry!=null&&entry.has("result")){require(entry.getJSONObject("result").optBoolean("ok"),"Benchmark control failed");executed++;}}
        JSONObject action;
        if(executed>=3)action=new JSONObject().put("type","done").put("summary","Verified three scroll actions.");
        else {JSONObject s=step(obs,executed==1?"up":"down");action=(JSONObject)plan.getMethod("resolve",JSONObject.class,JSONObject.class).invoke(null,s,obs);require(action!=null,"Fixture selector did not resolve");}
        JSONObject result=new JSONObject().put("action",action);
        if(mode.equals("plan")) {
            JSONArray steps=new JSONArray();if(executed==0){JSONObject future=step(obs,"up");if(mismatch)future.getJSONObject("screen").put("text","Unexpected screen title");steps.put(future).put(step(obs,"down"));}
            result.put("plan",steps);require(envelope.getJSONObject("text").getJSONObject("format").optString("name").equals("phone_plan"),"Fast I not using plan wire schema");
        } else if(mode.equals("choice")) {
            result.put("choice",JSONObject.NULL);
            if(!action.optString("type").equals("done")) {
                JSONArray candidates=task.getJSONArray("candidates");int selected=-1;
                for(int i=0;i<candidates.length();i++){JSONObject c=candidates.getJSONObject(i);if(c.optString("type").equals("scroll")&&c.optString("target").equals(action.optString("target"))&&c.optString("direction").equals(action.optString("direction")))selected=i;}
                require(selected>=0,"Fast II omitted matching control");result.put("choice",selected).put("action",JSONObject.NULL);
            }
            require(envelope.getJSONObject("text").getJSONObject("format").optString("name").equals("phone_choice"),"Fast II not using choice wire schema");
        }
        return completed(result);
    }
    private long runOne(String strategy)throws Exception {
        mode=strategy;requests.set(0);context.getSharedPreferences("tango",0).edit().putBoolean("fast_mode",!strategy.equals("normal")).putString("fast_mode_strategy",strategy).commit();
        test.runOnMainSync(()->{try{agent.getMethod("setUserInteracting",boolean.class).invoke(null,false);context.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));}catch(Exception e){throw new RuntimeException(e);}});SystemClock.sleep(600);
        long started=SystemClock.elapsedRealtime();test.runOnMainSync(()->{try{agent.getField("state").set(null,"idle");agent.getField("steps").setInt(null,0);}catch(Exception e){throw new RuntimeException(e);}context.startForegroundService(new Intent().setClassName("com.tango.phone","com.tango.phone.AgentService").setAction("start").putExtra("newThread",true).putExtra("goal","FAST_FIXTURE Scroll down, up, down in Settings, then verify."));});
        long deadline=started+20000;while(SystemClock.elapsedRealtime()<deadline){String state=(String)agent.getField("state").get(null);if(state.equals("done"))break;if(state.equals("waiting"))throw new AssertionError("Fast fixture asked: "+agent.getField("question").get(null));SystemClock.sleep(20);}
        require(agent.getField("state").get(null).equals("done")&&agent.getField("steps").getInt(null)==3,"Matched task not completed");require(requests.get()==(strategy.equals("plan")&&!mismatch?2:4),"Unexpected request count for "+strategy+": "+requests.get());return SystemClock.elapsedRealtime()-started;
    }
    private void guards()throws Exception {
        Method resolve=plan.getMethod("resolve",JSONObject.class,JSONObject.class);JSONObject step=step(lastObs,"down");
        JSONObject changed=new JSONObject(lastObs.toString()).put("packageName","different.app");require(resolve.invoke(null,step,changed)==null,"Plan crossed app boundary");checks.put("fastPlanPackageMismatchReplans",true);
        changed=new JSONObject(lastObs.toString()).put("password",true);require(resolve.invoke(null,step,changed)==null,"Plan bypassed password handoff");checks.put("fastPlanPasswordStops",true);
        changed=new JSONObject(lastObs.toString());JSONArray nodes=changed.getJSONArray("nodes");JSONObject target=null;for(int i=0;i<nodes.length();i++)if(nodes.getJSONObject(i).optBoolean("scrollable"))target=nodes.getJSONObject(i);require(target!=null,"Missing target");nodes.put(new JSONObject(target.toString()).put("id","duplicate"));require(resolve.invoke(null,step,changed)==null,"Ambiguous plan target selected");checks.put("fastPlanAmbiguousTargetReplans",true);
        changed=new JSONObject(lastObs.toString());for(int i=0;i<changed.getJSONArray("nodes").length();i++)changed.getJSONArray("nodes").getJSONObject(i).put("id","fresh:"+i);JSONObject action=(JSONObject)resolve.invoke(null,step,changed);require(action!=null&&action.optString("target").startsWith("fresh:"),"Plan reused snapshot-specific ID");checks.put("fastPlanResolvesFreshSnapshotIds",true);
        JSONObject oversized=new JSONObject().put("action",new JSONObject().put("type","tap")).put("plan",new JSONArray().put(step).put(step).put(step).put(step));require(((JSONArray)plan.getMethod("accept",JSONObject.class).invoke(null,oversized)).length()==0,"Oversized plan accepted");checks.put("fastPlanBoundedToThreeFutureSteps",true);
        try{choice.getMethod("resolve",JSONObject.class,JSONObject.class).invoke(null,new JSONObject().put("choice",99999).put("action",JSONObject.NULL),new JSONObject().put("observation",lastObs));throw new AssertionError("Invalid choice accepted");}catch(java.lang.reflect.InvocationTargetException expected){checks.put("fastChoiceOutOfRangeRejected",true);}
    }
    private void run()throws Exception {
        FixtureHttp.Responder previous=FixtureHttp.responder;FixtureHttp.responder=(url,method,body)->{if(url.getHost().equals("api.openai.com")&&url.getPath().equals("/v1/responses")){FixtureHttp.Response r=reply(body);if(r!=null)return r;}return previous.answer(url,method,body);};
        JSONObject report=new JSONObject().put("provider","intercepted fixtures, no live OpenAI requests").put("simulatedProviderDelayMs",300).put("actionsPerTask",3).put("repetitions",4).put("fastIRequestsPerTask",2).put("fastIIRequestsPerTask",4);
        try {
            JSONArray first=new JSONArray(),second=new JSONArray();for(int i=0;i<4;i++){if(i%2==0){first.put(runOne("plan"));second.put(runOne("choice"));}else{second.put(runOne("choice"));first.put(runOne("plan"));}}
            report.put("fastITotalMs",first).put("fastIITotalMs",second);checks.put("fastModesMatchedNativeTasksPass",true);checks.put("fastIHalvesRequestsForThreeActionTask",true);guards();
            try(java.io.FileOutputStream out=context.openFileOutput("fast-benchmark.json",0)){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            checks.put("fastBenchmark",report);
            mismatch=true;runOne("plan");mismatch=false;checks.put("fastPlanMismatchDropsQueueAndReplans",true);
            mode="plan";requests.set(0);context.getSharedPreferences("tango",0).edit().putBoolean("fast_mode",true).putString("fast_mode_strategy","plan").commit();
            test.runOnMainSync(()->{try{agent.getField("state").set(null,"idle");agent.getField("steps").setInt(null,0);}catch(Exception e){throw new RuntimeException(e);}context.startForegroundService(new Intent().setClassName("com.tango.phone","com.tango.phone.AgentService").setAction("start").putExtra("newThread",true).putExtra("goal","FAST_FIXTURE Stop while future steps are queued."));});
            long until=SystemClock.elapsedRealtime()+8000;while(agent.getField("steps").getInt(null)<1&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(10);
            require(agent.getField("steps").getInt(null)==1,"Stop fixture missed first action");test.runOnMainSync(()->{try{agent.getMethod("stopTask").invoke(agent.getField("instance").get(null));}catch(Exception e){throw new RuntimeException(e);}});SystemClock.sleep(800);
            require(agent.getField("steps").getInt(null)==1&&requests.get()==1&&agent.getField("state").get(null).equals("stopped"),"Queued steps ran after Stop");checks.put("fastPlanStopCancelsQueuedActions",true);
        } finally {FixtureHttp.responder=previous;context.getSharedPreferences("tango",0).edit().putBoolean("fast_mode",false).remove("fast_mode_strategy").commit();test.runOnMainSync(()->context.stopService(new Intent().setClassName("com.tango.phone","com.tango.phone.AgentService")));SystemClock.sleep(300);}
    }
    public static void run(Instrumentation test,Context context,JSONObject checks)throws Exception {new FastChecks(test,context,checks).run();}
}
