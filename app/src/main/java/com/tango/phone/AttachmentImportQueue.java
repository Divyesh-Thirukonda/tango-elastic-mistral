package com.tango.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small local-copy jobs belong to the application, so a screen rotation cannot cancel them. */
public final class AttachmentImportQueue {
    public interface Listener {void onImportsChanged();}
    public static final class State {
        public final boolean busy,canceling;
        public final int completed,total;
        public final String message;
        private State(Job job){busy=job!=null&&job.busy;canceling=busy&&job.signal.isCanceled();completed=job==null?0:job.completed;total=job==null?0:job.total;message=job==null?"":job.message;}
        public String caption(){return canceling?"Canceling…":"Adding "+Math.min(total,completed+1)+" of "+total+"…";}
    }
    private static AttachmentImportQueue instance;
    private final Context context;
    private final SharedPreferences storage;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,Job> jobs=new LinkedHashMap<>();
    private Listener listener;
    private static final class Job {
        final String account,thread;
        final CancellationSignal signal=new CancellationSignal();
        int completed,total;
        boolean busy=true;
        String message="";
        Job(String account,String thread,int total){this.account=account;this.thread=thread;this.total=total;}
    }
    private AttachmentImportQueue(Context context) {
        this.context=context.getApplicationContext();storage=this.context.getSharedPreferences("attachment_imports",0);
        // A killed process cannot retain a document provider's open stream. Explain the interruption,
        // keeping every file already committed to the draft; never retry or send without the user.
        try{JSONArray saved=new JSONArray(storage.getString("jobs","[]"));for(int i=0;i<saved.length();i++){JSONObject entry=saved.getJSONObject(i);Job job=new Job(entry.getString("account"),entry.getString("thread"),entry.optInt("total"));job.completed=entry.optInt("completed");job.busy=false;job.message=entry.optBoolean("busy")?"File import was interrupted. Your added files are saved. Choose the remaining files again.":entry.optString("message");if(entry.optBoolean("busy"))try{new AttachmentStore(this.context.getFilesDir()).discardInterruptedCopy(job.account,job.thread,this.context.getFilesDir());}catch(Exception ignored){}jobs.put(key(job.account,job.thread),job);}}catch(Exception ignored){}
        save();
    }
    public static synchronized AttachmentImportQueue get(Context context){if(instance==null)instance=new AttachmentImportQueue(context);return instance;}
    private static String key(String account,String thread){return account+"\n"+thread;}
    public synchronized void attach(Listener listener){this.listener=listener;}
    public synchronized void detach(Listener listener){if(this.listener==listener)this.listener=null;}
    public synchronized State state(String account,String thread){return new State(jobs.get(key(account,thread)));}
    public synchronized String consumeMessage(String account,String thread){Job job=jobs.get(key(account,thread));if(job==null||job.busy)return "";String message=job.message;jobs.remove(key(account,thread));save();return message;}
    public boolean enqueue(String account,String thread,ArrayList<Uri> selected,Uri camera,boolean captured) {
        final Job job;
        synchronized(this){String key=key(account,thread);Job existing=jobs.get(key);if(existing!=null&&existing.busy)return false;job=new Job(account,thread,camera==null?selected.size():captured?1:0);jobs.put(key,job);save();}
        ArrayList<Uri> files=new ArrayList<>(selected);changed();
        worker.execute(() -> {
            String failure="";
            try {
                if(camera!=null){JSONObject added=AttachmentStore.finishCamera(context,camera,captured,job.signal);if(added!=null)completed(job);}
                else for(Uri uri:files){job.signal.throwIfCanceled();AgentService.addThreadAttachment(context,uri,account,thread,job.signal);completed(job);}
            }catch(OperationCanceledException error){failure=job.completed>0?"Import canceled. Added files are kept.":"Import canceled.";}
            catch(Exception error){failure=job.signal.isCanceled()?"Import canceled. Added files are kept.":error.getMessage()==null?"This file could not be added. Choose it again.":error.getMessage();}
            synchronized(this){job.busy=false;job.message=failure;save();}changed();
        });return true;
    }
    private void completed(Job job){synchronized(this){job.completed++;save();}changed();}
    public void cancel(String account,String thread) {
        Job job;synchronized(this){job=jobs.get(key(account,thread));if(job==null||!job.busy)return;}
        // The same lock brackets final metadata commit: cancel never removes a completed file.
        job.signal.cancel();changed();
    }
    private void changed(){main.post(() -> {Listener current;synchronized(this){current=listener;}if(current!=null)current.onImportsChanged();});}
    private synchronized void save() {
        JSONArray saved=new JSONArray();for(Job job:jobs.values())try{if(job.busy||!job.message.isBlank())saved.put(new JSONObject().put("account",job.account).put("thread",job.thread).put("completed",job.completed).put("total",job.total).put("busy",job.busy).put("message",job.message));}catch(Exception ignored){}
        storage.edit().putString("jobs",saved.toString()).commit();
    }
}
