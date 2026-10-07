package com.tango.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** All actions are serial and gated by a generation: a stopped request cannot later tap the phone. */
public class AgentService extends Service {
    private static final int MAX_RUN_STEPS=500, MAX_RUN_DECISIONS=650, MAX_ROUTINE_ASKS=6;
    private static final long MAX_RUN_TIME=30*60*1000L;
    public static volatile AgentService instance;
    public static volatile String state="idle", status="Ready when you are.", goal="", question="";
    public static volatile int steps;
    public static volatile JSONArray journal=new JSONArray();
    private static ThreadStore threadStore;
    private static String loadedAccount=null, activeThread="";
    private static volatile boolean userInteracting;
    private static final Object INPUT_LOCK=new Object();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    // Tickets stay unique when a finished service is recreated for a follow-up conversation.
    private static final AtomicInteger GENERATION=new AtomicInteger();
    private final AtomicInteger generation=GENERATION;
    private final AgentOverlay overlay=new AgentOverlay();
    private ApiClient client;
    private PowerManager.WakeLock wake;
    private String sessionId="";
    private String taskAccount="";
    private JSONArray history=new JSONArray();
    private JSONArray sentAttachments=new JSONArray();
    private boolean ready;
    private int failures,unchanged,connectionFailures,observationFailures,invalidReplies,routineAsks,recoveryAttempts,decisions,selfReturns;
    private int runStartSteps;
    private volatile long runDeadline,inputStartedAt;
    private volatile boolean inputSuspended;
    private String lastFingerprint="";
    private String lastExternalPackage="",homePackage="",pendingReturnApp="";
    private boolean ordinaryActionSucceeded;
    private JSONObject recovery, pendingClarification, clarificationReview;
    private JSONArray clarificationReplies=new JSONArray();
    private final Object lock=new Object();
    public static boolean canAct(int ticket) { AgentService service=instance; return service!=null && !userInteracting && service.generation.get()==ticket && state.equals("running") && service.accountReady(); }
    public static void setUserInteracting(boolean interacting) {
        synchronized(INPUT_LOCK) {
            if(userInteracting==interacting) return;
            userInteracting=interacting;
            AgentService service=instance;
            if(service==null || !state.equals("running")) return;
            if(interacting) {
                service.inputSuspended=true; service.inputStartedAt=SystemClock.elapsedRealtime();
                int ticket=service.generation.incrementAndGet(); service.client.cancel();
                service.main.post(() -> { if(instance==service && ticket==service.generation.get() && state.equals("running") && userInteracting) { service.setState("running","Resumes when you leave Tango."); service.releaseWake(); } });
            } else if(service.inputSuspended) {
                service.inputSuspended=false;
                service.runDeadline+=Math.max(0,SystemClock.elapsedRealtime()-service.inputStartedAt);
                int ticket=service.generation.get();
                service.main.post(() -> { if(instance==service && ticket==service.generation.get() && state.equals("running") && !userInteracting) { service.setState("running","Continuing from the current screen."); service.beginLoop(); } });
            }
        }
    }
    private boolean accountReady() { ChatGptAuth auth=ChatGptAuth.get(this); return auth.hasPlanAccess() && !taskAccount.isBlank() && taskAccount.equals(auth.activeClientId()); }
    @Override public void onCreate() {
        super.onCreate(); loadJournal(this); instance=this; client=new ApiClient(this); restore();
        try { android.content.pm.ResolveInfo home=getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0); if(home!=null && home.activityInfo!=null) homePackage=home.activityInfo.packageName; } catch(Exception ignored) {}
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("agent","Tango task",NotificationManager.IMPORTANCE_LOW));
        wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"tango:task");
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        startForeground(1,notification());
        if (intent==null) { stopSelf(); return START_NOT_STICKY; }
        String action=intent.getAction()==null?"start":intent.getAction();
        loadJournal(this);
        if(action.equals("start") && intent.getBooleanExtra("newThread",false) && ChatGptAuth.get(this).hasPlanAccess()) {
            try {newThread(this);}catch(Exception e){question=safeError(e);setState("paused",question);return START_NOT_STICKY;}
        }
        String requestedThread=intent.getStringExtra("threadId");
        if(requestedThread!=null && !requestedThread.isBlank() && !requestedThread.equals(activeThread)) {
            // Notification controls must never stop a different conversation that was selected later.
            if(action.equals("stop") || action.equals("pause")) return START_NOT_STICKY;
            try { if(!selectThread(this,requestedThread)) return START_NOT_STICKY; }
            catch(Exception e) { question=safeError(e); setState("paused",question); return START_NOT_STICKY; }
        }
        if(!action.equals("stop")&&!action.equals("pause")&&!ChatGptAuth.get(this).hasPlanAccess()) {
            generation.incrementAndGet(); client.cancel(); question="Continue with ChatGPT in Tango before running commands.";
            setState("paused",question); overlay.hide(); releaseWake(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY;
        }
        if (action.equals("stop")) stopTask();
        else if (action.equals("pause")) pauseTask("Paused.");
        else if (action.equals("resume")) resumeTask(intent.getStringExtra("reply"));
        else if (action.equals("steer")) steer(intent.getStringExtra("reply"));
        else startTask(intent.getStringExtra("goal"));
        return START_NOT_STICKY;
    }
    private void startTask(String command) {
        if (command==null || command.isBlank()) { if(threadAttachments(this).length()==0){stopSelf();return;} command="Use the attached files to help with my task."; }
        if(ready && !goal.isBlank()) { resumeTask(command); return; }
        JSONArray attached=validatedDraftAttachments();if(attached==null)return;
        generation.incrementAndGet(); client.cancel(); clearInputHold();
        synchronized(lock) {
            goal=command; sessionId=UUID.randomUUID().toString(); taskAccount=ChatGptAuth.get(this).activeClientId(); steps=0; failures=0; unchanged=0; lastFingerprint="";
            history=new JSONArray(); clarificationReplies=new JSONArray(); pendingClarification=null; sentAttachments=new JSONArray(); journal=new JSONArray(); question=""; ready=true; lastExternalPackage="";
        }
        resetRecovery(); resetRunBudget();
        setState("running","Getting the phone ready.");
        if(!recordUserMessage(command,attached))return;save();beginLoop();
    }
    private void resumeTask(String reply) {
        if (!ready) restore();
        if (goal.isBlank()) { setState("idle","Start a task first."); stopSelf(); return; }
        if(!accountReady()) { generation.incrementAndGet(); client.cancel(); setState("paused","This task belongs to a different ChatGPT account. Switch back or start a new command."); releaseWake(); return; }
        JSONArray attached=validatedDraftAttachments();if(attached==null)return;
        generation.incrementAndGet(); client.cancel(); clearInputHold(); resetRecovery(); resetRunBudget();
        synchronized(lock) {
            String answer=reply==null||reply.isBlank()?"Continue":reply;
            rememberClarificationReply(answer);
            question="";
            goal=goal+"\nFollow-up instruction from user: "+answer;
            history.put(json("userReply",answer));
        }
        if(!recordUserMessage(reply==null||reply.isBlank()?"Continue":reply,attached))return;
        setState("running","Continuing from the current screen."); beginLoop();
    }
    private void steer(String reply) {
        if (reply==null || reply.isBlank()) {if(threadAttachments(this).length()==0)return;reply="Use the attached files for this task.";}
        if(!accountReady()) { generation.incrementAndGet(); client.cancel(); setState("paused","Sign in with the original ChatGPT account or start a new command."); releaseWake(); return; }
        JSONArray attached=validatedDraftAttachments();if(attached==null)return;
        generation.incrementAndGet(); client.cancel(); clearInputHold();
        synchronized(lock) { goal=goal+"\nFollow-up instruction from user: "+reply; history.put(json("userReply",reply)); }
        if(!recordUserMessage(reply,attached))return;resetRecovery(); resetRunBudget(); question="";
        setState("running","Applying your new instruction."); beginLoop();
    }
    private void beginLoop() {
        startForeground(1,notification());
        if (!wake.isHeld()) wake.acquire(MAX_RUN_TIME);
        overlay.show(this); overlay.update(state);
        int ticket=generation.get();
        worker.execute(() -> {
            PhoneController.awaitSettled("start",ticket);
            loop(ticket);
        });
    }
    private void loop(int ticket) {
        JSONArray pendingPlan=new JSONArray(); long planCreatedAt=0;
        while (ticket==generation.get() && state.equals("running")) {
            try {
                if(userInteracting) return;
                if(!accountReady()) { ask("Sign in with this task's ChatGPT account to continue.",ticket); return; }
                if(steps-runStartSteps>=MAX_RUN_STEPS || decisions>=MAX_RUN_DECISIONS || SystemClock.elapsedRealtime()>=runDeadline) { ask("This run reached its time or action limit. Continue for another run or stop.",ticket); return; }
                if (PhoneAccessibilityService.instance==null) { ask("Enable Tango in Accessibility settings, then Continue.",ticket); return; }
                boolean usePlan=pendingPlan.length()>0 && getSharedPreferences("tango",0).getBoolean("fast_mode",false) && android.os.SystemClock.elapsedRealtime()-planCreatedAt<45000 && failures==0;
                if(!usePlan) pendingPlan=new JSONArray();
                JSONObject obs=usePlan?PhoneController.observeNodes():PhoneController.observe(this);
                if (!canAct(ticket)) return;
                if (obs.optBoolean("locked") || ((android.app.KeyguardManager)getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked()) {
                    ask("Unlock your phone, then Continue.",ticket); return;
                }
                if (obs.optBoolean("password") || obs.optBoolean("hasPassword") || hasPassword(obs)) {
                    ask("Complete this sign-in or security check yourself, then Continue.",ticket); return;
                }
                if (obs.optString("packageName").equals(getPackageName())) {
                    if(++selfReturns>3) { ask("I couldn't return to the target app. Open it, then Continue.",ticket); return; }
                    recover(ticket,"Tango is in the foreground",obs,"Return to the task without asking the user to switch apps.");
                    synchronized(lock) {if(!canAct(ticket))return;pendingReturnApp=lastExternalPackage;}
                    if(!performAction(json("type","home","reason","Returning to your task."),ticket,true)) return;
                    continue;
                }
                if(!obs.optString("observationError").isEmpty()) {
                    observationFailures++;
                    if(observationFailures>=6) { ask("I still can't read this screen. Open the target app, then Continue.",ticket); return; }
                    recover(ticket,"Screen observation unavailable",obs,obs.optString("observationError"));
                    if(observationFailures==3 && !performAction(json("type","home","reason","Recovering phone observation."),ticket,true)) return;
                    Thread.sleep(1000); continue;
                }
                observationFailures=0; selfReturns=0;
                String observedPackage=obs.optString("packageName");
                String returnTarget;
                synchronized(lock) {if(!canAct(ticket))return;returnTarget=pendingReturnApp;pendingReturnApp="";}
                if(!returnTarget.isBlank()) {
                    String target=returnTarget;
                    if(!observedPackage.equals(target) && !target.equals(getPackageName())) {
                        if(!performAction(json("type","launch","packageName",target,"reason","Returning to the app already in use."),ticket,true)) return;
                        continue;
                    }
                }
                String fingerprint=fingerprint(obs);
                synchronized(lock) {
                    if(!canAct(ticket))return;
                    if(isTaskApp(observedPackage))lastExternalPackage=observedPackage;
                    unchanged=fingerprint.equals(lastFingerprint)?unchanged+1:0;lastFingerprint=fingerprint;
                    if(unchanged==0 && ordinaryActionSucceeded){routineAsks=0;recovery=null;}ordinaryActionSucceeded=false;
                }
                if(unchanged>=5 && unchanged%5==0) recover(ticket,"Screen has not changed",obs,"Reobserve and try an alternate reversible path. An unchanged screen alone is not a reason to ask the user.");
                if(failures>=3) recover(ticket,"Several phone actions failed",obs,"Use the actual failure outcomes and this fresh snapshot. Choose a different control or route; do not reuse stale targets.");
                if(usePlan) {
                    JSONObject next=FastPlan.resolve(pendingPlan.optJSONObject(0),obs);
                    if(next!=null && ActionGuard.blockReason(goal,obs,next)==null) {
                        pendingPlan.remove(0);
                        if(!performAction(next,ticket,false)) return;
                        continue;
                    }
                    pendingPlan=new JSONArray();
                    // A failed precondition always returns to the planner with a fresh image.
                    continue;
                }
                JSONObject request;
                synchronized(lock) {if(!canAct(ticket))return;request=new JSONObject().put("sessionId",sessionId).put("taskAccount",taskAccount).put("threadId",activeThread).put("goal",goal).put("observation",obs).put("history",boundedHistory()).put("attachments",new JSONArray(sentAttachments.toString())); if(recovery!=null)request.put("recovery",recovery);request.put("clarificationReplies",new JSONArray(clarificationReplies.toString()));if(clarificationReview!=null)request.put("clarificationReview",new JSONObject(clarificationReview.toString()));}
                if(getSharedPreferences("tango",0).getBoolean("fast_mode",false)) request.put("executionMode",getSharedPreferences("tango",0).getString("fast_mode_strategy","plan").equals("choice")?"choice":"plan");
                JSONArray attached=request.getJSONArray("attachments");
                if(attached.length()>0)request.put("attachmentInputs",new AttachmentStore(getFilesDir()).modelContent(request.getString("taskAccount"),request.getString("threadId"),attached));
                if(!canAct(ticket))return;
                decisions++;
                main.post(() -> { if (canAct(ticket)) setState("running","Working • step "+(steps+1)); });
                JSONObject result=client.decide(request);
                if (ticket!=generation.get() || !state.equals("running") || userInteracting) return;
                if(!accountReady()) { ask("Sign in again to restore ChatGPT access.",ticket); return; }
                connectionFailures=0;
                JSONObject action;
                try { action=result.getJSONObject("action"); ActionGuard.validate(action); invalidReplies=0; }
                catch(Exception invalid) {
                    if(++invalidReplies>=3) { ask("The AI returned unusable actions after retries. Continue to try again.",ticket); return; }
                    recover(ticket,"Unusable action",obs,safeError(invalid)); continue;
                }
                String reason=ActionGuard.blockReason(goal,obs,action);
                if (reason!=null) {
                    if(reason.equals("Your request is to keep a draft. Sending is blocked.")) {
                        if(++routineAsks>=MAX_ROUTINE_ASKS) { ask("Sending stayed blocked. The draft still needs to be saved or checked; Continue to retry.",ticket); return; }
                        recover(ticket,"Draft send action was blocked",obs,"Do not send. Save or verify the requested draft instead. The blocked action did not run.");
                        continue;
                    }
                    ask(reason,ticket); return;
                }
                String type=action.getString("type");
                if (type.equals("ask")) {
                    if(RecoveryPolicy.isSecurity(action)) { ask(action,ticket); return; }
                    if(clarificationReview==null) {
                        synchronized(lock){if(!canAct(ticket))return;clarificationReview=RecoveryPolicy.clarificationReview(action);}
                        recover(ticket,"Checking available evidence before asking",obs,action.getString("question"));
                        continue;
                    }
                    if(RecoveryPolicy.needsUser(action)) { ask(action,ticket); return; }
                    routineAsks++;
                    if(routineAsks>=MAX_ROUTINE_ASKS) { ask("Recovery hasn't made progress. Open the target screen or give me another approach, then Continue.",ticket); return; }
                    recover(ticket,"Routine question redirected to autonomous recovery",obs,action.getString("question"));
                    if(routineAsks==2 || routineAsks==4) {
                        JSONObject fallback;
                        if(RecoveryPolicy.preserveUnsavedWork(obs)) fallback=json("type","wait","durationMs",1000,"reason","Preserving your form or draft while checking the screen again.");
                        else {synchronized(lock){if(!canAct(ticket))return;pendingReturnApp=isTaskApp(observedPackage)?observedPackage:lastExternalPackage;}fallback=json("type","home","reason","Trying a safe return to the current app.");}
                        if(!performAction(fallback,ticket,true)) return;
                    }
                    continue;
                }
                clarificationReview=null;
                if (type.equals("done")) {
                    String summary=action.getString("summary");
                    main.post(() -> { if (canAct(ticket)) { question=""; log("Result",summary); setState("done",summary); overlay.hide(); releaseWake(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); } }); return;
                }
                pendingPlan=FastPlan.accept(result); planCreatedAt=android.os.SystemClock.elapsedRealtime();
                if(!performAction(action,ticket,false)) return;
            } catch (Exception e) {
                if (ticket!=generation.get() || !state.equals("running") || userInteracting) return;
                if(e instanceof InterruptedException) { Thread.currentThread().interrupt(); return; }
                connectionFailures++;
                boolean permanent=e instanceof ApiClient.ProviderException && !((ApiClient.ProviderException)e).shouldRetry();
                if(permanent || connectionFailures>=4) { ask(safeError(e)+" Continue when it's resolved.",ticket); return; }
                log(ticket,"Recovery","Retrying the AI connection ("+connectionFailures+"/3).");
                main.post(() -> { if(canAct(ticket))setState("running","Retrying the connection."); });
                try { Thread.sleep(1000L*(1L<<(connectionFailures-1))); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
            }
        }
    }
    private boolean performAction(JSONObject action,int ticket,boolean recoveryAction) throws Exception {
        if(!canAct(ticket)) return false;
        String type=action.getString("type");
        log(ticket,recoveryAction?"Recovery":"Tango",action.isNull("reason")?type:action.optString("reason",type));
        action.put("_generation",ticket);
        JSONObject outcome;
        try { outcome=PhoneController.execute(this,action); } finally { action.remove("_generation"); }
        if(!canAct(ticket)) return false;
        synchronized(lock) { if(!canAct(ticket)) return false; steps++; history.put(new JSONObject().put("step",steps).put("action",action).put("result",outcome)); trimHistory(); }
        if(!recoveryAction) {
            failures=outcome.optBoolean("ok")?0:failures+1;
            ordinaryActionSucceeded=outcome.optBoolean("ok") && !type.equals("wait") && !type.equals("home") && !type.equals("launch");
        }
        save(); PhoneController.awaitSettled(outcome.optBoolean("ok")?type:"failed",ticket); return canAct(ticket);
    }
    private void recover(int ticket,String reason,JSONObject obs,String detail) {
        synchronized(lock) {
            if(!canAct(ticket))return;
            recovery=RecoveryPolicy.context(reason,++recoveryAttempts,failures,unchanged,lastExternalPackage,detail);
            history.put(json("type","recovery","recovery",recovery)); trimHistory();
            if(recoveryAttempts==1 || reason.equals("Routine question redirected to autonomous recovery")) log("Recovery","Trying another approach.");
        }
    }
    private boolean isTaskApp(String packageName) { return !packageName.isBlank() && !packageName.equals(getPackageName()) && !packageName.equals(homePackage) && !packageName.equals("com.android.systemui") && !packageName.equals("android") && !packageName.contains("permissioncontroller"); }
    private void clearInputHold() { synchronized(INPUT_LOCK) { userInteracting=false; inputSuspended=false; } }
    private void resetRunBudget() { runStartSteps=steps; decisions=0; runDeadline=SystemClock.elapsedRealtime()+MAX_RUN_TIME; }
    private void resetRecovery() { failures=0; unchanged=0; connectionFailures=0; observationFailures=0; invalidReplies=0; routineAsks=0; recoveryAttempts=0; selfReturns=0; lastFingerprint=""; pendingReturnApp=""; ordinaryActionSucceeded=false; recovery=null; clarificationReview=null; }
    private void trimHistory() { if(history.length()>160) { JSONArray trimmed=new JSONArray(); for(int i=history.length()-128;i<history.length();i++)trimmed.put(history.opt(i)); history=trimmed; } }
    private boolean hasPassword(JSONObject obs) {
        JSONArray nodes=obs.optJSONArray("nodes");
        if (nodes!=null) for (int i=0;i<nodes.length();i++) { JSONObject n=nodes.optJSONObject(i); if (n!=null && n.optBoolean("password")) return true; }
        return false;
    }
    private String fingerprint(JSONObject obs) throws Exception {
        // IDs change on every snapshot. Fingerprint semantics and geometry instead.
        JSONArray nodes=obs.optJSONArray("nodes"); StringBuilder b=new StringBuilder(obs.optString("packageName"));
        if (nodes!=null) for (int i=0;i<nodes.length();i++) { JSONObject n=nodes.optJSONObject(i); if(n!=null) b.append(n.optString("text")).append(n.optString("description")).append(n.opt("bounds")); }
        if (nodes==null || nodes.length()<3) b.append(obs.optString("screenshot").hashCode());
        return b.toString();
    }
    private JSONArray boundedHistory() throws Exception {
        JSONArray out=new JSONArray(); for (int i=Math.max(0,history.length()-32);i<history.length();i++) out.put(history.get(i)); return out;
    }
    private void ask(String message,int ticket) {
        ask(json("type","ask","question",message,"blockerKind","environment"),ticket);
    }
    private void ask(JSONObject action,int ticket) {
        final JSONObject pending;try{pending=new JSONObject(action.toString());}catch(Exception invalid){return;}
        String message=RecoveryPolicy.humanQuestion(pending);
        main.post(() -> { synchronized(lock){if(ticket!=generation.get())return;pendingClarification=pending;question=message;log("Needs you",message);setState("waiting",message);releaseWake();} });
    }
    private void rememberClarificationReply(String answer) {
        // Old versions saved only the visible question. Migrate it when continuing an existing thread.
        JSONObject pending=pendingClarification;
        if(pending==null&&!question.isBlank())pending=json("question",question);
        if(pending!=null){
            JSONObject resolved=json("question",pending.optString("question",question),"blockerKind",pending.optString("blockerKind"),"blockerEvidence",pending.optString("blockerEvidence"),"userReply",answer);
            clarificationReplies.put(resolved);history.put(json("clarificationReply",resolved));
            while(clarificationReplies.length()>24)clarificationReplies.remove(0);
        }
        pendingClarification=null;
    }
    public void pauseTask(String message) {
        generation.incrementAndGet(); client.cancel(); question=""; setState("paused",message); releaseWake();
    }
    public void stopTask() {
        generation.incrementAndGet(); client.cancel(); question=""; log("Tango","Stopped by you."); setState("stopped","Stopped.");
        overlay.hide(); releaseWake(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    private void releaseWake() { if(wake!=null && wake.isHeld()) wake.release(); }
    private void setState(String next,String message) {
        state=next; status=message; overlay.update(next); save();
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1,notification());
    }
    private Notification notification() {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,AgentService.class).setAction("stop").putExtra("threadId",activeThread),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause=PendingIntent.getService(this,2,new Intent(this,AgentService.class).setAction("pause").putExtra("threadId",activeThread),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"agent").setSmallIcon(R.drawable.ic_tango).setContentTitle(state.equals("waiting")?"Tango needs you":"Tango • "+state)
                .setContentText(status).setStyle(new Notification.BigTextStyle().bigText(status)).setContentIntent(open).setOngoing(state.equals("running")||state.equals("waiting")||state.equals("paused"))
                .addAction(new Notification.Action.Builder(null,"Pause",pause).build()).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
    }
    private void log(String who,String message) {
        synchronized(lock) {
            journal.put(json("who",who,"text",message,"time",System.currentTimeMillis()));
            if(journal.length()>300) {
                int firstChat=-1,chatCount=0;
                for(int i=0;i<journal.length();i++){JSONObject entry=journal.optJSONObject(i);if(isChatEntry(entry)){if(firstChat<0)firstChat=i;chatCount++;}}
                boolean[] keep=new boolean[journal.length()];int chats=0,actions=0;
                for(int i=journal.length()-1;i>=0;i--) {
                    JSONObject entry=journal.optJSONObject(i);boolean chat=isChatEntry(entry);
                    if(chat){if(chats++<(chatCount>200?199:200))keep[i]=true;}else if(actions++<80)keep[i]=true;
                }
                if(firstChat>=0)keep[firstChat]=true;
                JSONArray slim=new JSONArray();for(int i=0;i<journal.length();i++)if(keep[i])slim.put(journal.opt(i));journal=slim;
            }
        }
    }
    private static boolean isChatEntry(JSONObject entry) {return entry!=null && (entry.optString("who").equals("You") || entry.optString("who").equals("Result") || entry.optString("who").equals("Needs you"));}
    private void log(int ticket,String who,String message) { synchronized(lock) { if(canAct(ticket)) log(who,message); } }
    private void save() {
        try {
            synchronized(lock) {
                JSONObject saved=new JSONObject().put("state",state).put("status",status).put("goal",goal).put("question",question).put("steps",steps).put("sessionId",sessionId).put("taskAccount",taskAccount).put("lastExternalPackage",lastExternalPackage).put("journal",journal).put("history",history).put("sentAttachments",sentAttachments).put("pendingClarification",pendingClarification==null?JSONObject.NULL:pendingClarification).put("clarificationReplies",clarificationReplies);
                if(loadedAccount!=null && loadedAccount.equals(taskAccount) && !activeThread.isEmpty()) store(this).save(taskAccount,activeThread,saved);
                // A blocked sign-in attempt must preserve an older account's recoverable legacy file.
                if(!ChatGptAuth.get(this).hasPlanAccess()) {
                    try(java.io.InputStream existing=openFileInput("task.json")) {
                        if(!taskAccount.equals(new JSONObject(readFully(existing)).optString("taskAccount")))return;
                    }catch(java.io.FileNotFoundException ignored){}
                }
                android.util.AtomicFile atomic=new android.util.AtomicFile(new java.io.File(getFilesDir(),"task.json"));
                java.io.FileOutputStream file=atomic.startWrite();
                try { file.write(saved.toString().getBytes(StandardCharsets.UTF_8)); atomic.finishWrite(file); }
                catch(Exception e) { atomic.failWrite(file); }
            }
        } catch(Exception ignored) {}
    }
    private static synchronized ThreadStore store(Context c) throws Exception {
        if(threadStore==null) {ThreadStore candidate=new ThreadStore(c.getFilesDir());candidate.migrateLegacy();threadStore=candidate;} return threadStore;
    }
    public static void loadJournal(Context c) {
        String account=ChatGptAuth.get(c).activeClientId();
        if(account.equals(loadedAccount) && !activeThread.isEmpty()) return;
        try {
            cancelForThreadChange();
            String id=store(c).active(account);activateThread(c,account,id,store(c).get(account,id));
        } catch(Exception e) { loadedAccount=account;activeThread="";clearVisible();status="Your conversations could not be opened. Free some storage and try again."; }
    }
    public static String activeThreadId(Context c) {loadJournal(c);return activeThread;}
    /** UI readers receive a stable copy while the worker appends task events. */
    public static JSONArray journalSnapshot() {
        AgentService service=instance;
        if(service!=null)synchronized(service.lock){return copyJournal();}
        return copyJournal();
    }
    private static JSONArray copyJournal() {try{return new JSONArray(journal.toString());}catch(Exception ignored){return new JSONArray();}}
    public static JSONArray listThreads(Context c,String query,boolean archived) {
        loadJournal(c);try {return store(c).list(ChatGptAuth.get(c).activeClientId(),query,archived);}catch(Exception ignored){return new JSONArray();}
    }
    /** Detail includes the durable conversation journal, result, and phone action history. */
    public static JSONObject getThread(Context c,String id) throws Exception {
        loadJournal(c);JSONObject thread=store(c).get(ChatGptAuth.get(c).activeClientId(),id);if(thread==null)return null;
        JSONObject result=thread.optJSONObject("snapshot");result=result==null?new JSONObject():new JSONObject(result.toString());
        if(id.equals(activeThread)) {
            AgentService service=instance;
            if(service!=null) {synchronized(service.lock) {result.put("sessionId",service.sessionId).put("history",new JSONArray(service.history.toString()));}}
            result.put("goal",goal).put("state",state).put("status",status).put("question",question).put("steps",steps).put("journal",new JSONArray(journal.toString()));
        }
        result.put("id",thread.getString("id")).put("title",thread.optString("title")).put("archived",thread.optBoolean("archived"))
            .put("createdAt",thread.optLong("createdAt")).put("updatedAt",thread.optLong("updatedAt")).put("active",id.equals(activeThread));
        if(!result.has("journal"))result.put("journal",new JSONArray());if(!result.has("state"))result.put("state","idle");return result;
    }
    public static String newThread(Context c) throws Exception {
        loadJournal(c);cancelForThreadChange();String account=ChatGptAuth.get(c).activeClientId(),id=store(c).create(account);
        activateThread(c,account,id,store(c).get(account,id));return id;
    }
    public static boolean selectThread(Context c,String id) throws Exception {
        loadJournal(c);String account=ChatGptAuth.get(c).activeClientId();JSONObject thread=store(c).get(account,id);
        if(thread==null || thread.optBoolean("archived"))return false;if(id.equals(activeThread))return true;
        cancelForThreadChange();if(!store(c).select(account,id))return false;activateThread(c,account,id,thread);return true;
    }
    public static void renameThread(Context c,String id,String title) throws Exception {loadJournal(c);store(c).rename(ChatGptAuth.get(c).activeClientId(),id,title);}
    public static void archiveThread(Context c,String id,boolean archived) throws Exception {
        loadJournal(c);String account=ChatGptAuth.get(c).activeClientId();if(archived && id.equals(activeThread))cancelForThreadChange();
        store(c).archive(account,id,archived);String selected=store(c).active(account);if(!selected.equals(activeThread))activateThread(c,account,selected,store(c).get(account,selected));
    }
    public static void deleteThread(Context c,String id) throws Exception {
        loadJournal(c);String account=ChatGptAuth.get(c).activeClientId();if(id.equals(activeThread))cancelForThreadChange();store(c).delete(account,id);
        String selected=store(c).active(account);if(!selected.equals(activeThread))activateThread(c,account,selected,store(c).get(account,selected));
    }
    public static void clearThreads(Context c) throws Exception {loadJournal(c);cancelForThreadChange();String account=ChatGptAuth.get(c).activeClientId();store(c).clear(account);String id=store(c).active(account);activateThread(c,account,id,store(c).get(account,id));c.deleteFile("task.json");}
    public static String threadDraft(Context c) {return draft(c,"draft");}
    public static String threadReplyDraft(Context c) {return draft(c,"replyDraft");}
    private static String draft(Context c,String key) {loadJournal(c);try{JSONObject thread=store(c).get(ChatGptAuth.get(c).activeClientId(),activeThread);return thread==null?"":thread.optString(key);}catch(Exception ignored){return "";}}
    public static void saveThreadDraft(Context c,String command) throws Exception {saveThreadDraft(c,command,threadReplyDraft(c));}
    public static void saveThreadDraft(Context c,String command,String reply) throws Exception {loadJournal(c);store(c).draft(ChatGptAuth.get(c).activeClientId(),activeThread,command,reply);}
    static void stageThreadDraft(Context c,String account,String thread,String command,String reply) throws Exception {
        AttachmentStore.requireAccount(c,account);if(!thread.equals(activeThreadId(c)))return;
        ThreadStore destination=store(c);synchronized(destination){AttachmentStore.requireAccount(c,account);if(!thread.equals(destination.active(account)))return;destination.stageDraft(account,thread,command,reply);}
        DraftSaveQueue.persist(destination,account);
    }
    /** Draft metadata contains no private paths or file content. */
    public static JSONArray threadAttachments(Context c) {
        try {String account=AttachmentStore.authorizedAccount(c);loadJournal(c);JSONObject thread=store(c).get(account,activeThread);JSONArray values=thread==null?null:thread.optJSONArray("attachments");return values==null?new JSONArray():new JSONArray(values.toString());}
        catch(Exception ignored){return new JSONArray();}
    }
    /** Run imports on a worker: cloud document providers may need to download selected content. */
    public static JSONObject addThreadAttachment(Context c,android.net.Uri uri) throws Exception {
        return addThreadAttachment(c,uri,AttachmentStore.authorizedAccount(c),activeThreadId(c));
    }
    /** Capture these identifiers before launching a picker, then use this overload for its result. */
    public static JSONObject addThreadAttachment(Context c,android.net.Uri uri,String account,String thread) throws Exception {
        return addThreadAttachment(c,uri,account,thread,new android.os.CancellationSignal());
    }
    public static JSONObject addThreadAttachment(Context c,android.net.Uri uri,String account,String thread,android.os.CancellationSignal cancellation) throws Exception {
        cancellation.throwIfCanceled();
        AttachmentStore.requireAccount(c,account);if(!thread.equals(activeThreadId(c)))throw new Exception("The chat changed. Add the file again in this chat.");
        if(threadAttachments(c).length()>=AttachmentStore.MAX_FILES)throw new Exception("Add up to 4 files per message.");
        AttachmentStore files=new AttachmentStore(c.getFilesDir());
        JSONObject metadata=files.importUri(c,account,thread,uri,cancellation);
        try{synchronized(cancellation){cancellation.throwIfCanceled();addImportedAttachment(c,account,thread,metadata);}return metadata;}
        catch(Exception error){files.remove(account,thread,metadata.getString("id"));throw error;}
    }
    static void addImportedAttachment(Context c,String account,String thread,JSONObject metadata) throws Exception {
        AttachmentStore.requireAccount(c,account);loadJournal(c);
        ThreadStore destination=store(c);synchronized(destination){
            AttachmentStore.requireAccount(c,account);
            JSONObject saved=destination.get(account,thread);
            if(!thread.equals(activeThread)||!thread.equals(destination.active(account))||saved==null||saved.optBoolean("archived"))throw new Exception("The chat changed. Add the file again in this chat.");
            JSONArray attachments=saved.optJSONArray("attachments");if(attachments==null)attachments=new JSONArray();
            if(attachments.length()>=AttachmentStore.MAX_FILES)throw new Exception("Add up to 4 files per message.");
            attachments.put(metadata);destination.attachments(account,thread,attachments);
        }
    }
    public static synchronized void removeThreadAttachment(Context c,String id) throws Exception {
        String account=AttachmentStore.authorizedAccount(c),thread=activeThreadId(c);JSONArray attached=threadAttachments(c);boolean removed=false;
        for(int i=0;i<attached.length();i++)if(id.equals(attached.getJSONObject(i).optString("id"))){attached.remove(i);removed=true;break;}
        if(removed){
            store(c).attachments(account,thread,attached);
            JSONObject saved=store(c).get(account,thread);JSONObject snapshot=saved==null?null:saved.optJSONObject("snapshot");
            boolean referenced=false;JSONArray sent=snapshot==null?null:snapshot.optJSONArray("sentAttachments");
            if(sent!=null)for(int i=0;i<sent.length();i++)if(sent.optJSONObject(i)!=null && id.equals(sent.optJSONObject(i).optString("id"))){referenced=true;break;}
            if(!referenced)new AttachmentStore(c.getFilesDir()).remove(account,thread,id);
        }
    }
    private JSONArray validatedDraftAttachments() {
        try{String account=AttachmentStore.authorizedAccount(this);JSONArray draft=threadAttachments(this);AttachmentStore files=new AttachmentStore(getFilesDir());for(int i=0;i<draft.length();i++)files.metadata(account,activeThread,draft.getJSONObject(i).getString("id"));return draft;}
        catch(Exception error){question=safeError(error);setState("paused",question);return null;}
    }
    private boolean recordUserMessage(String message,JSONArray attached) {
        try {
            synchronized(lock) {
                AttachmentStore.requireAccount(this,taskAccount);
                log("You",message);
                if(attached.length()>0){journal.getJSONObject(journal.length()-1).put("attachments",new JSONArray(attached.toString()));for(int i=0;i<attached.length();i++)sentAttachments.put(new JSONObject(attached.getJSONObject(i).toString()).put("message",message));}
                // The journal/sent references become durable before the draft is cleared.
                JSONObject saved=new JSONObject().put("state",state).put("status",status).put("goal",goal).put("question",question).put("steps",steps).put("sessionId",sessionId).put("taskAccount",taskAccount).put("lastExternalPackage",lastExternalPackage).put("journal",journal).put("history",history).put("sentAttachments",sentAttachments).put("pendingClarification",pendingClarification==null?JSONObject.NULL:pendingClarification).put("clarificationReplies",clarificationReplies);
                store(this).commitMessage(taskAccount,activeThread,saved);
            }
            return true;
        }catch(Exception error){question=safeError(error);setState("paused",question);return false;}
    }
    private static void cancelForThreadChange() {
        AgentService service=instance;if(service==null)return;
        synchronized(service.lock) {
            service.generation.incrementAndGet();service.client.cancel();service.inputSuspended=false;
            if(state.equals("running") || state.equals("waiting")) {state="paused";status="Paused when you switched conversations.";}
            service.save();
            service.overlay.hide();service.releaseWake();service.stopForeground(STOP_FOREGROUND_REMOVE);
        }
    }
    private static void clearVisible() {goal="";question="";steps=0;journal=new JSONArray();state="idle";status="Ready when you are.";}
    private static void activateThread(Context c,String account,String id,JSONObject thread) throws Exception {
        AgentService service=instance;
        if(service==null){loadedAccount=account;activeThread=id;applyThread(c,thread);}
        else synchronized(service.lock){loadedAccount=account;activeThread=id;applyThread(c,thread);}
    }
    private static void applyThread(Context c,JSONObject thread) throws Exception {
        JSONObject saved=thread==null?null:thread.optJSONObject("snapshot");
        if(saved!=null && saved.optString("goal").isBlank() && (saved.optJSONArray("journal")==null || saved.optJSONArray("journal").length()==0))saved=null;
        if(saved==null)clearVisible();else {
            goal=saved.optString("goal");steps=saved.optInt("steps");journal=saved.optJSONArray("journal");if(journal==null)journal=new JSONArray();
            String previous=saved.optString("state","idle");state=previous.equals("running") || previous.equals("waiting") || previous.equals("paused") ? "paused" : previous;
            status=state.equals("paused")?"Conversation restored. Tap Continue when the phone is ready.":saved.optString("status","Ready when you are.");question=saved.optString("question");
        }
        AgentService service=instance;if(service!=null)service.restoreSnapshot(saved,ChatGptAuth.get(c).activeClientId());
    }
    private void restore() {
        try {JSONObject thread=store(this).get(ChatGptAuth.get(this).activeClientId(),activeThread);restoreSnapshot(thread==null?null:thread.optJSONObject("snapshot"),ChatGptAuth.get(this).activeClientId());}
        catch(Exception ignored) {restoreSnapshot(null,ChatGptAuth.get(this).activeClientId());}
    }
    private void restoreSnapshot(JSONObject saved,String account) {
        synchronized(lock) {
            sessionId=saved==null?"":saved.optString("sessionId",UUID.randomUUID().toString());history=saved==null?new JSONArray():saved.optJSONArray("history");if(history==null)history=new JSONArray();
            sentAttachments=saved==null?new JSONArray():saved.optJSONArray("sentAttachments");if(sentAttachments==null)sentAttachments=new JSONArray();
            pendingClarification=saved==null?null:saved.optJSONObject("pendingClarification");
            clarificationReplies=saved==null?new JSONArray():saved.optJSONArray("clarificationReplies");if(clarificationReplies==null)clarificationReplies=new JSONArray();
            taskAccount=account;lastExternalPackage=saved==null?"":saved.optString("lastExternalPackage");ready=saved!=null && !goal.isBlank();resetRecovery();
        }
    }
    private String safeError(Exception e) { String m=e.getMessage(); if(m==null)m=e.getClass().getSimpleName(); return m.substring(0,Math.min(500,m.length())); }
    private static String readFully(java.io.InputStream in) throws Exception { java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream(); byte[] b=new byte[8192]; int n; while((n=in.read(b))!=-1) { if(out.size()+n>500000)throw new Exception("Task journal too large"); out.write(b,0,n); } return out.toString(StandardCharsets.UTF_8.name()); }
    private static JSONObject json(Object... pairs) { JSONObject j=new JSONObject(); try { for(int i=0;i<pairs.length;i+=2)j.put((String)pairs[i],pairs[i+1]); } catch(Exception ignored) {} return j; }
    @Override public void onDestroy() { generation.incrementAndGet(); client.cancel(); worker.shutdownNow(); overlay.hide(); releaseWake(); instance=null; super.onDestroy(); }
    @Override public IBinder onBind(Intent i) { return null; }
}
