package com.tango.phone;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Local conversation storage. Every lookup is scoped to the selected ChatGPT registration. */
public final class ThreadStore {
    private final File directory, legacyFile;
    private final AttachmentStore attachmentStore;
    private final Map<String,JSONObject> accounts=new HashMap<>();
    public ThreadStore(File filesDirectory) {
        attachmentStore=new AttachmentStore(filesDirectory);
        directory=new File(filesDirectory,"threads"); legacyFile=new File(filesDirectory,"task.json");
    }
    public synchronized void migrateLegacy() throws Exception {
        File marker=new File(directory,"legacy-migrated");
        if(marker.exists() || !legacyFile.exists()) return;
        JSONObject saved=read(legacyFile);
        String account=saved.optString("taskAccount");
        // A legacy task without an owner must never be exposed to the next account to sign in.
        if(!account.isEmpty() && (!saved.optString("goal").isEmpty() || saved.optJSONArray("journal")!=null)) {
            JSONObject root=root(account); JSONArray threads=root.getJSONArray("threads");
            boolean imported=false; String session=saved.optString("sessionId");
            for(int i=0;i<threads.length();i++) {
                JSONObject snapshot=threads.getJSONObject(i).optJSONObject("snapshot");
                if(!session.isEmpty() && snapshot!=null && session.equals(snapshot.optString("sessionId"))) imported=true;
            }
            if(!imported) {
                JSONObject thread=record(); thread.put("title",title(saved.optString("goal"))).put("snapshot",saved);
                threads.put(thread); root.put("activeThreadId",thread.getString("id")); write(account,root);
            }
        }
        writeFile(marker,"1");
    }
    public synchronized String active(String account) throws Exception {
        JSONObject root=root(account); String active=root.optString("activeThreadId");
        JSONObject selected=find(root,active);
        if(selected!=null && !selected.optBoolean("archived")) return active;
        return chooseActive(account,root);
    }
    public synchronized String create(String account) throws Exception {
        JSONObject root=root(account),selected=find(root,root.optString("activeThreadId"));
        if(selected!=null && !selected.optBoolean("archived") && !hasContent(selected)) {
            selected.remove("snapshot");write(account,root);return selected.getString("id");
        }
        JSONObject thread=record(); root.getJSONArray("threads").put(thread);
        root.put("activeThreadId",thread.getString("id")); write(account,root); return thread.getString("id");
    }
    public synchronized boolean select(String account,String id) throws Exception {
        JSONObject root=root(account),thread=find(root,id);
        if(thread==null || thread.optBoolean("archived")) return false;
        root.put("activeThreadId",id); write(account,root); return true;
    }
    public synchronized JSONObject get(String account,String id) throws Exception {
        JSONObject thread=find(root(account),id); return thread==null ? null : copy(thread);
    }
    public synchronized JSONArray list(String account,String query,boolean archived) throws Exception {
        JSONObject root=root(account); JSONArray threads=root.getJSONArray("threads");
        String needle=query==null ? "" : query.trim().toLowerCase(Locale.ROOT);
        ArrayList<JSONObject> matches=new ArrayList<>();
        for(int i=0;i<threads.length();i++) {
            JSONObject thread=threads.getJSONObject(i);
            if(thread.optBoolean("archived")!=archived) continue;
            if(!hasContent(thread))continue;
            JSONObject snapshot=thread.optJSONObject("snapshot");
            String searchable=thread.optString("title")+"\n"+(snapshot==null ? "" : snapshot.optString("goal")+"\n"+snapshot.optString("journal"));
            if(!needle.isEmpty() && !searchable.toLowerCase(Locale.ROOT).contains(needle)) continue;
            matches.add(new JSONObject().put("id",thread.getString("id")).put("title",thread.optString("title","New chat"))
                .put("createdAt",thread.optLong("createdAt")).put("updatedAt",thread.optLong("updatedAt"))
                .put("archived",archived).put("active",thread.getString("id").equals(root.optString("activeThreadId")))
                .put("state",snapshot==null ? "idle" : snapshot.optString("state","idle"))
                .put("status",snapshot==null ? "Ready when you are." : snapshot.optString("status")));
        }
        matches.sort((left,right)->Long.compare(right.optLong("updatedAt"),left.optLong("updatedAt")));
        JSONArray result=new JSONArray(); for(JSONObject thread:matches) result.put(thread); return result;
    }
    public synchronized void save(String account,String id,JSONObject snapshot) throws Exception {
        JSONObject root=root(account),thread=require(root,id);
        if(!account.equals(snapshot.optString("taskAccount"))) throw new Exception("The conversation belongs to a different account.");
        thread.put("snapshot",copy(snapshot)).put("updatedAt",System.currentTimeMillis());
        if(!thread.optBoolean("renamed") && thread.optString("title").equals("New chat") && !snapshot.optString("goal").isBlank())
            thread.put("title",title(snapshot.optString("goal")));
        write(account,root);
    }
    public synchronized void commitMessage(String account,String id,JSONObject snapshot) throws Exception {
        JSONObject root=root(account),thread=require(root,id);
        if(!account.equals(snapshot.optString("taskAccount")))throw new Exception("The conversation belongs to a different account.");
        JSONArray current=thread.optJSONArray("attachments"),sent=snapshot.optJSONArray("sentAttachments"),remaining=new JSONArray();
        if(current!=null)for(int i=0;i<current.length();i++) {
            JSONObject attachment=current.getJSONObject(i);boolean consumed=false;
            if(sent!=null)for(int j=0;j<sent.length();j++)if(attachment.optString("id").equals(sent.getJSONObject(j).optString("id"))){consumed=true;break;}
            if(!consumed)remaining.put(attachment);
        }
        thread.put("snapshot",copy(snapshot)).put("attachments",remaining).put("updatedAt",System.currentTimeMillis());
        if(!thread.optBoolean("renamed") && thread.optString("title").equals("New chat") && !snapshot.optString("goal").isBlank())thread.put("title",title(snapshot.optString("goal")));
        write(account,root);
    }
    public synchronized void rename(String account,String id,String title) throws Exception {
        String clean=title==null ? "" : title.trim(); if(clean.isEmpty()) throw new Exception("Enter a conversation name.");
        JSONObject root=root(account); require(root,id).put("title",clean.substring(0,Math.min(120,clean.length()))).put("renamed",true);
        write(account,root);
    }
    public synchronized void archive(String account,String id,boolean archived) throws Exception {
        JSONObject root=root(account); require(root,id).put("archived",archived);
        if(archived && id.equals(root.optString("activeThreadId"))) { root.put("activeThreadId",""); chooseActive(account,root); }
        else write(account,root);
    }
    public synchronized void delete(String account,String id) throws Exception {
        JSONObject root=root(account); JSONArray threads=root.getJSONArray("threads"); require(root,id);
        for(int i=0;i<threads.length();i++) if(id.equals(threads.getJSONObject(i).optString("id"))) {threads.remove(i);break;}
        if(id.equals(root.optString("activeThreadId"))) { root.put("activeThreadId",""); chooseActive(account,root); }
        else write(account,root);
        if(!account.isEmpty())attachmentStore.deleteThread(account,id);
    }
    public synchronized void attachments(String account,String id,JSONArray attachments) throws Exception {
        JSONObject root=root(account),thread=require(root,id);thread.put("attachments",new JSONArray(attachments.toString()));write(account,root);
    }
    public synchronized void clear(String account) throws Exception {
        JSONObject root=new JSONObject().put("version",1).put("account",account).put("activeThreadId","").put("threads",new JSONArray());
        accounts.put(account,root); chooseActive(account,root);if(!account.isEmpty())attachmentStore.clear(account);
    }
    public synchronized void draft(String account,String id,String command,String reply) throws Exception {
        stageDraft(account,id,command,reply);write(account,root(account));
    }
    synchronized void stageDraft(String account,String id,String command,String reply) throws Exception {JSONObject thread=require(root(account),id);thread.put("draft",command==null?"":command).put("replyDraft",reply==null?"":reply);}
    synchronized void flushDrafts(String account) throws Exception {write(account,root(account));}
    private String chooseActive(String account,JSONObject root) throws Exception {
        JSONArray threads=root.getJSONArray("threads"); JSONObject newest=null;
        for(int i=0;i<threads.length();i++) {JSONObject candidate=threads.getJSONObject(i); if(!candidate.optBoolean("archived") && (newest==null || candidate.optLong("updatedAt")>=newest.optLong("updatedAt"))) newest=candidate;}
        if(newest==null) {newest=record();threads.put(newest);}
        root.put("activeThreadId",newest.getString("id"));write(account,root);return newest.getString("id");
    }
    private JSONObject root(String account) throws Exception {
        if(account==null) account="";
        JSONObject root=accounts.get(account); if(root!=null) return root;
        File file=file(account);
        root=file.exists() ? read(file) : new JSONObject().put("version",1).put("account",account).put("activeThreadId","").put("threads",new JSONArray());
        if(!account.equals(root.optString("account")) || root.optJSONArray("threads")==null) throw new Exception("This account's conversations could not be read.");
        accounts.put(account,root); return root;
    }
    private static JSONObject record() throws Exception {
        long now=System.currentTimeMillis(); return new JSONObject().put("id",UUID.randomUUID().toString()).put("title","New chat")
            .put("createdAt",now).put("updatedAt",now).put("archived",false).put("draft","").put("replyDraft","");
    }
    private static JSONObject require(JSONObject root,String id) throws Exception {JSONObject thread=find(root,id); if(thread==null) throw new Exception("That conversation is unavailable for this account."); return thread;}
    private static boolean hasContent(JSONObject thread) {
        if((thread.optJSONArray("attachments")!=null && thread.optJSONArray("attachments").length()>0) || thread.optBoolean("renamed") || !thread.optString("draft").isBlank() || !thread.optString("replyDraft").isBlank())return true;
        JSONObject snapshot=thread.optJSONObject("snapshot");JSONArray journal=snapshot==null?null:snapshot.optJSONArray("journal");
        return snapshot!=null && (!snapshot.optString("goal").isBlank() || (journal!=null && journal.length()>0));
    }
    private static JSONObject find(JSONObject root,String id) throws Exception {if(id==null || id.isEmpty())return null; JSONArray threads=root.getJSONArray("threads");for(int i=0;i<threads.length();i++){JSONObject thread=threads.getJSONObject(i);if(id.equals(thread.optString("id")))return thread;}return null;}
    private static String title(String goal) {String line=goal==null ? "" : goal.trim().split("\\r?\\n",2)[0];return line.isBlank()?"New chat":line.substring(0,Math.min(64,line.length()));}
    private static JSONObject copy(JSONObject object) throws Exception {return new JSONObject(object.toString());}
    private File file(String account) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(account.getBytes(StandardCharsets.UTF_8)); StringBuilder name=new StringBuilder();
        for(byte item:digest)name.append(String.format(Locale.ROOT,"%02x",item&255)); return new File(directory,name+".json");
    }
    private void write(String account,JSONObject root) throws Exception {writeFile(file(account),root.toString());}
    private static void writeFile(File file,String value) throws Exception {
        File parent=file.getParentFile(); if(!parent.isDirectory() && !parent.mkdirs()) throw new Exception("Could not create conversation storage.");
        File pending=new File(parent,file.getName()+".pending");
        try(FileOutputStream out=new FileOutputStream(pending)) {out.write(value.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        try {Files.move(pending.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(java.nio.file.AtomicMoveNotSupportedException unsupported) {Files.move(pending.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }
    private static JSONObject read(File file) throws Exception {
        try(FileInputStream in=new FileInputStream(file);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1) {if(out.size()+count>20_000_000)throw new Exception("Conversation storage is too large.");out.write(buffer,0,count);}
            return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        }
    }
}
