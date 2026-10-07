package com.tango.authsmoke;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Deterministic conversation persistence and cancellation checks. No live provider calls. */
public final class ThreadChecks {
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    private static Object invoke(Object target,String name,Class<?>[] types,Object...args) throws Exception {
        Class<?> type=target instanceof Class<?> ? (Class<?>)target : target.getClass();Method method=type.getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(target instanceof Class<?> ? null : target,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw(Exception)e.getCause();throw new RuntimeException(e.getCause());}
    }
    private static Object field(Object target,String name) throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void set(Object target,String name,Object value) throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static JSONObject snapshot(String account,String goal) throws Exception {
        return new JSONObject().put("taskAccount",account).put("sessionId","fixture-session").put("state","done").put("status","Saved fixture result")
            .put("goal",goal).put("steps",3).put("journal",new JSONArray().put(new JSONObject().put("who","You").put("text",goal))
                .put(new JSONObject().put("who","Result").put("text","Saved fixture result")))
            .put("history",new JSONArray().put(new JSONObject().put("userReply","Saved fixture action history")));
    }
    private static Object store(Class<?> type,File files) throws Exception {return type.getConstructor(File.class).newInstance(files);}
    private static String create(Object store,String account) throws Exception {return(String)invoke(store,"create",new Class[]{String.class},account);}
    private static JSONObject get(Object store,String account,String id) throws Exception {return(JSONObject)invoke(store,"get",new Class[]{String.class,String.class},account,id);}
    private static JSONArray list(Object store,String account,String query,boolean archived) throws Exception {return(JSONArray)invoke(store,"list",new Class[]{String.class,String.class,boolean.class},account,query,archived);}
    private static void save(Object store,String account,String id,JSONObject value) throws Exception {invoke(store,"save",new Class[]{String.class,String.class,JSONObject.class},account,id,value);}
    private static void deleteFiles(File file) {if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)deleteFiles(child);}file.delete();}
    private static void write(File file,JSONObject json) throws Exception {file.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(file)){out.write(json.toString().getBytes(StandardCharsets.UTF_8));}}
    private static void storage(Class<?> type,File files,JSONObject checks) throws Exception {
        deleteFiles(files);files.mkdirs();Object store=store(type,files);
        String initial=(String)invoke(store,"active",new Class[]{String.class},"account-a");
        require(list(store,"account-a","",false).length()==0,"Untouched New chat appears in history");
        require(initial.equals(create(store,"account-a")),"New chat duplicates an untouched blank conversation");
        invoke(store,"draft",new Class[]{String.class,String.class,String.class,String.class},"account-a",initial,"Typed draft","Blocker answer");
        require(list(store,"account-a","",false).length()==1,"Draft conversation was hidden");
        String second=create(store,"account-a");require(!initial.equals(second),"New chat reused a drafted conversation");
        save(store,"account-a",second,snapshot("account-a","Find the fixture board"));
        require(list(store,"account-a","fixture",false).length()==1,"Conversation content search did not filter");
        invoke(store,"rename",new Class[]{String.class,String.class,String.class},"account-a",second,"Board regression checks");
        require(list(store,"account-a","REGRESSION",false).length()==1,"Renamed title search is not case insensitive");
        JSONObject detached=get(store,"account-a",second);detached.getJSONObject("snapshot").put("goal","MUTATED RETURN VALUE");
        require(!get(store,"account-a",second).toString().contains("MUTATED RETURN VALUE"),"Caller can corrupt stored conversation through a returned object");
        require(get(store,"account-b",second)==null,"Other account can read conversation detail");
        require(!(boolean)invoke(store,"select",new Class[]{String.class,String.class},"account-b",second),"Other account can select conversation");
        try{save(store,"account-b",second,snapshot("account-a","wrong owner"));throw new AssertionError("Other account can overwrite conversation");}catch(Exception expected){}
        require(list(store,"account-b","",false).length()==0,"Other account sees conversation history");
        checks.put("threadsAccountIsolationAndContentSearch",true);
        store=store(type,files);
        require(second.equals(invoke(store,"active",new Class[]{String.class},"account-a")),"Selected conversation did not survive restart");
        JSONObject restored=get(store,"account-a",initial);require("Typed draft".equals(restored.optString("draft")) && "Blocker answer".equals(restored.optString("replyDraft")),"Per-thread drafts did not survive restart");
        require(get(store,"account-a",second).getJSONObject("snapshot").getJSONArray("journal").length()==2,"Journal/result did not survive restart");
        checks.put("threadsDraftsSelectedThreadAndResultSurviveRestart",true);
        invoke(store,"archive",new Class[]{String.class,String.class,boolean.class},"account-a",second,true);
        require(initial.equals(invoke(store,"active",new Class[]{String.class},"account-a")),"Archiving selected conversation did not select another chat");
        require(list(store,"account-a","",true).length()==1 && !(boolean)invoke(store,"select",new Class[]{String.class,String.class},"account-a",second),"Archived conversation is still selectable in normal history");
        invoke(store,"archive",new Class[]{String.class,String.class,boolean.class},"account-a",second,false);
        require((boolean)invoke(store,"select",new Class[]{String.class,String.class},"account-a",second),"Unarchiving did not restore selection");
        invoke(store,"delete",new Class[]{String.class,String.class},"account-a",second);require(get(store,"account-a",second)==null,"Deleted conversation remains readable");
        invoke(store,"clear",new Class[]{String.class},"account-a");require(list(store,"account-a","",false).length()==0,"Cleared conversations remain visible");
        checks.put("threadsRenameArchiveRestoreDeleteAndClear",true);
        File legacy=new File(files,"legacy");legacy.mkdirs();write(new File(legacy,"task.json"),snapshot("legacy-owner","Legacy task"));Object imported=store(type,legacy);
        invoke(imported,"migrateLegacy",new Class[]{});invoke(imported,"migrateLegacy",new Class[]{});
        require(list(imported,"legacy-owner","",false).length()==1 && list(imported,"other-owner","",false).length()==0,"Legacy task migration duplicated or leaked another account's history");
        JSONObject migrated=get(imported,"legacy-owner",(String)invoke(imported,"active",new Class[]{String.class},"legacy-owner"));
        require(migrated.getJSONObject("snapshot").getJSONArray("history").toString().contains("Saved fixture action history"),"Migration dropped action history");
        File unowned=new File(files,"unowned");unowned.mkdirs();write(new File(unowned,"task.json"),snapshot("","Legacy unknown owner"));Object isolated=store(type,unowned);invoke(isolated,"migrateLegacy",new Class[]{});
        require(list(isolated,"","",false).length()==0 && list(isolated,"next-owner","",false).length()==0,"Unknown legacy task was assigned to the next account");
        checks.put("threadsLegacyMigrationRetainsHistoryWithoutAccountLeak",true);
        deleteFiles(files);
    }
    private interface Checked {void run() throws Exception;}
    private static void main(Instrumentation test,Checked checked) throws Exception {Throwable[] error={null};test.runOnMainSync(()->{try{checked.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null){if(error[0] instanceof Exception)throw(Exception)error[0];throw new AssertionError(error[0]);}test.waitForIdleSync();}
    private static void service(Instrumentation test,Context context,JSONObject checks) throws Exception {
        Class<?> agent=context.getClassLoader().loadClass("com.tango.phone.AgentService");
        String original=(String)invoke(agent,"activeThreadId",new Class[]{Context.class},context);
        main(test,()->context.startForegroundService(new Intent().setClassName("com.tango.phone","com.tango.phone.AgentService").setAction("pause")));
        long until=SystemClock.uptimeMillis()+3000;while(agent.getField("instance").get(null)==null && SystemClock.uptimeMillis()<until)SystemClock.sleep(20);
        Object service=agent.getField("instance").get(null);require(service!=null,"Conversation fixture service did not start");
        Object auth=invoke(context.getClassLoader().loadClass("com.tango.phone.ChatGptAuth"),"get",new Class[]{Context.class},context);
        String account=(String)invoke(auth,"activeClientId",new Class[]{});
        String[] first={""},second={""};
        main(test,()->{
            first[0]=(String)invoke(agent,"newThread",new Class[]{Context.class},context);
            agent.getField("goal").set(null,"THREAD_FOLLOWUP_FIXTURE Initial phone task");agent.getField("state").set(null,"done");agent.getField("status").set(null,"Original result");agent.getField("journal").set(null,snapshot(account,"Initial phone task").getJSONArray("journal"));agent.getField("steps").setInt(null,3);
            set(service,"ready",true);set(service,"sessionId","thread-continuation-session");set(service,"taskAccount",account);set(service,"history",snapshot(account,"Initial phone task").getJSONArray("history"));invoke(service,"save",new Class[]{});
            invoke(agent,"setUserInteracting",new Class[]{boolean.class},false);agent.getField("state").set(null,"running");
        });
        int stale=((AtomicInteger)field(service,"generation")).get();
        main(test,()->second[0]=(String)invoke(agent,"newThread",new Class[]{Context.class},context));
        require(((AtomicInteger)field(service,"generation")).get()>stale && !(boolean)invoke(agent,"canAct",new Class[]{int.class},stale),"Switching chat did not invalidate the previous run");
        require(((String)agent.getField("goal").get(null)).isEmpty() && ((JSONArray)agent.getField("journal").get(null)).length()==0,"New conversation inherited old journal");
        main(test,()->require((boolean)invoke(agent,"selectThread",new Class[]{Context.class,String.class},context,first[0]),"Saved conversation could not be selected"));
        require("paused".equals(agent.getField("state").get(null)) && ((JSONArray)field(service,"history")).toString().contains("Saved fixture action history"),"Conversation switch lost paused state/action history");
        require(((JSONArray)agent.getField("journal").get(null)).toString().contains("Saved fixture result"),"Conversation switch lost the result");
        checks.put("threadsSwitchCancelsOldGenerationAndRestoresJournal",true);
        // Hold the serial worker so continuation can be inspected and paused before any observation/inference.
        ExecutorService worker=(ExecutorService)field(service,"worker");CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);
        worker.execute(()->{held.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});require(held.await(3,TimeUnit.SECONDS),"Serial worker fixture did not hold");
        try {
            main(test,()->{agent.getField("state").set(null,"done");invoke(service,"startTask",new Class[]{String.class},"Now verify the saved screen");invoke(service,"pauseTask",new Class[]{String.class},"Fixture pause before inference");});
            require(first[0].equals(invoke(agent,"activeThreadId",new Class[]{Context.class},context)) && "thread-continuation-session".equals(field(service,"sessionId")),"Completed follow-up created another thread/session");
            require(((String)agent.getField("goal").get(null)).contains("THREAD_FOLLOWUP_FIXTURE") && ((String)agent.getField("goal").get(null)).contains("Now verify the saved screen"),"Completed follow-up replaced conversation instructions");
            require(((JSONArray)field(service,"history")).toString().contains("Saved fixture action history") && ((JSONArray)agent.getField("journal").get(null)).toString().contains("Saved fixture result"),"Completed follow-up cleared conversation history/result");
            checks.put("threadsCompletedFollowupPreservesThreadSessionAndContext",true);
        }finally{release.countDown();}
        main(test,()->{
            for(int i=0;i<420;i++)invoke(service,"log",new Class[]{String.class,String.class},"Tango","Verbose fixture action "+i);
            require(((JSONArray)agent.getField("journal").get(null)).toString().contains("Initial phone task") && ((JSONArray)agent.getField("journal").get(null)).toString().contains("Saved fixture result"),"Verbose task logs displaced prompt/result chat messages");
            JSONObject latest=(JSONObject)invoke(agent,"getThread",new Class[]{Context.class,String.class},context,first[0]);require(latest.getJSONArray("journal").toString().contains("Verbose fixture action 419"),"Thread detail omits latest unsaved journal entries");
            invoke(agent,"selectThread",new Class[]{Context.class,String.class},context,original);invoke(agent,"deleteThread",new Class[]{Context.class,String.class},context,first[0]);
            if(!second[0].equals(original))invoke(agent,"deleteThread",new Class[]{Context.class,String.class},context,second[0]);
        });
        checks.put("threadsLongRunsRetainPromptAndResultAndExposeLatestDetail",true);
    }
    public static void run(Instrumentation test,Context context,JSONObject checks) throws Exception {
        storage(context.getClassLoader().loadClass("com.tango.phone.ThreadStore"),new File(context.getCacheDir(),"thread-checks"),checks);service(test,context,checks);
    }
    public static void main(String[] args) throws Exception {
        JSONObject checks=new JSONObject();File temporary=FilesTemp.directory();try{storage(Class.forName("com.tango.phone.ThreadStore"),temporary,checks);System.out.println(checks.toString(2));}finally{deleteFiles(temporary);}
    }
    private static final class FilesTemp {static File directory() throws Exception{return java.nio.file.Files.createTempDirectory("tango-thread-checks").toFile();}}
}
