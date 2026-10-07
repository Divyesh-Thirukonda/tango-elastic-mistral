package com.tango.phone;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Ordered draft persistence outlives an Activity; each write uses a captured account. */
final class DraftSaveQueue {
    private static final ExecutorService writer=Executors.newSingleThreadExecutor();
    private static final Map<String,Pending> pending=new LinkedHashMap<>();
    private static final Map<String,String> errors=new LinkedHashMap<>();
    private static Future<?> last;
    private static final class Pending {long revision;final ThreadStore store;final String account;Pending(ThreadStore store,String account){this.store=store;this.account=account;}}
    static synchronized void persist(ThreadStore store,String account) {
        String key=System.identityHashCode(store)+":"+account;Pending job=pending.get(key);
        if(job!=null){job.revision++;return;}
        job=new Pending(store,account);pending.put(key,job);final Pending current=job;
        last=writer.submit(() -> {
            while(true){long revision;synchronized(DraftSaveQueue.class){revision=current.revision;}
                String failure="";try{current.store.flushDrafts(current.account);}catch(Exception error){failure="Your draft could not be saved. Free some storage and try again.";}
                synchronized(DraftSaveQueue.class){if(!failure.isBlank())errors.put(current.account,failure);else errors.remove(current.account);if(current.revision==revision){pending.remove(key);return;}}
            }
        });
    }
    /** Lifecycle boundary only: finish the ordered writes before Android can discard the process. */
    static void flush(){Future<?> barrier;synchronized(DraftSaveQueue.class){barrier=last;}if(barrier!=null)try{barrier.get();}catch(Exception ignored){}}
    static synchronized String takeError(String account){String error=errors.remove(account);return error==null?"":error;}
}
