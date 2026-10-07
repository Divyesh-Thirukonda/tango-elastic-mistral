package com.tango.authsmoke;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real service recovery tests on a stable Settings screen; no live account or external action. */
public final class AutonomyChecks {
    private final Instrumentation test;private final Context context;private final JSONObject checks;private final Class<?> agent;
    private final AtomicInteger unchanged=new AtomicInteger(),failed=new AtomicInteger(),routine=new AtomicInteger(),essential=new AtomicInteger();
    private final AtomicInteger metadataFact=new AtomicInteger(),legacyUnknown=new AtomicInteger(),budget=new AtomicInteger();
    private final AtomicInteger editRequests=new AtomicInteger(),editPending=new AtomicInteger(),editDelivered=new AtomicInteger();
    private final AtomicInteger passwordAsks=new AtomicInteger(),codeAsks=new AtomicInteger();
    private volatile boolean recoveryContext,failedOutcomes,routineReconsidered;
    private final JSONObject timings=new JSONObject();
    private AutonomyChecks(Instrumentation test,Context context,JSONObject checks)throws Exception{this.test=test;this.context=context;this.checks=checks;agent=context.getClassLoader().loadClass("com.tango.phone.AgentService");}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static JSONObject task(String body)throws Exception{return new JSONObject(new JSONObject(body).getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"));}
    private static FixtureHttp.Response completed(JSONObject action)throws Exception {
        JSONObject response=new JSONObject().put("object","response").put("status","completed").put("output",new JSONArray().put(new JSONObject().put("type","message").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",new JSONObject().put("action",action).toString())))));
        return new FixtureHttp.Response(200,response.toString(),"application/json");
    }
    private static boolean recovery(JSONObject request)throws Exception {
        if(request.has("recovery"))return true;JSONArray history=request.optJSONArray("history");if(history==null)return false;
        for(int i=0;i<history.length();i++){JSONObject entry=history.optJSONObject(i);if(entry!=null&&(entry.has("recovery")||"recovery".equals(entry.optString("type"))))return true;}return false;
    }
    private static boolean failedOutcome(JSONObject request)throws Exception {
        JSONArray history=request.optJSONArray("history");if(history==null)return false;int count=0;
        for(int i=0;i<history.length();i++){JSONObject entry=history.optJSONObject(i),result=entry==null?null:entry.optJSONObject("result");if(result!=null&&Boolean.FALSE.equals(result.opt("ok")))count++;}return count>=3;
    }
    private final AtomicInteger inferredContact=new AtomicInteger(),confirmedContact=new AtomicInteger();
    private boolean replyContextPreserved,repeatReviewed;
    private FixtureHttp.Response reply(JSONObject request)throws Exception {
        String goal=request.optString("goal");JSONObject action;
        if(goal.contains("AUTONOMY_CONTEXT_CONTACT")){
            inferredContact.incrementAndGet();
            action=request.has("clarificationReview")?new JSONObject().put("type","done").put("summary","Fixture chose the supported contact without a user interruption."):
                new JSONObject().put("type","ask").put("question","Is the affectionate contact your girlfriend?").put("blockerKind","required_fact").put("blockerEvidence","One affectionate contact is the plausible match; reconsider available context before asking.");
        }
        else if(goal.contains("AUTONOMY_CONFIRMED_CONTACT")){
            confirmedContact.incrementAndGet();JSONArray answers=request.optJSONArray("clarificationReplies");JSONObject answer=answers==null?null:answers.optJSONObject(0);
            boolean answered=answer!=null&&"Continue".equals(answer.optString("userReply"));
            if(answered){require("Is Alex 🥰💗😘 the contact you meant?".equals(answer.optString("question")),"Continue lost the specific proposed contact");if(!goal.contains("older installed"))require(answer.optString("blockerEvidence").contains("contact"),"Clarification lost its evidence");replyContextPreserved=true;}
            repeatReviewed=answered&&request.has("clarificationReview");
            action=repeatReviewed?new JSONObject().put("type","done").put("summary","Fixture continued with the accepted candidate."):
                new JSONObject().put("type","ask").put("question",answered?"Should I use Alex 🥰💗😘 for this message?":"Is Alex 🥰💗😘 the contact you meant?").put("blockerKind","material_choice").put("blockerEvidence","Fixture contact choice remained unresolved until the user accepted the proposed contact.");
        }
        else if(goal.contains("AUTONOMY_SECURITY_PASSWORD")){passwordAsks.incrementAndGet();action=new JSONObject().put("type","ask").put("question","What is your Cornell password? Reply with it here.").put("blockerKind","security").put("blockerEvidence","The original app requires a password sign-in.");}
        else if(goal.contains("AUTONOMY_SECURITY_CODE")){codeAsks.incrementAndGet();action=new JSONObject().put("type","ask").put("question","Paste the verification code into this reply.").put("blockerKind","security").put("blockerEvidence","The original app is requesting a one-time code.");}
        else if(goal.contains("AUTONOMY_EDIT_DELAY")){int count=editRequests.incrementAndGet();if(count==1){editPending.incrementAndGet();long until=SystemClock.uptimeMillis()+1500;while(SystemClock.uptimeMillis()<until)try{Thread.sleep(50);}catch(InterruptedException ignored){}editDelivered.incrementAndGet();action=new JSONObject().put("type","home").put("reason","Late fixture reply.");}else action=new JSONObject().put("type","done").put("summary","Reobserved after the person finished editing.");}
        else if(goal.contains("AUTONOMY_METADATA_FACT")){metadataFact.incrementAndGet();action=new JSONObject().put("type","ask").put("question","Should I use the existing club name or a new one?").put("reason","Registration needs exactly one official name.").put("blockerKind","required_fact").put("blockerEvidence","The two names are materially different and the official form requires one.");}
        else if(goal.contains("AUTONOMY_LEGACY_UNKNOWN")){legacyUnknown.incrementAndGet();action=new JSONObject().put("type","ask").put("question","Can you clarify this choice?").put("reason","The remaining choice has material consequences.");}
        else if(goal.contains("AUTONOMY_BUDGET")){budget.incrementAndGet();action=new JSONObject().put("type","ask").put("question","Should I retry using a different button?").put("reason","The app remains unchanged.").put("blockerKind","routine").put("blockerEvidence","No navigation approach has progressed.");}
        else if(goal.contains("AUTONOMY_UNCHANGED")){int count=unchanged.incrementAndGet();recoveryContext|=recovery(request);action=count<=8?new JSONObject().put("type","wait").put("durationMs",30).put("reason","Wait briefly and verify the screen."):new JSONObject().put("type","done").put("summary","Completed after observing unchanged screens.");}
        else if(goal.contains("AUTONOMY_FAILED")){int count=failed.incrementAndGet();failedOutcomes|=failedOutcome(request);action=count<=5?new JSONObject().put("type","tap").put("target","missing-fixture-target-"+count).put("reason","Try a different current control."):new JSONObject().put("type","done").put("summary","Finished after considering failed controls.");}
        else if(goal.contains("AUTONOMY_ROUTINE")){int count=routine.incrementAndGet();routineReconsidered|=count>1&&recovery(request);action=count==1?new JSONObject().put("type","ask").put("question","Should I retry using a different button?").put("reason","A tap failed.").put("blockerKind","routine").put("blockerEvidence","Another reversible navigation attempt is available."):new JSONObject().put("type","done").put("summary","Handled the routine retry autonomously.");}
        else if(goal.contains("AUTONOMY_ESSENTIAL")){essential.incrementAndGet();action=goal.contains("Follow-up instruction from user: Board Game Club")?new JSONObject().put("type","done").put("summary","Required club name supplied."):new JSONObject().put("type","ask").put("question","What club name should I register?").put("reason","The registration form requires its name.").put("blockerKind","required_fact").put("blockerEvidence","Club name is required and absent from the user's command.");}
        else return null;
        return completed(action);
    }
    private void start(String goal,String action,String reply)throws Exception {
        test.runOnMainSync(()->context.startActivity(new Intent().setClassName("com.tango.phone","com.tango.phone.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP)));
        test.waitForIdleSync();SystemClock.sleep(300);
        test.runOnMainSync(()->{context.startForegroundService(new Intent().setClassName("com.tango.phone","com.tango.phone.AgentService").setAction(action).putExtra("newThread",action.equals("start")).putExtra("goal",goal).putExtra("reply",reply));context.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));});
        long until=SystemClock.uptimeMillis()+5000;while(SystemClock.uptimeMillis()<until){String current=(String)agent.getField("goal").get(null);if(goal!=null&&current.startsWith(goal)||reply!=null&&current.contains("Follow-up instruction from user: "+reply)){test.runOnMainSync(()->{});test.waitForIdleSync();return;}SystemClock.sleep(30);}throw new AssertionError("Service did not accept fixture task");
    }
    private void waitState(String expected,String label)throws Exception {
        long until=SystemClock.uptimeMillis()+25000;
        while(SystemClock.uptimeMillis()<until){String state=(String)agent.getField("state").get(null);if(expected.equals(state))return;if("waiting".equals(state)&&!expected.equals(state))throw new AssertionError(label+" asked a person: "+agent.getField("question").get(null));SystemClock.sleep(100);}
        throw new AssertionError(label+" did not reach "+expected+": "+agent.getField("state").get(null));
    }
    private void draftGuard()throws Exception {
        Class<?> guard=context.getClassLoader().loadClass("com.tango.phone.ActionGuard");Method block=guard.getMethod("blockReason",String.class,JSONObject.class,JSONObject.class);
        JSONObject observation=new JSONObject().put("width",1000).put("height",1000).put("nodes",new JSONArray().put(new JSONObject().put("id","send-fixture").put("text","Send").put("description","Send email").put("bounds",new JSONArray().put(800).put(50).put(990).put(150))));
        JSONObject byTarget=new JSONObject().put("type","tap").put("target","send-fixture"),byPosition=new JSONObject().put("type","tap").put("target",JSONObject.NULL).put("x",.9).put("y",.1);
        String draft="Draft the board game club invitation and keep it in Drafts. Do not send.";
        require(block.invoke(null,draft,observation,byTarget)!=null&&block.invoke(null,draft,observation,byPosition)!=null,"Greater autonomy bypassed draft send guard");checks.put("autonomyDraftSendTargetAndCoordinatesBlocked",true);
        require(block.invoke(null,draft+"\nFollow-up instruction from user: Send the draft",observation,byTarget)==null,"Explicit later send authorization was ignored");checks.put("autonomyExplicitLaterSendAuthorizationPreserved",true);
    }
    private void run()throws Exception {
        FixtureHttp.Responder previous=FixtureHttp.responder;FixtureHttp.responder=(url,method,body)->{if(url.getHost().equals("api.openai.com")&&url.getPath().equals("/v1/responses")){FixtureHttp.Response handled=reply(task(body));if(handled!=null)return handled;}return previous.answer(url,method,body);};
        try {
            Method observe=context.getClassLoader().loadClass("com.tango.phone.PhoneController").getMethod("observe",Context.class);long[] samples=new long[4];for(int i=0;i<samples.length;i++){long began=SystemClock.elapsedRealtime();observe.invoke(null,context);samples[i]=SystemClock.elapsedRealtime()-began;}java.util.Arrays.sort(samples);timings.put("observeMedianMs",(samples[1]+samples[2])/2);
            long began=SystemClock.elapsedRealtime();
            start("AUTONOMY_UNCHANGED Verify this screen without bothering me over a slow interface.","start",null);waitState("done","Unchanged screen recovery");require(unchanged.get()>=9&&agent.getField("steps").getInt(null)>=8&&recoveryContext,"Unchanged screens did not receive recovery context and continue beyond old threshold");checks.put("autonomyEightUnchangedStepsRecoverWithoutHumanAsk",true);
            timings.put("eightWaitsToDoneMs",SystemClock.elapsedRealtime()-began);began=SystemClock.elapsedRealtime();
            start("AUTONOMY_FAILED Try other navigation approaches when a control fails.","start",null);waitState("done","Failed action recovery");require(failed.get()>=6&&agent.getField("steps").getInt(null)>=5&&failedOutcomes,"Failed controls were not retried with actual failure outcomes");checks.put("autonomyFiveFailedActionsRecoverWithoutHumanAsk",true);
            timings.put("fiveFailedControlsToDoneMs",SystemClock.elapsedRealtime()-began);began=SystemClock.elapsedRealtime();
            start("AUTONOMY_ROUTINE Find the relevant item using reasonable reversible navigation.","start",null);waitState("done","Routine model question");require(routine.get()>=2&&routineReconsidered,"Routine ask did not trigger model reconsideration with recovery context");checks.put("autonomyRoutineModelAskReconsideredWithoutWaiting",true);
            timings.put("routineAskToDoneMs",SystemClock.elapsedRealtime()-began);
            start("AUTONOMY_CONTEXT_CONTACT Text my girlfriend with the requested message using context.","start",null);waitState("done","Contextual contact inference");
            require(inferredContact.get()==2&&!agent.getField("journal").get(null).toString().contains("Needs you"),"A tentative contact question reached the user before internal reconsideration");checks.put("autonomyContextualContactQuestionReconsideredBeforeInterrupting",true);
            start("AUTONOMY_CONFIRMED_CONTACT Text the requested message to the intended contact.","start",null);waitState("waiting","Genuinely unresolved contact choice");
            require(confirmedContact.get()==2,"Contact choice was not reconsidered before handoff");
            String contactThread=(String)agent.getMethod("activeThreadId",Context.class).invoke(null,context);
            JSONObject saved=(JSONObject)agent.getMethod("getThread",Context.class,String.class).invoke(null,context,contactThread);
            require(saved.getJSONObject("pendingClarification").optString("question").contains("Alex"),"Pending choice was not saved with the thread");
            test.runOnMainSync(()->context.stopService(new Intent().setClassName("com.tango.phone","com.tango.phone.AgentService")));SystemClock.sleep(400);
            start(null,"resume","");waitState("done","Continue to a proposed contact");
            require(replyContextPreserved&&repeatReviewed&&confirmedContact.get()==4,"Rephrased contact confirmation was not resolved using the saved answer");
            JSONArray journal=(JSONArray)agent.getField("journal").get(null);int questions=0;for(int i=0;i<journal.length();i++)if("Needs you".equals(journal.getJSONObject(i).optString("who")))questions++;
            require(questions==1,"Continue produced a repeated visible confirmation");checks.put("autonomyContinueBindsPersistedQuestionAcrossServiceRestart",true);checks.put("autonomyRephrasedConfirmationReconsideredWithoutRepeatedHandoff",true);
            confirmedContact.set(0);replyContextPreserved=false;repeatReviewed=false;
            start("AUTONOMY_CONFIRMED_CONTACT Continue an older installed conversation.","start",null);waitState("waiting","Legacy pending contact");
            String legacyThread=(String)agent.getMethod("activeThreadId",Context.class).invoke(null,context);
            JSONObject legacy=(JSONObject)agent.getMethod("getThread",Context.class,String.class).invoke(null,context,legacyThread);JSONObject snapshot=new JSONObject(legacy.toString());snapshot.remove("pendingClarification");snapshot.remove("clarificationReplies");
            Method storeMethod=agent.getDeclaredMethod("store",Context.class);storeMethod.setAccessible(true);Object threadStore=storeMethod.invoke(null,context);threadStore.getClass().getMethod("save",String.class,String.class,JSONObject.class).invoke(threadStore,snapshot.getString("taskAccount"),legacyThread,snapshot);
            test.runOnMainSync(()->context.stopService(new Intent().setClassName("com.tango.phone","com.tango.phone.AgentService")));SystemClock.sleep(400);
            start(null,"resume","");waitState("done","Legacy Continue migration");require(replyContextPreserved&&repeatReviewed,"Old saved question was not bound to Continue after upgrade");checks.put("autonomyLegacyPendingQuestionMigratesOnContinue",true);
            start("AUTONOMY_ESSENTIAL Register the club using the required club name.","start",null);waitState("waiting","Missing required club name");require("What club name should I register?".equals(agent.getField("question").get(null)),"Essential missing fact was not shown to the user");SystemClock.sleep(700);require(essential.get()==2,"Agent continued model requests while awaiting a required fact");checks.put("autonomyMissingRequiredFactWaitsForHuman",true);
            start(null,"resume","Board Game Club");waitState("done","Required fact resume");require(essential.get()==3,"Required fact resume did not reach the provider");checks.put("autonomyRequiredFactResumeCompletes",true);
            start("AUTONOMY_METADATA_FACT Register the club with a valid official name.","start",null);waitState("waiting","Required fact metadata");require(metadataFact.get()==2&&"Should I use the existing club name or a new one?".equals(agent.getField("question").get(null)),"Required fact metadata was overridden by routine phrasing");checks.put("autonomyRequiredFactMetadataOverridesRoutinePhrasing",true);
            start("AUTONOMY_LEGACY_UNKNOWN Complete the task with accurate choices.","start",null);waitState("waiting","Unknown legacy ask");require(legacyUnknown.get()==2&&"Can you clarify this choice?".equals(agent.getField("question").get(null)),"Unclassified legacy question was suppressed");checks.put("autonomyUnknownLegacyAskConservativelyWaits",true);
            start("AUTONOMY_BUDGET Try available reversible navigation approaches.","start",null);waitState("waiting","Bounded autonomous reconsideration");require(budget.get()>1&&budget.get()<=16,"Recovery either asked immediately or kept retrying without a finite budget");checks.put("autonomyRoutineReconsiderationHasFiniteBudget",true);
            start("AUTONOMY_EDIT_DELAY Keep Settings open while I edit my instruction.","start",null);long until=SystemClock.uptimeMillis()+8000;while(editPending.get()==0&&SystemClock.uptimeMillis()<until)SystemClock.sleep(50);require(editPending.get()==1,"Editing fixture never started inference");test.runOnMainSync(()->{try{agent.getMethod("setUserInteracting",boolean.class).invoke(null,true);}catch(Exception e){throw new RuntimeException(e);}});until=SystemClock.uptimeMillis()+8000;while(editDelivered.get()==0&&SystemClock.uptimeMillis()<until)SystemClock.sleep(50);SystemClock.sleep(500);
            require(editDelivered.get()==1&&agent.getField("steps").getInt(null)==0&&editRequests.get()==1,"Human editing did not hold/drop the late model action");checks.put("autonomyHumanEditingDropsDelayedAction",true);
            test.runOnMainSync(()->{try{agent.getMethod("setUserInteracting",boolean.class).invoke(null,false);}catch(Exception e){throw new RuntimeException(e);}});waitState("done","Fresh decision after human input");JSONObject observation=(JSONObject)context.getClassLoader().loadClass("com.tango.phone.PhoneController").getMethod("observe",Context.class).invoke(null,context);require(editRequests.get()==2&&agent.getField("steps").getInt(null)==0&&observation.optString("packageName").contains("settings"),"Held action replayed after editing instead of observing/replanning");checks.put("autonomyReleasedInputGetsFreshDecisionWithoutReplayingAction",true);
            for(String marker:new String[]{"AUTONOMY_SECURITY_PASSWORD","AUTONOMY_SECURITY_CODE"}){start(marker+" Sign in through the original app.","start",null);waitState("waiting","Canonical security handoff");String handoff=((String)agent.getField("question").get(null)).toLowerCase(java.util.Locale.ROOT);require(handoff.matches("(?s).*(yourself|directly|in the app).*" )&&!handoff.contains("reply with")&&!handoff.contains("paste the verification")&&!handoff.contains("what is your")&&agent.getField("steps").getInt(null)==0,"Security prompt solicited a trusted secret or executed an action: "+handoff);checks.put(marker.equals("AUTONOMY_SECURITY_PASSWORD")?"autonomyPasswordAskUsesCanonicalInAppHandoff":"autonomyVerificationCodeAskUsesCanonicalInAppHandoff",true);}
            draftGuard();checks.put("offlineAutonomyInferenceRequests",unchanged.get()+failed.get()+routine.get()+essential.get()+metadataFact.get()+legacyUnknown.get()+budget.get()+editRequests.get()+passwordAsks.get()+codeAsks.get()+inferredContact.get()+confirmedContact.get()+4);checks.put("autonomyTimingsMs",timings);
        } finally {FixtureHttp.responder=previous;test.runOnMainSync(()->context.stopService(new Intent().setClassName("com.tango.phone","com.tango.phone.AgentService")));SystemClock.sleep(300);}
    }
    public static void run(Instrumentation test,Context context,JSONObject checks)throws Exception{new AutonomyChecks(test,context,checks).run();}
}
