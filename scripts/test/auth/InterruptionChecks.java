package com.tango.authsmoke;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.widget.EditText;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises real lifecycle callbacks, background file copying and interrupted local persistence. */
public final class InterruptionChecks {
    private final Instrumentation test;private final Context context;private final Activity activity;private final JSONObject checks;private final Class<?> agent,storeType,queueType;
    private InterruptionChecks(Instrumentation test,Context context,Activity activity,JSONObject checks)throws Exception{this.test=test;this.context=context;this.activity=activity;this.checks=checks;ClassLoader loader=context.getClassLoader();agent=loader.loadClass("com.tango.phone.AgentService");storeType=loader.loadClass("com.tango.phone.AttachmentStore");queueType=loader.loadClass("com.tango.phone.AttachmentImportQueue");}
    private interface Checked{void run()throws Exception;}
    private interface Condition{boolean ready()throws Exception;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static Object field(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void set(Object target,String name,Object value)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static Object call(Object target,String name,Class<?>[] types,Object...args)throws Exception{Class<?> type=target instanceof Class?(Class<?>)target:target.getClass();java.lang.reflect.Method method=type.getDeclaredMethod(name,types);method.setAccessible(true);try{return method.invoke(target instanceof Class?null:target,args);}catch(InvocationTargetException error){if(error.getCause() instanceof Exception)throw(Exception)error.getCause();throw new AssertionError(error.getCause());}}
    private void main(Checked work)throws Exception{Throwable[] failure={null};test.runOnMainSync(()->{try{work.run();}catch(Throwable error){failure[0]=error;}});if(failure[0] instanceof Exception)throw(Exception)failure[0];if(failure[0]!=null)throw new AssertionError(failure[0]);test.waitForIdleSync();}
    private void waitFor(Condition condition,String message)throws Exception{long until=SystemClock.uptimeMillis()+5000;while(SystemClock.uptimeMillis()<until){if(condition.ready())return;SystemClock.sleep(25);}throw new AssertionError(message);}
    private String account()throws Exception{return(String)call(storeType,"authorizedAccount",new Class[]{Context.class},context);}
    private String thread()throws Exception{return(String)call(agent,"activeThreadId",new Class[]{Context.class},context);}
    private JSONArray attachments()throws Exception{return(JSONArray)call(agent,"threadAttachments",new Class[]{Context.class},context);}
    private boolean resumed()throws Exception{boolean[] result={false};main(()->result[0]=(boolean)field(activity,"resumed"));return result[0];}
    private boolean foreground(String packageName){android.view.accessibility.AccessibilityNodeInfo root=test.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow();if(root==null)return false;try{return packageName.contentEquals(root.getPackageName()==null?"":root.getPackageName());}finally{root.recycle();}}
    private void background()throws Exception {
        main(()->activity.startActivity(new Intent(Settings.ACTION_SETTINGS)));
        waitFor(()->!resumed()&&foreground("com.android.settings"),"Settings did not become foreground after Tango paused");
        // resumed becomes false partway through onPause; a main-thread barrier ensures its
        // cancellation work is complete before inspecting voice state or injecting Back.
        main(()->require(!(boolean)field(activity,"resumed")&&!activity.hasWindowFocus(),"Tango retained focus after the Settings interruption"));
    }
    private void returnToApp()throws Exception {
        if(resumed()&&foreground(context.getPackageName()))return;
        waitFor(()->foreground("com.android.settings"),"Settings is not ready for the interruption return");
        test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitFor(()->resumed()&&foreground(context.getPackageName()),"Activity did not resume after the interruption");
        main(()->require((boolean)field(activity,"resumed"),"Tango paused before its resume callback completed"));
    }
    private void draftAndVoice()throws Exception {
        main(()->{call(activity,"showHome",new Class[]{});((EditText)field(activity,"command")).setText("DRAFT_INTERRUPTION_FIXTURE");});
        waitFor(()->"DRAFT_INTERRUPTION_FIXTURE".equals(call(agent,"threadDraft",new Class[]{Context.class},context)),"Typing was not saved until leaving the screen");checks.put("uiDraftAutosavesWhileEditing",true);
        AtomicInteger sends=new AtomicInteger();main(()->{Runnable send=sends::incrementAndGet;set(activity,"scheduledRun",send);((android.os.Handler)field(activity,"handler")).postDelayed(send,800);});
        background();SystemClock.sleep(950);require(sends.get()==0&&field(activity,"scheduledRun")==null,"Voice countdown fired after interruption");require("DRAFT_INTERRUPTION_FIXTURE".equals(call(agent,"threadDraft",new Class[]{Context.class},context)),"Background interruption lost typed draft");Object reopened=context.getClassLoader().loadClass("com.tango.phone.ThreadStore").getConstructor(File.class).newInstance(context.getFilesDir());JSONObject durable=(JSONObject)call(reopened,"get",new Class[]{String.class,String.class},account(),thread());require("DRAFT_INTERRUPTION_FIXTURE".equals(durable.optString("draft")),"Lifecycle flush left typed draft only in memory");checks.put("uiBackgroundFlushesDraftToDurableStorage",true);returnToApp();
        waitFor(()->((android.widget.TextView)field(activity,"statusView")).getText().toString().contains("Tap send"),"Interrupted voice countdown has no recovery feedback");checks.put("uiBackgroundCancelsVoiceCountdownAndKeepsDraft",true);
        main(()->{call(activity,"microphoneIcon",new Class[]{boolean.class},true);require(field(activity,"recognizer")==null,"Synthetic interruption test must not open the microphone");});
        background();require(!(boolean)field(activity,"listening")&&!(boolean)field(activity,"shouldListen"),"Backgrounding left the microphone armed");returnToApp();waitFor(()->((android.widget.TextView)field(activity,"statusView")).getText().toString().contains("Recording stopped"),"Interrupted recording has no recovery message");checks.put("uiBackgroundStopsRecordingWithoutRestarting",true);
        Bundle saved=new Bundle();main(()->call(activity,"onSaveInstanceState",new Class[]{Bundle.class},saved));require(saved.containsKey("attachmentAccount")&&saved.containsKey("attachmentThread"),"Picker origin is missing from saved state");checks.put("uiPickerOriginSurvivesSavedInstanceState",true);
    }
    private void navigationRestore()throws Exception {
        SharedPreferences preferences=context.getSharedPreferences("tango",0);boolean listen=preferences.getBoolean("listen_on_launch",true),advanced=(boolean)field(activity,"advancedExpanded");preferences.edit().putBoolean("listen_on_launch",true).commit();Bundle saved=new Bundle();int[] scroll={0};AtomicInteger navigation=new AtomicInteger();Throwable failure=null;
        try {
            main(()->{call(activity,"showHome",new Class[]{});EditText editor=(EditText)field(activity,"command");editor.setText("Unfinished composer draft");call(activity,"onNewIntent",new Class[]{Intent.class},new Intent(Intent.ACTION_MAIN));require(field(activity,"command")==editor&&"Unfinished composer draft".contentEquals(editor.getText())&&!(boolean)field(activity,"shouldListen"),"Launcher return overwrote a draft or armed auto-listen while composing");});checks.put("uiLauncherReturnPreservesDraftWithoutAutoListening",true);
            main(()->{set(activity,"advancedExpanded",true);call(activity,"showSettings",new Class[]{});});
            main(()->((android.widget.ScrollView)field(activity,"currentScreen")).scrollTo(0,200));
            main(()->{android.view.View screen=(android.view.View)field(activity,"currentScreen");scroll[0]=screen.getScrollY();require(scroll[0]>0,"Account restoration fixture did not scroll");call(activity,"onSaveInstanceState",new Class[]{Bundle.class},saved);call(activity,"onNewIntent",new Class[]{Intent.class},new Intent(Intent.ACTION_MAIN));require(field(activity,"currentScreen")==screen&&(boolean)field(activity,"settingsVisible")&&!(boolean)field(activity,"shouldListen"),"Launcher return discarded Account or armed automatic recording");call(activity,"showHome",new Class[]{});call(activity,"restoreUiState",new Class[]{Bundle.class},saved);});
            waitFor(()->((android.view.View)field(activity,"currentScreen")).getScrollY()==scroll[0],"Account recreation lost its scroll position");checks.put("uiAccountScrollAndScreenSurviveRestorationAndLauncherReturn",true);
            main(()->{call(activity,"showHome",new Class[]{});call(activity,"showRenameThread",new Class[]{String.class,String.class},thread(),"Unfinished rename");EditText editor=(EditText)field(activity,"sheetEditor");call(activity,"onNewIntent",new Class[]{Intent.class},new Intent(Intent.ACTION_MAIN));require(field(activity,"sheetEditor")==editor&&"Unfinished rename".contentEquals(editor.getText())&&!(boolean)field(activity,"shouldListen"),"Launcher return discarded an unfinished rename");call(activity,"dismissSheetsImmediately",new Class[]{});call(activity,"openDrawer",new Class[]{boolean.class},false);call(activity,"setDrawerProgress",new Class[]{float.class},1f);set(activity,"drawerNavigation",(Runnable)navigation::incrementAndGet);});
            checks.put("uiLauncherReturnPreservesUnfinishedRename",true);background();require(field(activity,"drawerNavigation")==null&&navigation.get()==0,"Queued drawer action survived background interruption");returnToApp();require(navigation.get()==0,"Queued drawer action ran after returning");checks.put("uiBackgroundCancelsQueuedDrawerNavigation",true);
        }catch(Throwable error){failure=error;propagate(error);}
        finally{try{preferences.edit().putBoolean("listen_on_launch",listen).commit();main(()->{set(activity,"advancedExpanded",advanced);call(activity,"dismissSheetsImmediately",new Class[]{});call(activity,"closeDrawer",new Class[]{boolean.class},false);call(activity,"showHome",new Class[]{});});}catch(Throwable cleanup){if(failure!=null)failure.addSuppressed(cleanup);else propagate(cleanup);}}
    }
    private Uri cameraFixture()throws Exception {
        Uri camera=(Uri)call(storeType,"beginCamera",new Class[]{Context.class},context);Bitmap bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xFF4466AA);try(OutputStream output=context.getContentResolver().openOutputStream(camera,"w")){require(bitmap.compress(Bitmap.CompressFormat.JPEG,90,output),"Fixture photo could not be written");}finally{bitmap.recycle();}return camera;
    }
    private void backgroundImport()throws Exception {
        Object queue=call(queueType,"get",new Class[]{Context.class},context);ExecutorService worker=(ExecutorService)field(queue,"worker");CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);
        worker.execute(()->{held.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException ignored){}});require(held.await(3,TimeUnit.SECONDS),"Could not hold attachment worker");
        Uri camera=cameraFixture();String account=account(),thread=thread();Throwable failure=null;
        try {
            main(()->{set(activity,"attachmentAccount",account);set(activity,"attachmentThread",thread);set(activity,"pendingCamera",camera);call(activity,"onActivityResult",new Class[]{int.class,int.class,Intent.class},22,Activity.RESULT_OK,null);});
            require((boolean)field(activity,"importingAttachments"),"Queued camera import has no pending UI state");background();release.countDown();waitFor(()->attachments().length()==1,"File import was lost when Activity paused");
            waitFor(()->{Object state=call(queue,"state",new Class[]{String.class,String.class},account,thread);return!(boolean)state.getClass().getField("busy").get(state);},"Background copy did not complete");returnToApp();main(()->call(activity,"renderAttachments",new Class[]{}));require(!(boolean)field(activity,"importingAttachments"),"Resumed UI still shows finished import as pending");checks.put("uiFileImportCompletesAcrossBackgroundInterruption",true);
            CountDownLatch cancelHeld=new CountDownLatch(1),cancelRelease=new CountDownLatch(1);worker.execute(()->{cancelHeld.countDown();try{cancelRelease.await(10,TimeUnit.SECONDS);}catch(InterruptedException ignored){}});require(cancelHeld.await(3,TimeUnit.SECONDS),"Could not hold cancellation fixture");Uri canceled=cameraFixture();
            try{call(queue,"enqueue",new Class[]{String.class,String.class,ArrayList.class,Uri.class,boolean.class},account,thread,new ArrayList<Uri>(),canceled,true);call(queue,"cancel",new Class[]{String.class,String.class},account,thread);}finally{cancelRelease.countDown();}
            waitFor(()->{Object state=call(queue,"state",new Class[]{String.class,String.class},account,thread);return!(boolean)state.getClass().getField("busy").get(state);},"Canceled import never returned control");require(attachments().length()==1,"Cancel erased a completed attachment or committed the canceled one");checks.put("uiCancelImportKeepsAlreadyAddedFiles",true);
        }catch(Throwable error){failure=error;propagate(error);}
        finally{release.countDown();try{if(!resumed())returnToApp();JSONArray items=attachments();for(int i=0;i<items.length();i++)call(agent,"removeThreadAttachment",new Class[]{Context.class,String.class},context,items.getJSONObject(i).getString("id"));}catch(Throwable cleanup){if(failure!=null)failure.addSuppressed(cleanup);else propagate(cleanup);}}
    }
    private void cancellationAndRecovery()throws Exception {
        File temporary=new File(context.getCacheDir(),"interrupted-attachment-"+UUID.randomUUID());Object store=storeType.getConstructor(File.class).newInstance(temporary);CancellationSignal cancellation=new CancellationSignal();
        InputStream provider=new InputStream(){public int read(){cancellation.cancel();return 65;}public int read(byte[] buffer,int offset,int length){buffer[offset]=65;cancellation.cancel();return 1;}};
        boolean rejected=false;try{call(store,"importStream",new Class[]{String.class,String.class,String.class,String.class,InputStream.class,CancellationSignal.class},"fixture",UUID.randomUUID().toString(),"draft.txt","text/plain",provider,cancellation);}catch(android.os.OperationCanceledException expected){rejected=true;}
        require(rejected&&!hasFiles(temporary),"Canceled streaming copy retained partial private bytes");delete(temporary);checks.put("attachmentCancelDuringCopyRemovesPartialBytes",true);
        File recoveredFiles=new File(context.getCacheDir(),"import-restart-"+UUID.randomUUID());Object recoveredStore=storeType.getConstructor(File.class).newInstance(recoveredFiles);Class<?> threadsType=context.getClassLoader().loadClass("com.tango.phone.ThreadStore");Object threads=threadsType.getConstructor(File.class).newInstance(recoveredFiles);String owner="fixture-recovery",thread=(String)call(threads,"active",new Class[]{String.class},owner);
        JSONObject committed=(JSONObject)call(recoveredStore,"importStream",new Class[]{String.class,String.class,String.class,String.class,InputStream.class},owner,thread,"kept.txt","text/plain",new java.io.ByteArrayInputStream(new byte[]{65}));call(threads,"attachments",new Class[]{String.class,String.class,JSONArray.class},owner,thread,new JSONArray().put(committed));
        JSONObject uncommitted=(JSONObject)call(recoveredStore,"importStream",new Class[]{String.class,String.class,String.class,String.class,InputStream.class},owner,thread,"interrupted.txt","text/plain",new java.io.ByteArrayInputStream(new byte[]{66}));
        SharedPreferences preferences=context.getSharedPreferences("interruption-test",0);preferences.edit().putString("jobs",new JSONArray().put(new JSONObject().put("account",owner).put("thread",thread).put("completed",1).put("total",2).put("busy",true)).toString()).commit();
        Context isolated=new ContextWrapper(context){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return recoveredFiles;}@Override public SharedPreferences getSharedPreferences(String name,int mode){return preferences;}};
        java.lang.reflect.Constructor<?> constructor=queueType.getDeclaredConstructor(Context.class);constructor.setAccessible(true);Object reopened=constructor.newInstance(isolated);
        try{Object state=call(reopened,"state",new Class[]{String.class,String.class},owner,thread);require(!(boolean)state.getClass().getField("busy").get(state)&&(int)state.getClass().getField("completed").get(state)==1&&state.getClass().getField("message").get(state).toString().contains("interrupted"),"Restarted import is stuck loading or lacks recovery feedback");call(recoveredStore,"metadata",new Class[]{String.class,String.class,String.class},owner,thread,committed.getString("id"));boolean removed=false;try{call(recoveredStore,"metadata",new Class[]{String.class,String.class,String.class},owner,thread,uncommitted.getString("id"));}catch(Exception expected){removed=true;}require(removed,"Process interruption left an uncommitted file consuming attachment storage");checks.put("attachmentProcessRestartRecoversInterruptedImportWithoutResending",true);checks.put("attachmentRecoveryKeepsCommittedFilesAndRemovesUncommittedCopies",true);}finally{((ExecutorService)field(reopened,"worker")).shutdownNow();preferences.edit().clear().commit();delete(recoveredFiles);}
    }
    private static boolean hasFiles(File file){if(!file.isDirectory())return file.exists();File[] children=file.listFiles();if(children!=null)for(File child:children)if(hasFiles(child))return true;return false;}
    private static void delete(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);file.delete();}
    private void run()throws Exception {
        String original=(String)call(agent,"threadDraft",new Class[]{Context.class},context);
        Throwable failure=null;
        try{draftAndVoice();navigationRestore();backgroundImport();cancellationAndRecovery();}
        catch(Throwable error){failure=error;propagate(error);}
        finally{try{if(!resumed())returnToApp();main(()->{((EditText)field(activity,"command")).setText(original);call(activity,"rememberDraft",new Class[]{});call(activity,"showHome",new Class[]{});});}catch(Throwable cleanup){if(failure!=null)failure.addSuppressed(cleanup);else propagate(cleanup);}}
    }
    private static void propagate(Throwable failure)throws Exception{if(failure instanceof Exception)throw(Exception)failure;if(failure instanceof Error)throw(Error)failure;throw new RuntimeException(failure);}
    public static void run(Instrumentation test,Context context,Activity activity,JSONObject checks)throws Exception{new InterruptionChecks(test,context,activity,checks).run();}
}
