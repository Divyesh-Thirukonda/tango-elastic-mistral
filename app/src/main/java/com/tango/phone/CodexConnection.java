package com.tango.phone;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import org.json.JSONObject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Revocable, account-bound USB bridge. Tokens are kept in memory and never include ChatGPT credentials. */
public final class CodexConnection {
    public static final int PORT=8766;
    private static volatile Session session;
    private static volatile String lastError="";
    private static final Handler MAIN=new Handler(Looper.getMainLooper());

    public static synchronized JSONObject connect(Context context) throws Exception {
        ChatGptAuth auth=ChatGptAuth.get(context);
        if(!auth.hasPlanAccess())throw new IllegalStateException("Continue with ChatGPT before connecting Codex.");
        Session current=session;
        if(current!=null && current.valid())return info(context);
        revoke(); lastError="";
        Session created=new Session(context.getApplicationContext(),auth.activeClientId()); session=created;
        try { context.startForegroundService(new Intent(context,CodexConnectionService.class).setAction("connect")); }
        catch(Exception error) {revoke();lastError="Couldn't start the Codex connection.";throw error;}
        return info(context);
    }
    public static synchronized void disconnect(Context context) {
        revoke(); context.stopService(new Intent(context,CodexConnectionService.class));
    }
    private static void revoke() {Session current=session;session=null;if(current!=null)current.close();}
    static synchronized boolean startListener() {
        Session current=session;
        if(current==null || !current.valid()) {revoke();return false;}
        current.start(); return true;
    }
    static synchronized void serviceDestroyed() {revoke();}
    static boolean accountValid() {Session current=session;return current!=null && current.valid();}
    public static synchronized JSONObject info(Context context) {
        Session current=session;
        if(current!=null && !current.valid()) {revoke();current=null;}
        return json("connected",current!=null,"listening",current!=null && current.started,
            "endpoint","http://127.0.0.1:"+PORT,"token",current==null?"":current.token,
            "adbForwardCommand","adb forward tcp:"+PORT+" tcp:"+PORT,"error",lastError);
    }
    private static final class Session {
        final Context context;
        final String account,token;
        final ServerSocket server;
        final Set<Socket> clients=ConcurrentHashMap.newKeySet();
        final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8));
        volatile boolean started,closed;
        Session(Context context,String account) throws Exception {
            this.context=context;this.account=account;
            byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
            token=Base64.encodeToString(bytes,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
            server=new ServerSocket();
            try {server.setReuseAddress(true);server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),PORT),8);}
            catch(Exception error) {server.close();workers.shutdownNow();throw new IOException("The Codex connection port is already in use.",error);}
        }
        boolean valid() {
            ChatGptAuth auth=ChatGptAuth.get(context);
            return !closed && session==this && auth.hasPlanAccess() && !account.isBlank() && account.equals(auth.activeClientId());
        }
        synchronized void start() {
            if(started || closed)return; started=true;
            Thread listener=new Thread(this::accept,"tango-codex-usb");listener.setDaemon(true);listener.start();
        }
        void accept() {
            while(!closed)try {
                Socket client=server.accept();client.setSoTimeout(7000);clients.add(client);
                if(!client.getInetAddress().isLoopbackAddress()) {clients.remove(client);client.close();continue;}
                try {workers.execute(()->serve(client));}
                catch(java.util.concurrent.RejectedExecutionException busy) {clients.remove(client);client.close();}
            } catch(IOException error) {if(!closed){lastError="Codex connection ended. Reconnect in Tango.";close();}}
        }
        void serve(Socket socket) {
            try(Socket client=socket) {
                int status=200;JSONObject response;
                try {
                    CodexConnectionHttp.Request request=CodexConnectionHttp.readHead(client.getInputStream());
                    if(!request.authorized(token))throw new CodexConnectionHttp.Failure(401,"Use the current connection token shown in Tango.");
                    requireValid();
                    String raw=CodexConnectionHttp.readBody(client.getInputStream(),request);
                    JSONObject body;
                    try {body=raw.isBlank()?new JSONObject():new JSONObject(raw);}
                    catch(Exception invalid) {throw new CodexConnectionHttp.Failure(400,"Request body must be a JSON object.");}
                    response=route(request.method,request.path,body);
                    requireValid();
                } catch(CodexConnectionHttp.Failure failure) {status=failure.status;response=json("error",json("code","connection_"+status,"message",failure.getMessage()));}
                catch(Exception error) {status=500;response=json("error",json("code","connection_error","message","Tango couldn't complete this request. Check the phone and try again."));}
                writeResponse(client.getOutputStream(),status,response);
            } catch(IOException ignored) {} finally {clients.remove(socket);}
        }
        void requireValid() throws CodexConnectionHttp.Failure {
            if(!valid())throw new CodexConnectionHttp.Failure(403,"Connection revoked. Sign in and reconnect from Tango.");
        }
        JSONObject route(String method,String path,JSONObject body) throws Exception {
            if(method.equals("GET") && path.equals("/v1/observe")) {
                JSONObject observation=PhoneController.observe(context);requireValid();
                return json("observation",observation);
            }
            return onMain(()-> {
                requireValid();
                if(method.equals("GET")) {
                    if(path.equals("/v1/status"))return status();
                    if(path.equals("/v1/threads"))return json("threads",AgentService.listThreads(context,"",false));
                    if(path.startsWith("/v1/threads/"))return json("thread",thread(path.substring("/v1/threads/".length())));
                }
                if(method.equals("POST")) {
                    if(path.equals("/v1/prompts")) {
                        String prompt=requiredText(body,"prompt"),id=body.optString("threadId","");
                        if(AgentService.state.equals("running") && (id.isBlank()||!id.equals(AgentService.activeThreadId(context))))
                            throw new CodexConnectionHttp.Failure(409,"A thread is running. Steer it, or pause it before starting another.");
                        if(!id.isBlank() && thread(id).optBoolean("archived"))
                            throw new CodexConnectionHttp.Failure(409,"Unarchive this thread in Tango before continuing it.");
                        String mode=body.optString("mode","");
                        if(!mode.isBlank()) {
                            int index=mode.equals("instant")?0:mode.equals("medium")?1:mode.equals("high")?2:-1;
                            if(index<0)throw new CodexConnectionHttp.Failure(400,"Mode must be instant, medium or high.");
                            new ApiClient(context).setMode(index);
                        }
                        MainActivity.prepareForCodexThreadChange();
                        if(id.isBlank())id=AgentService.newThread(context);
                        dispatch("start",id,"goal",prompt);return json("accepted",true,"threadId",id);
                    }
                    String action=path.equals("/v1/steer")?"steer":path.equals("/v1/respond")?"resume":path.equals("/v1/pause")?"pause":path.equals("/v1/stop")?"stop":"";
                    if(!action.isBlank()) {
                        String id=body.optString("threadId","");
                        if(id.isBlank())throw new CodexConnectionHttp.Failure(400,"Specify the threadId to control.");
                        thread(id);
                        if(!id.equals(AgentService.activeThreadId(context)))throw new CodexConnectionHttp.Failure(409,"Select this thread in Tango or send it a new prompt before controlling it.");
                        if((action.equals("steer")||action.equals("resume")) && AgentService.goal.isBlank())throw new CodexConnectionHttp.Failure(409,"Start this thread with a prompt first.");
                        if(action.equals("resume") && !(AgentService.state.equals("waiting")||AgentService.state.equals("paused")))throw new CodexConnectionHttp.Failure(409,"This thread isn't waiting or paused.");
                        dispatch(action,id,"reply",action.equals("steer")||action.equals("resume")?requiredText(body,"reply"):"");
                        return json("accepted",true,"threadId",id);
                    }
                }
                throw new CodexConnectionHttp.Failure(404,"Unknown connection endpoint.");
            });
        }
        JSONObject thread(String id) throws Exception {
            if(!id.matches("[A-Za-z0-9_-]{1,80}"))throw new CodexConnectionHttp.Failure(400,"Invalid threadId.");
            JSONObject thread=AgentService.getThread(context,id);
            if(thread==null)throw new CodexConnectionHttp.Failure(404,"Thread not found for this account.");
            return thread;
        }
        JSONObject status() throws Exception {
            return json("connected",true,"threadId",AgentService.activeThreadId(context),"state",AgentService.state,
                "status",AgentService.status,"question",AgentService.question,"steps",AgentService.steps,
                "mode",ApiClient.modeName(new ApiClient(context).selectedMode()),
                "accessibilityEnabled",PhoneAccessibilityService.instance!=null);
        }
        void dispatch(String action,String id,String field,String value) throws Exception {
            requireValid();
            context.startForegroundService(new Intent(context,AgentService.class).setAction(action).putExtra("threadId",id).putExtra(field,value));
            if(action.equals("start")||action.equals("steer")||action.equals("resume"))MainActivity.handoffToCodex();
        }
        <T> T onMain(java.util.concurrent.Callable<T> callable) throws Exception {
            FutureTask<T> future=new FutureTask<>(callable);MAIN.post(future);
            try {return future.get(10,TimeUnit.SECONDS);}
            catch(java.util.concurrent.ExecutionException error) {if(error.getCause() instanceof Exception)throw (Exception)error.getCause();throw error;}
            finally {if(!future.isDone()){future.cancel(false);MAIN.removeCallbacks(future);}}
        }
        void close() {
            closed=true;try{server.close();}catch(IOException ignored){}
            for(Socket socket:clients)try{socket.close();}catch(IOException ignored){}
            clients.clear();workers.shutdownNow();
        }
    }
    private static String requiredText(JSONObject body,String key) throws CodexConnectionHttp.Failure {
        Object value=body.opt(key);
        if(!(value instanceof String)||((String)value).isBlank()||((String)value).length()>16000)
            throw new CodexConnectionHttp.Failure(400,"Specify "+key+" as text (up to 16000 characters).");
        return ((String)value).trim();
    }
    private static void writeResponse(OutputStream output,int status,JSONObject response) throws IOException {
        byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);
        String reason=status==200?"OK":status==401?"Unauthorized":status==403?"Forbidden":status==404?"Not Found":status==409?"Conflict":status==500?"Internal Server Error":"Bad Request";
        output.write(("HTTP/1.1 "+status+" "+reason+"\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+bytes.length+"\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);output.flush();
    }
    private static JSONObject json(Object... values) {JSONObject result=new JSONObject();try{for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);}catch(Exception ignored){}return result;}
    private CodexConnection() {}
}
