package com.tango.phone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Official public OSS Sign in with ChatGPT flow, executed on the Android phone. No desktop or client secret. */
public final class ChatGptAuth {
    public interface Listener {
        void onStatus(String message);
        void onSuccess();
        void onError(String message);
    }
    private static final String STORE="chatgpt_sessions_v1";
    private static final String DISCOVERY=AuthProtocol.ISSUER+"/.well-known/openid-configuration";
    private static ChatGptAuth instance;
    public static synchronized ChatGptAuth get(Context context) {
        if(instance==null) instance=new ChatGptAuth(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newCachedThreadPool();
    private final Object storageLock=new Object(), refreshLock=new Object(), discoveryLock=new Object();
    private final Object foregroundLock=new Object();
    private final Map<String,Session> sessions=new LinkedHashMap<>();
    private final Map<String,Long> registrations=new LinkedHashMap<>();
    private final Map<String,StagedTokens> stagedTokens=new LinkedHashMap<>();
    private final String hostId;
    private final boolean hostReady;
    private volatile Session current;
    private volatile Pending pending;
    private volatile boolean foreground;
    private volatile long generation;
    private volatile String lastError="", retryClient="";
    private volatile JSONObject lastDiagnostic=new JSONObject();
    private JSONObject discovery, cachedKeys;
    private long discoveryAt, keysAt;

    private ChatGptAuth(Context context) {
        this.context=context;
        String savedHost=context.getSharedPreferences("tango_auth",0).getString("host_id","");
        boolean durableHost=!savedHost.isEmpty();
        if(savedHost.isEmpty()) {
            savedHost="urn:uuid:"+UUID.randomUUID();
            durableHost=context.getSharedPreferences("tango_auth",0).edit().putString("host_id",savedHost).commit();
            if(!durableHost)
                lastError="Could not save this phone's ChatGPT host identity.";
        }
        hostId=savedHost; hostReady=durableHost;
        try {
            String saved=SecretStore.get(context,STORE);
            if(!saved.isEmpty()) {
                JSONObject root=new JSONObject(saved);
                JSONArray list=root.optJSONArray("accounts");
                if(list!=null) for(int i=0;i<list.length();i++) {
                    Session session=Session.read(list.getJSONObject(i));
                    if(session!=null && !sessions.containsKey(session.clientId)) sessions.put(session.clientId,session);
                }
                JSONArray incomplete=root.optJSONArray("pending_registrations");
                if(incomplete!=null) for(int i=0;i<incomplete.length();i++) {
                    JSONObject record=incomplete.getJSONObject(i); String client=record.optString("client_id");
                    if(validClient(client) && !sessions.containsKey(client)) registrations.put(client,record.optLong("issued_at"));
                }
                JSONArray stages=root.optJSONArray("staged_tokens");
                if(stages!=null) for(int i=0;i<stages.length();i++) {
                    StagedTokens stage=StagedTokens.read(stages.getJSONObject(i));
                    if(stage!=null) stagedTokens.put(stage.clientId,stage);
                }
                current=sessions.get(root.optString("active_client_id"));
            }
            String diagnostic=context.getSharedPreferences("tango_auth",0).getString("last_diagnostic","");
            if(!diagnostic.isEmpty()) lastDiagnostic=new JSONObject(diagnostic);
        } catch(Exception ignored) { lastError="The saved ChatGPT session could not be read. Sign in again."; }
    }
    public String hostId() { return hostId; }
    public String activeClientId() { Session value=current; return value==null ? "" : value.clientId; }
    public String email() { Session value=current; return value==null ? "" : value.email; }
    public String error() { return lastError; }
    public String diagnosticText() { return lastDiagnostic.length()==0 ? "" : lastDiagnostic.toString(); }
    public String status() { Pending attempt=pending; return attempt==null ? lastError : attempt.status; }
    public void setForeground(boolean visible) {
        foreground=visible;
        synchronized(foregroundLock) { foregroundLock.notifyAll(); }
    }
    /** Configuration changes attach a fresh UI without canceling an accepted browser callback. */
    public void attachListener(Listener listener) {
        Pending attempt=pending;
        if(attempt!=null) { attempt.listener=listener; emitStatus(listener,attempt.status); }
    }
    /** This is only an OAuth registration suggestion; it is never a signed-in account identity. */
    public String suggestedSignInClientId() {
        Session value=current; if(value!=null) return value.clientId;
        synchronized(storageLock) {
            String chosen=""; long newest=Long.MIN_VALUE;
            for(Map.Entry<String,Long> entry:registrations.entrySet()) if(entry.getValue()>=newest) {chosen=entry.getKey();newest=entry.getValue();}
            return chosen;
        }
    }
    public boolean isSigningIn() { return pending!=null; }
    public boolean isSignedIn() { Session value=current; return value!=null && value.signedIn(); }
    public boolean hasPlanAccess() { Session value=current; return value!=null && value.usable(); }
    public JSONArray accounts() {
        JSONArray result=new JSONArray();
        synchronized(storageLock) {
            for(Session value:sessions.values()) try {
                String identity=value.email.isEmpty() ? (value.name.isEmpty() ? "ChatGPT account" : value.name) : value.email;
                String suffix=value.clientId.substring(Math.max(0,value.clientId.length()-6));
                result.put(new JSONObject().put("client_id",value.clientId).put("subject",value.subject)
                    .put("email",value.email).put("name",value.name).put("label",identity+" · "+suffix)
                    .put("active",current!=null && value.clientId.equals(current.clientId))
                    .put("signed_in",value.signedIn()).put("plan_access",value.usable()));
            } catch(Exception ignored) {}
            for(String client:registrations.keySet()) try {
                String suffix=client.substring(Math.max(0,client.length()-6));
                result.put(new JSONObject().put("client_id",client).put("subject","").put("email","").put("name","")
                    .put("label","Incomplete sign-in · "+suffix).put("active",false).put("signed_in",false).put("plan_access",false).put("pending_registration",true));
            } catch(Exception ignored) {}
        }
        return result;
    }
    public void switchAccount(String clientId) throws Exception {
        cancelPending(false);
        retryClient="";
        synchronized(storageLock) {
            Session selected=sessions.get(clientId);
            if(selected==null && !registrations.containsKey(clientId)) throw new Exception("That ChatGPT account is not saved on this phone.");
            save(sessions,selected==null ? "" : selected.clientId);
            generation++; current=selected; lastError="";
        }
    }
    /** New authorization leaves the currently selected account untouched until a verified result is committed. */
    public void beginSignIn(Activity activity,String savedClientId,boolean requestConsent,Listener listener) {
        cancelPending(false);
        if(!hostReady) { emitError(listener,"Could not securely prepare this phone's ChatGPT host identity. Free some storage and restart Tango."); return; }
        String selected=savedClientId==null ? "" : savedClientId;
        Session existing; StagedTokens staged; boolean registered;
        synchronized(storageLock) { existing=sessions.get(selected); registered=registrations.containsKey(selected); staged=stagedTokens.get(selected); }
        if(!selected.isEmpty() && existing==null && !registered) { emitError(listener,"That ChatGPT registration is not saved on this phone."); return; }
        final String client=selected.isEmpty() ? AuthProtocol.DYNAMIC_CLIENT : selected;
        final Session prior=existing;
        final Pending attempt;
        if(staged!=null && staged.kind.equals("sign_in") && staged.fresh() && !requestConsent) {
            attempt=new Pending(client,prior,listener,staged);
            synchronized(storageLock) { attempt.generation=++generation; pending=attempt; }
            status(attempt,"Resuming verification of your ChatGPT account…");
            worker.execute(()->completeSignIn(attempt,staged));
            return;
        }
        try {
            ServerSocket socket=new ServerSocket();
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),4);
            socket.setSoTimeout(1000);
            attempt=new Pending(socket,client,prior,requestConsent,listener);
            synchronized(storageLock) { attempt.generation=++generation; pending=attempt; }
        } catch(Exception e) { emitError(listener,"Could not start the phone's secure sign-in callback. Try again."); return; }
        status(attempt,"Preparing secure ChatGPT sign-in…");
        worker.execute(()->listen(attempt));
        worker.execute(()-> {
            try {
                JSONObject config=configuration();
                prefetchKeys(config);
                Map<String,String> parameters=new LinkedHashMap<>();
                parameters.put("client_id",attempt.clientId);
                if(attempt.initial) parameters.put("agent_name_hint","Tango");
                parameters.put("ext_agent_host_id",hostId);
                if(prior!=null) {
                    if(!prior.idToken.isEmpty()) parameters.put("id_token_hint",prior.idToken);
                    if(!prior.email.isEmpty()) parameters.put("login_hint",prior.email);
                }
                parameters.put("response_type","code"); parameters.put("redirect_uri",attempt.redirectUri);
                parameters.put("scope",AuthProtocol.SCOPES); parameters.put("resource",AuthProtocol.RESOURCE);
                parameters.put("state",attempt.state); parameters.put("nonce",attempt.nonce);
                parameters.put("code_challenge_method","S256"); parameters.put("code_challenge",AuthProtocol.challenge(attempt.verifier));
                if(requestConsent) parameters.put("prompt","consent");
                final Uri address=Uri.parse(config.getString("authorization_endpoint")+"?"+form(parameters));
                main.post(()-> {
                    if(!live(attempt)) return;
                    try {
                        Intent intent=new Intent(Intent.ACTION_VIEW,address).addCategory(Intent.CATEGORY_BROWSABLE);
                        // ACTION_VIEW uses the user's external browser; Tango never embeds the OpenAI login UI.
                        activity.startActivity(intent);
                    } catch(Exception e) { finishError(attempt,"Install or enable a browser to continue with ChatGPT."); }
                });
            } catch(Exception e) { finishFailure(attempt,"prepare",e); }
        });
    }
    public void cancelSignIn() {
        cancelPending(true);
    }
    private void cancelPending(boolean discard) {
        Pending value;
        synchronized(storageLock) {
            value=pending; pending=null; generation++;
            if(discard && value!=null && !value.issuedClient.isEmpty()) {
                StagedTokens stage=stagedTokens.get(value.issuedClient);
                if(stage!=null && stage.kind.equals("sign_in")) { stagedTokens.remove(value.issuedClient); try { save(sessions,activeClientId()); } catch(Exception e) { lastError="Could not clear the incomplete sign-in reply."; } }
            }
        }
        if(value!=null) value.close();
        synchronized(foregroundLock) { foregroundLock.notifyAll(); }
    }
    private boolean live(Pending attempt) { return pending==attempt && generation==attempt.generation && !attempt.consumed; }
    private boolean exchangeLive(Pending attempt) { return pending==attempt && generation==attempt.generation; }
    private void listen(Pending attempt) {
        try {
            while(live(attempt) && SystemClock.elapsedRealtime()<attempt.deadline) {
                try(Socket socket=attempt.server.accept()) {
                    socket.setSoTimeout(4000);
                    String request=readHeaders(socket.getInputStream());
                    String[] lines=request.split("\\r?\\n");
                    String[] first=lines[0].split(" ",3);
                    String host="";
                    for(int i=1;i<lines.length;i++) if(lines[i].regionMatches(true,0,"Host:",0,5)) {
                        if(!host.isEmpty()) throw new Exception("Invalid callback host.");
                        host=lines[i].substring(5).trim();
                    }
                    if(first.length!=3 || !"GET".equals(first[0]) || !host.equals("127.0.0.1:"+attempt.server.getLocalPort())) {
                        respond(socket,400,"Invalid sign-in request."); continue;
                    }
                    // Favicon/probes do not consume the one-time authorization transaction.
                    if(!first[1].startsWith(AuthProtocol.CALLBACK_PATH+"?")) { respond(socket,404,"Not found."); continue; }
                    AuthProtocol.Callback callback;
                    try { callback=AuthProtocol.validateCallback(first[1],attempt.state,attempt.clientId,attempt.initial); }
                    catch(Exception invalid) {
                        respond(socket,400,"Sign-in could not be verified. Return to Tango and start again.");
                        finishError(attempt,invalid.getMessage()); return;
                    }
                    synchronized(storageLock) {
                        if(!live(attempt)) { respond(socket,410,"This sign-in attempt has ended."); return; }
                        attempt.consumed=true; attempt.issuedClient=callback.clientId;
                    }
                    // A closed browser tab must not prevent processing an already validated callback.
                    try { respond(socket,200,callback.error.isEmpty() ? "Authorization received. Return to Tango to finish signing in." : "Sign-in was declined. Return to Tango."); }
                    catch(java.io.IOException ignored) { /* HTML is best effort; the accepted callback remains valid. */ }
                    attempt.close();
                    if(!callback.error.isEmpty()) {
                        finishError(attempt,"access_denied".equals(callback.error) ? "ChatGPT permission was declined. Continue with ChatGPT to try again." : "OpenAI sign-in failed ("+callback.error+"). Try again.");
                    } else {
                        worker.execute(()->exchange(attempt,callback));
                    }
                    return;
                } catch(SocketTimeoutException ignored) { /* bounded wait for the browser callback */ }
            }
            if(live(attempt)) finishError(attempt,"ChatGPT sign-in timed out. Continue with ChatGPT to start again.");
        } catch(Exception e) { if(live(attempt)) finishError(attempt,"The sign-in callback ended unexpectedly. Try again."); }
        finally { attempt.close(); }
    }
    private void exchange(Pending attempt,AuthProtocol.Callback callback) {
        try {
            retainRegistration(attempt,callback.clientId);
            if(!awaitForeground(attempt)) return;
            status(attempt,"Exchanging your ChatGPT authorization…");
            JSONObject config=configuration(); prefetchKeys(config);
            Map<String,String> parameters=new LinkedHashMap<>();
            parameters.put("grant_type","authorization_code"); parameters.put("client_id",callback.clientId);
            parameters.put("code",callback.code); parameters.put("code_verifier",attempt.verifier);
            parameters.put("redirect_uri",attempt.redirectUri); parameters.put("resource",AuthProtocol.RESOURCE);
            if(!exchangeLive(attempt)) return;
            JSONObject tokens=request(config.getString("token_endpoint"),parameters,false);
            StagedTokens stage=new StagedTokens("sign_in",callback.clientId,attempt.nonce,attempt.prior==null ? "" : attempt.prior.subject,"",System.currentTimeMillis()/1000,tokens);
            stageReply(attempt.generation,stage);
            completeSignIn(attempt,stage);
        } catch(Exception e) { finishFailure(attempt,"exchange",e); }
    }
    private void completeSignIn(Pending attempt,StagedTokens stage) {
        String phase="verify";
        try {
            if(!awaitForeground(attempt)) return;
            if(!stage.fresh()) {
                removeStage(stage,attempt); throw new Exception("The saved OpenAI reply expired. Continue with ChatGPT again; your registration was kept.");
            }
            status(attempt,"Verifying and securely saving your ChatGPT account…");
            String idToken=requiredString(stage.tokens,"id_token"),access=requiredString(stage.tokens,"access_token");
            JSONObject identity=verify(idToken,stage.clientId,stage.nonce);
            AuthProtocol.verifyAccessHash(identity,access);
            if(!stage.expectedSubject.isEmpty() && !AuthProtocol.same(stage.expectedSubject,identity.getString("sub")))
                throw new Exception("OpenAI returned a different account. Add it as a separate ChatGPT account instead.");
            Session value=Session.fromTokens(stage.clientId,identity,stage.tokens,null,stage.receivedAt);
            phase="save";
            synchronized(storageLock) {
                if(!exchangeLive(attempt)) return;
                Session collision=sessions.get(value.clientId);
                if(collision!=null && !AuthProtocol.same(collision.subject,value.subject)) throw new Exception("The client registration belongs to a different ChatGPT account.");
                Map<String,Session> updated=new LinkedHashMap<>(sessions); updated.put(value.clientId,value);
                StagedTokens oldStage=stagedTokens.remove(value.clientId); Long oldRegistration=registrations.remove(value.clientId);
                try { save(updated,value.clientId); }
                catch(Exception e) { if(oldStage!=null)stagedTokens.put(value.clientId,oldStage);if(oldRegistration!=null)registrations.put(value.clientId,oldRegistration);throw e; }
                sessions.clear(); sessions.putAll(updated); current=value; pending=null; generation++; retryClient=""; lastError="";
            }
            clearDiagnostic();
            main.post(()->attempt.listener.onSuccess());
        } catch(Exception e) {
            if(phase.equals("verify") && !(e instanceof java.io.IOException) && !(e instanceof OAuthError)) {
                try { removeStage(stage,attempt); } catch(Exception storageFailure) { phase="save"; e=storageFailure; }
            }
            finishFailure(attempt,phase,e);
        }
    }
    private void retainRegistration(Pending attempt,String clientId) throws Exception {
        synchronized(storageLock) {
            if(!exchangeLive(attempt)) return;
            if(!sessions.containsKey(clientId)) {
                Long previous=registrations.put(clientId,System.currentTimeMillis()/1000);
                try { save(sessions,activeClientId()); }
                catch(Exception e) { if(previous==null)registrations.remove(clientId);else registrations.put(clientId,previous);throw e; }
            }
        }
    }
    private void stageReply(long epoch,StagedTokens stage) throws Exception {
        synchronized(storageLock) {
            if(stage.kind.equals("refresh")) {
                Session source=sessions.get(stage.clientId);
                if(source==null || !source.signedIn() || !stage.expectedSubject.equals(source.subject) || !stage.binding.equals(sessionBinding(source)))
                    throw new Exception("The source ChatGPT session changed before its renewal reply could be saved.");
                // A switched account's renewed reply is retained with its original session, never activated here.
            } else if(generation!=epoch) throw new Exception("The ChatGPT sign-in attempt changed before its reply could be saved.");
            StagedTokens previous=stagedTokens.put(stage.clientId,stage);
            try { save(sessions,activeClientId()); }
            catch(Exception e) { if(previous==null)stagedTokens.remove(stage.clientId);else stagedTokens.put(stage.clientId,previous);throw e; }
        }
    }
    private void removeStage(StagedTokens stage,Pending attempt) throws Exception {
        synchronized(storageLock) {
            if(!exchangeLive(attempt) || stagedTokens.get(stage.clientId)!=stage) return;
            stagedTokens.remove(stage.clientId); save(sessions,activeClientId());
        }
    }
    private boolean awaitForeground(Pending attempt) throws Exception {
        if(!foreground) status(attempt,"Authorization received. Return to Tango to finish signing in.");
        synchronized(foregroundLock) {
            while(!foreground && exchangeLive(attempt) && SystemClock.elapsedRealtime()<attempt.deadline) foregroundLock.wait(1000);
        }
        if(!exchangeLive(attempt)) return false;
        if(!foreground) throw new Exception("Return to Tango and continue sign-in. Your ChatGPT registration was kept.");
        return true;
    }
    /** Worker-only. Rotating refreshes are serialized; a late result cannot replace a switched/signed-out account. */
    public String accessToken() throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper()) throw new Exception("ChatGPT requests must run in the background.");
        synchronized(refreshLock) {
            Session before=current; long epoch=generation;
            if(before==null || !before.signedIn()) throw new Exception("Continue with ChatGPT before using Tango.");
            if(!AuthProtocol.planGranted(before.scopes)) throw new Exception("Allow Tango to use your ChatGPT plan to continue.");
            StagedTokens stage;
            synchronized(storageLock) { stage=stagedTokens.get(before.clientId); }
            if(stage!=null && stage.kind.equals("refresh") && stage.expectedSubject.equals(before.subject) && stage.binding.equals(sessionBinding(before)))
                return completeRenewal(before,epoch,stage);
            long now=System.currentTimeMillis()/1000;
            if(before.expiresAt>now+60 || (before.expiresAt>now+5 && before.earliestRefreshAt>now)) return before.accessToken;
            if(before.refreshToken.isEmpty()) throw new Exception("Your ChatGPT session has expired. Continue with ChatGPT again.");
            if(before.earliestRefreshAt>now) throw new Exception("OpenAI has not enabled renewal yet. Try again shortly.");
            try {
                JSONObject config=configuration(); prefetchKeys(config);
                Map<String,String> parameters=new LinkedHashMap<>();
                parameters.put("grant_type","refresh_token"); parameters.put("client_id",before.clientId);
                parameters.put("refresh_token",before.refreshToken); parameters.put("resource",AuthProtocol.RESOURCE);
                synchronized(storageLock) {
                    if(generation!=epoch || current!=before) throw new Exception("The active ChatGPT account changed. Start the request again.");
                }
                // Never retry an ambiguous rotating-token POST; a successful reply is saved before JWKS verification.
                JSONObject tokens=request(config.getString("token_endpoint"),parameters,false);
                stage=new StagedTokens("refresh",before.clientId,before.originalNonce,before.subject,sessionBinding(before),System.currentTimeMillis()/1000,tokens);
                stageReply(epoch,stage);
                return completeRenewal(before,epoch,stage);
            } catch(OAuthError e) {
                if(e.terminalRefresh()) invalidateIfCurrent(before,epoch,"Your ChatGPT session ended. Continue with ChatGPT again.");
                recordDiagnostic("refresh",e);
                throw e;
            } catch(Exception e) { recordDiagnostic("refresh",e); throw e; }
        }
    }
    private String completeRenewal(Session before,long epoch,StagedTokens stage) throws Exception {
        boolean committed=false;
        try {
            if(!stage.fresh()) {
                invalidateIfCurrent(before,epoch,"The saved ChatGPT renewal expired. Continue with ChatGPT again.");
                throw new Exception("The saved ChatGPT renewal expired. Continue with ChatGPT again.");
            }
            JSONObject identity=null;
            if(stage.tokens.has("id_token")) {
                identity=verify(requiredString(stage.tokens,"id_token"),before.clientId,null);
                AuthProtocol.verifyAccessHash(identity,requiredString(stage.tokens,"access_token"));
                if(!AuthProtocol.same(before.subject,identity.getString("sub"))) throw new Exception("The renewed token belongs to another ChatGPT account.");
                if(identity.has("nonce") && !AuthProtocol.same(before.originalNonce,identity.optString("nonce",null)))
                    throw new Exception("The renewed ChatGPT identity had an unexpected nonce. Sign in again.");
            }
            Session after=Session.fromTokens(before.clientId,identity,stage.tokens,before,stage.receivedAt);
            synchronized(storageLock) {
                if(generation!=epoch || current!=before) throw new Exception("The active ChatGPT account changed. Start the request again.");
                Map<String,Session> updated=new LinkedHashMap<>(sessions); updated.put(after.clientId,after);
                StagedTokens savedStage=stagedTokens.remove(after.clientId);
                try { save(updated,after.clientId); }
                catch(Exception e) { if(savedStage!=null)stagedTokens.put(after.clientId,savedStage);throw e; }
                sessions.clear(); sessions.putAll(updated); current=after;
                committed=true;
            }
            if(!AuthProtocol.planGranted(after.scopes)) throw new Exception("Tango no longer has permission to use your ChatGPT plan. Continue with ChatGPT to enable it.");
            return after.accessToken;
        } catch(Exception e) {
            if(!committed && !(e instanceof java.io.IOException) && !(e instanceof OAuthError) && !(e instanceof SecureStoreError))
                invalidateIfCurrent(before,epoch,"The renewed ChatGPT session could not be verified. Continue with ChatGPT again.");
            recordDiagnostic("refresh_verify",e); throw e;
        }
    }
    private static String sessionBinding(Session session) throws Exception {
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest((session.clientId+"\n"+session.subject+"\n"+session.refreshToken).getBytes(StandardCharsets.UTF_8));
        return android.util.Base64.encodeToString(digest,android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING);
    }
    /** Called only when the provider confirms unusable credentials, never for a transient network/503 error. */
    public void invalidateCurrentCredentials(String reason) {
        Session before=current; long epoch=generation;
        if(before!=null) invalidateIfCurrent(before,epoch,reason);
    }
    /** A rejected request from an older account cannot clear a newly selected account's tokens. */
    public void invalidateCredentials(String expectedClientId,String reason) {
        Session before=current; long epoch=generation;
        if(before!=null && AuthProtocol.same(expectedClientId,before.clientId)) invalidateIfCurrent(before,epoch,reason);
    }
    private void invalidateIfCurrent(Session before,long epoch,String reason) {
        synchronized(storageLock) {
            if(current!=before || generation!=epoch) return;
            Session cleared=before.withoutCredentials();
            Map<String,Session> updated=new LinkedHashMap<>(sessions); updated.put(cleared.clientId,cleared);
            stagedTokens.remove(cleared.clientId);
            String storageError="";
            try { save(updated,cleared.clientId); }
            catch(Exception e) { storageError="Could not clear the saved ChatGPT session. Use Android's Clear storage before signing in again."; }
            sessions.clear(); sessions.putAll(updated); current=cleared; generation++; lastError=storageError.isEmpty() ? reason : storageError;
        }
    }
    /** Immediately blocks local requests, preserves the registration, and attempts remote refresh-token revocation. */
    public void signOut(Listener listener) {
        cancelSignIn();
        retryClient="";
        Session before=current;
        if(before==null) { main.post(listener::onSuccess); return; }
        String newestRefresh=before.refreshToken;
        synchronized(storageLock) {
            StagedTokens stage=stagedTokens.get(before.clientId);
            if(stage!=null && stage.kind.equals("refresh") && stage.expectedSubject.equals(before.subject)) newestRefresh=stage.tokens.optString("refresh_token",newestRefresh);
        }
        final String revokeToken=newestRefresh;
        invalidateCurrentCredentials("");
        emitStatus(listener,"Signing out of ChatGPT…");
        worker.execute(()-> {
            boolean revoked=revokeToken.isEmpty();
            for(int attempt=0;attempt<3 && !revoked;attempt++) {
                try {
                    Map<String,String> parameters=new LinkedHashMap<>();
                    parameters.put("token",revokeToken); parameters.put("token_type_hint","refresh_token"); parameters.put("client_id",before.clientId);
                    request(configuration().getString("revocation_endpoint"),parameters,true); revoked=true;
                } catch(Exception e) {
                    if(e instanceof OAuthError && ((OAuthError)e).status<500) break;
                    if(attempt<2) try { Thread.sleep(500L*(attempt+1)); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
            }
            if(revoked) main.post(listener::onSuccess);
            else emitError(listener,"Signed out on this phone. Remote revocation could not be confirmed; disconnect Tango in ChatGPT Settings → Apps.");
        });
    }
    private void finishError(Pending attempt,String message) {
        synchronized(storageLock) {
            if(pending!=attempt || generation!=attempt.generation) return;
            pending=null; generation++; lastError=message;
        }
        attempt.close(); emitError(attempt.listener,message);
    }
    private void status(Pending attempt,String message) { attempt.status=message; emitStatus(attempt.listener,message); }
    private void finishFailure(Pending attempt,String phase,Exception error) {
        if(!exchangeLive(attempt)) return;
        recordDiagnostic(phase,error);
        String text=message(error);
        synchronized(storageLock) {
            if(stagedTokens.containsKey(attempt.issuedClient)) text+=" The reply is saved securely; Continue with ChatGPT will retry verification without exchanging it again.";
            else if(registrations.containsKey(attempt.issuedClient)) text+=" Your registration was kept. Return to Tango and retry sign-in.";
        }
        finishError(attempt,text);
    }
    private void emitStatus(Listener listener,String message) { if(listener!=null) main.post(()->listener.onStatus(message)); }
    private void emitError(Listener listener,String message) { if(listener!=null) main.post(()->listener.onError(message)); }
    private String message(Exception e) {
        if(e instanceof OAuthError) return e.getMessage();
        if(e instanceof AuthNetworkError) return e.getMessage();
        if(e instanceof org.json.JSONException) return "OpenAI returned an invalid identity or token document. Continue with ChatGPT again.";
        if(e instanceof java.io.IOException) return "Could not reach OpenAI. Check your internet connection and try again.";
        String message=e.getMessage(); return message==null ? "ChatGPT sign-in could not be completed. Try again." : message;
    }
    private void save(Map<String,Session> updated,String selected) throws Exception {
        JSONArray records=new JSONArray(); for(Session session:updated.values()) records.put(session.json());
        JSONArray incomplete=new JSONArray(); for(Map.Entry<String,Long> registration:registrations.entrySet()) incomplete.put(new JSONObject().put("client_id",registration.getKey()).put("issued_at",registration.getValue()));
        JSONArray stages=new JSONArray(); for(StagedTokens stage:stagedTokens.values()) stages.put(stage.json());
        try { SecretStore.putAtomic(context,STORE,new JSONObject().put("version",2).put("active_client_id",selected).put("accounts",records).put("pending_registrations",incomplete).put("staged_tokens",stages).toString()); }
        catch(Exception error) { throw new SecureStoreError(error); }
    }
    private JSONObject configuration() throws Exception {
        synchronized(discoveryLock) {
            if(discovery==null || System.currentTimeMillis()-discoveryAt>3600000) {
                JSONObject config=readDocument(DISCOVERY);
                if(!AuthProtocol.ISSUER.equals(config.opt("issuer"))) throw new Exception("OpenAI's identity configuration could not be verified.");
                for(String field:new String[]{"authorization_endpoint","token_endpoint","revocation_endpoint","jwks_uri"}) validateEndpoint(requiredString(config,field));
                JSONArray algorithms=config.optJSONArray("id_token_signing_alg_values_supported");
                boolean supported=false;
                if(algorithms!=null) for(int i=0;i<algorithms.length();i++) if("RS256".equals(algorithms.optString(i))) supported=true;
                if(!supported) throw new Exception("OpenAI's identity-token signing method is unsupported by this Tango version.");
                discovery=config; discoveryAt=System.currentTimeMillis();
            }
            return discovery;
        }
    }
    private JSONObject verify(String token,String client,String nonce) throws Exception {
        JSONObject config=configuration(); String kid=AuthProtocol.keyId(token),uri=config.getString("jwks_uri");
        JSONObject keys;
        synchronized(discoveryLock) {
            if(cachedKeys==null || System.currentTimeMillis()-keysAt>3600000 || !AuthProtocol.containsKey(cachedKeys,kid)) {
                cachedKeys=readDocument(uri); validateKeys(cachedKeys); keysAt=System.currentTimeMillis();
            }
            keys=cachedKeys;
        }
        return AuthProtocol.verifyIdentity(token,keys,client,nonce,System.currentTimeMillis()/1000);
    }
    private void prefetchKeys(JSONObject config) throws Exception {
        synchronized(discoveryLock) {
            if(cachedKeys==null || System.currentTimeMillis()-keysAt>3600000) {
                JSONObject keys=readDocument(config.getString("jwks_uri")); validateKeys(keys);
                cachedKeys=keys; keysAt=System.currentTimeMillis();
            }
        }
    }
    private static void validateKeys(JSONObject keys) throws Exception {
        JSONArray list=keys.optJSONArray("keys"); boolean rsa=false;
        if(list==null || list.length()==0 || list.length()>100) throw new Exception("OpenAI returned an invalid signing-key document.");
        for(int i=0;i<list.length();i++) {
            JSONObject key=list.optJSONObject(i);
            if(key!=null && "RSA".equals(key.optString("kty")) && !key.optString("kid").isEmpty() && !key.optString("n").isEmpty() && !key.optString("e").isEmpty()) rsa=true;
        }
        if(!rsa) throw new Exception("OpenAI did not provide a supported identity signing key.");
    }
    /** Only public, repeatable GETs receive one bounded retry. OAuth POSTs are never replayed. */
    private JSONObject readDocument(String endpoint) throws Exception {
        for(int retry=0;;retry++) {
            try { return request(endpoint,null,false); }
            catch(Exception error) {
                boolean transientFailure=error instanceof AuthNetworkError && ((AuthNetworkError)error).retryable();
                transientFailure|=error instanceof OAuthError && ((OAuthError)error).status>=500;
                if(retry>=1 || !transientFailure) throw error;
                Thread.sleep(350);
            }
        }
    }
    private static void validateEndpoint(String value) throws Exception {
        URI uri=new URI(value);
        if(!"https".equals(uri.getScheme()) || !"auth.openai.com".equals(uri.getHost()) || uri.getPort()!=-1 || uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getQuery()!=null)
            throw new Exception("OpenAI's identity endpoint could not be verified.");
    }
    private JSONObject request(String endpoint,Map<String,String> parameters,boolean emptyOkay) throws Exception {
        validateEndpoint(endpoint);
        String operation=operation(endpoint,parameters),transportStage="connect",requestId=""; int observedStatus=0;
        HttpURLConnection connection=null;
        try {
            connection=(HttpURLConnection)new URL(endpoint).openConnection();
            connection.setConnectTimeout(12000); connection.setReadTimeout(20000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept","application/json"); connection.setRequestProperty("User-Agent","Tango-Android/0.3");
            connection.setRequestProperty("Connection","close");
            if(parameters!=null) {
                byte[] payload=form(parameters).getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded"); connection.setFixedLengthStreamingMode(payload.length);
                transportStage="write";
                try(OutputStream out=connection.getOutputStream()) { out.write(payload); }
            }
            transportStage="response";
            int status=connection.getResponseCode(); observedStatus=status;
            requestId=connection.getHeaderField("x-request-id"); if(requestId==null)requestId=connection.getHeaderField("openai-request-id");
            transportStage="read";
            InputStream source=status>=200 && status<300 ? connection.getInputStream() : connection.getErrorStream();
            String raw="";
            if(source!=null) try(InputStream in=source) {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] chunk=new byte[4096]; int count;
                while((count=in.read(chunk))!=-1) { if(bytes.size()+count>1000000) throw new Exception("OpenAI's sign-in response was too large."); bytes.write(chunk,0,count); }
                raw=bytes.toString(StandardCharsets.UTF_8.name());
            }
            JSONObject result;
            try { result=raw.isEmpty() ? new JSONObject() : new JSONObject(raw); }
            catch(Exception e) { if(status>=200 && status<300) throw new Exception("OpenAI returned an invalid sign-in response."); else result=new JSONObject(); }
            if(status<200 || status>=300) {
                Object error=result.opt("error");
                String code=error instanceof JSONObject ? ((JSONObject)error).optString("code","unknown_error") : (error instanceof String ? (String)error : "unknown_error");
                throw new OAuthError(status,AuthProtocol.safeCode(code),requestId);
            }
            if(raw.isEmpty() && !emptyOkay) throw new Exception("OpenAI returned an empty sign-in response.");
            return result;
        } catch(java.io.IOException e) { throw new AuthNetworkError(operation,transportStage,observedStatus,requestId,e); }
        finally { if(connection!=null)connection.disconnect(); }
    }
    private static String operation(String endpoint,Map<String,String> parameters) {
        if(endpoint.equals(DISCOVERY)) return "discovery";
        if(parameters==null) return "keys";
        if("authorization_code".equals(parameters.get("grant_type"))) return "code_exchange";
        if("refresh_token".equals(parameters.get("grant_type"))) return "refresh";
        return "revocation";
    }
    private void recordDiagnostic(String phase,Exception error) {
        try {
            JSONObject record=new JSONObject().put("phase",phase).put("android_api",android.os.Build.VERSION.SDK_INT).put("at",System.currentTimeMillis()/1000);
            record.put("app_version",context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName);
            if(error instanceof AuthNetworkError) {
                AuthNetworkError network=(AuthNetworkError)error;
                record.put("phase",network.operation).put("stage",network.stage).put("kind",network.kind).put("transport_exception",network.exceptionClass).put("http_status",network.status);
                if(safeRequestId(network.requestId))record.put("request_id",network.requestId);
            } else if(error instanceof OAuthError) {
                OAuthError oauth=(OAuthError)error;
                record.put("kind","HTTP").put("http_status",oauth.status).put("error_code",oauth.code);
                if(safeRequestId(oauth.requestId))record.put("request_id",oauth.requestId);
            } else record.put("kind",error instanceof SecureStoreError || phase.equals("save") ? "SECURE_STORAGE" : "VALIDATION").put("exception",error.getClass().getSimpleName());
            lastDiagnostic=record;
            context.getSharedPreferences("tango_auth",0).edit().putString("last_diagnostic",record.toString()).apply();
        } catch(Exception ignored) { /* No diagnostic contains credentials or a raw exception message. */ }
    }
    private void clearDiagnostic() {
        lastDiagnostic=new JSONObject(); context.getSharedPreferences("tango_auth",0).edit().remove("last_diagnostic").apply();
    }
    private static boolean safeRequestId(String value) { return value!=null && value.matches("[A-Za-z0-9_-]{1,160}"); }
    private static boolean validClient(String value) { return value!=null && value.matches("[A-Za-z0-9_.:-]{1,512}") && !AuthProtocol.DYNAMIC_CLIENT.equals(value); }
    private static String requiredString(JSONObject object,String key) throws Exception {
        Object value=object.opt(key);
        if(!(value instanceof String) || ((String)value).isEmpty() || ((String)value).length()>100000) throw new Exception("OpenAI's response did not contain a valid "+key+".");
        return (String)value;
    }
    private static String form(Map<String,String> values) throws Exception {
        StringBuilder result=new StringBuilder();
        for(Map.Entry<String,String> entry:values.entrySet()) {
            if(result.length()>0) result.append('&');
            result.append(URLEncoder.encode(entry.getKey(),StandardCharsets.UTF_8.name())).append('=').append(URLEncoder.encode(entry.getValue(),StandardCharsets.UTF_8.name()));
        }
        return result.toString();
    }
    private static String readHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); int previous=0,match=0;
        while(bytes.size()<65536) {
            int value=in.read(); if(value<0) break; bytes.write(value);
            if(value=='\n') { if(previous=='\n' || match==2) break; match=1; }
            else if(value=='\r' && match==1) match=2; else match=0;
            previous=value;
        }
        if(bytes.size()>=65536) throw new Exception("Callback request too large.");
        return bytes.toString(StandardCharsets.US_ASCII.name());
    }
    private static void respond(Socket socket,int status,String text) throws Exception {
        byte[] body=("<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><title>Tango</title></head><body><h1>Tango</h1><p>"+text+"</p></body></html>").getBytes(StandardCharsets.UTF_8);
        String headers="HTTP/1.1 "+status+" "+(status==200 ? "OK" : "Error")+"\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Frame-Options: DENY\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n\r\n";
        OutputStream out=socket.getOutputStream(); out.write(headers.getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
    }
    private static final class Pending {
        final ServerSocket server; final String clientId,state,nonce,verifier,redirectUri;
        final Session prior; volatile Listener listener; final boolean initial;
        final long deadline=SystemClock.elapsedRealtime()+600000;
        volatile long generation; volatile boolean consumed;
        volatile String issuedClient="",status="Preparing secure ChatGPT sign-in…";
        Pending(ServerSocket socket,String client,Session selected,boolean consent,Listener listener) {
            server=socket; clientId=client; prior=selected; this.listener=listener; initial=AuthProtocol.DYNAMIC_CLIENT.equals(client);
            state=AuthProtocol.randomValue(32); nonce=AuthProtocol.randomValue(32); verifier=AuthProtocol.randomValue(64);
            redirectUri="http://127.0.0.1:"+socket.getLocalPort()+AuthProtocol.CALLBACK_PATH;
        }
        Pending(String client,Session selected,Listener listener,StagedTokens stage) {
            server=null;clientId=client;prior=selected;this.listener=listener;initial=false;consumed=true;issuedClient=client;
            nonce=stage.nonce;state="";verifier="";redirectUri="";
        }
        void close() { if(server!=null)try { server.close(); } catch(Exception ignored) {} }
    }
    /** Encrypted, unverified replies are recovery data, never authenticated sessions. */
    private static final class StagedTokens {
        final String kind,clientId,nonce,expectedSubject,binding;
        final long receivedAt; final JSONObject tokens;
        StagedTokens(String kind,String client,String nonce,String subject,String binding,long received,JSONObject tokens) throws Exception {
            this.kind=kind;clientId=client;this.nonce=nonce;expectedSubject=subject;this.binding=binding;receivedAt=received;
            this.tokens=new JSONObject(tokens.toString());
        }
        boolean fresh() {
            Object expiry=tokens.opt("expires_in");
            if(!(expiry instanceof Integer) && !(expiry instanceof Long)) return false;
            long seconds=((Number)expiry).longValue(),now=System.currentTimeMillis()/1000;
            return seconds>0 && seconds<=86400 && receivedAt>0 && receivedAt<=now+5 && receivedAt+seconds>now+5;
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("kind",kind).put("client_id",clientId).put("nonce",nonce).put("expected_subject",expectedSubject)
                .put("session_binding",binding).put("received_at",receivedAt).put("token_response",tokens);
        }
        static StagedTokens read(JSONObject record) throws Exception {
            String kind=record.optString("kind"),client=record.optString("client_id");
            JSONObject tokens=record.optJSONObject("token_response");
            if(!validClient(client) || (!kind.equals("sign_in") && !kind.equals("refresh")) || tokens==null) return null;
            String nonce=record.optString("nonce"),subject=record.optString("expected_subject"),binding=record.optString("session_binding");
            if(kind.equals("sign_in") && nonce.isEmpty()) return null;
            if(kind.equals("refresh") && (subject.isEmpty() || binding.isEmpty())) return null;
            return new StagedTokens(kind,client,nonce,subject,binding,record.optLong("received_at"),tokens);
        }
    }
    private static final class Session {
        final String clientId,subject,email,name,idToken,accessToken,refreshToken,scopes,originalNonce;
        final long expiresAt,earliestRefreshAt;
        Session(String client,String sub,String email,String name,String id,String access,String refresh,String scope,long expiry,long earliest,String nonce) {
            clientId=client; subject=sub; this.email=email; this.name=name; idToken=id; accessToken=access; refreshToken=refresh; scopes=scope; expiresAt=expiry; earliestRefreshAt=earliest; originalNonce=nonce;
        }
        boolean signedIn() { return !subject.isEmpty() && (!accessToken.isEmpty() || !refreshToken.isEmpty()); }
        boolean usable() { return signedIn() && AuthProtocol.planGranted(scopes) && (!refreshToken.isEmpty() || (expiresAt>System.currentTimeMillis()/1000+5 && !accessToken.isEmpty())); }
        Session withoutCredentials() { return new Session(clientId,subject,email,name,"","","","",0,0,""); }
        JSONObject json() throws Exception {
            return new JSONObject().put("issuer",AuthProtocol.ISSUER).put("client_id",clientId).put("subject",subject).put("email",email).put("name",name)
                .put("id_token",idToken).put("access_token",accessToken).put("refresh_token",refreshToken).put("scopes",scopes).put("expires_at",expiresAt).put("earliest_refresh_at",earliestRefreshAt).put("original_nonce",originalNonce);
        }
        static Session read(JSONObject object) {
            String client=object.optString("client_id"),sub=object.optString("subject");
            if(!AuthProtocol.ISSUER.equals(object.optString("issuer")) || !client.matches("[A-Za-z0-9_.:-]{1,512}") || AuthProtocol.DYNAMIC_CLIENT.equals(client) || sub.isEmpty()) return null;
            return new Session(client,sub,object.optString("email"),object.optString("name"),object.optString("id_token"),object.optString("access_token"),object.optString("refresh_token"),object.optString("scopes"),object.optLong("expires_at"),object.optLong("earliest_refresh_at"),object.optString("original_nonce"));
        }
        static Session fromTokens(String client,JSONObject identity,JSONObject tokens,Session previous) throws Exception {
            return fromTokens(client,identity,tokens,previous,System.currentTimeMillis()/1000);
        }
        static Session fromTokens(String client,JSONObject identity,JSONObject tokens,Session previous,long receivedAt) throws Exception {
            String access=requiredString(tokens,"access_token");
            if(!access.matches("[\\x21-\\x7e]+")) throw new Exception("OpenAI returned an invalid access token.");
            if(!"Bearer".equalsIgnoreCase(requiredString(tokens,"token_type"))) throw new Exception("OpenAI returned an unsupported token type.");
            Object expiry=tokens.opt("expires_in");
            if(!(expiry instanceof Integer) && !(expiry instanceof Long)) throw new Exception("OpenAI returned invalid session expiry information.");
            long seconds=((Number)expiry).longValue(); if(seconds<=0 || seconds>86400) throw new Exception("OpenAI returned invalid session expiry information.");
            String scope;
            if(tokens.has("scope")) scope=requiredString(tokens,"scope");
            else if(previous!=null) scope=previous.scopes;
            else throw new Exception("OpenAI did not confirm the sign-in permissions.");
            String refresh;
            if(tokens.has("refresh_token")) refresh=requiredString(tokens,"refresh_token");
            else if(previous!=null) throw new Exception("OpenAI did not provide the replacement session token. Sign in again.");
            else refresh="";
            if(!refresh.isEmpty() && !refresh.matches("[\\x21-\\x7e]+")) throw new Exception("OpenAI returned an invalid refresh token.");
            if(AuthProtocol.hasScope(scope,"offline_access") && refresh.isEmpty()) throw new Exception("OpenAI did not provide the renewable session token. Sign in again.");
            String id=tokens.has("id_token") ? requiredString(tokens,"id_token") : (previous==null ? "" : previous.idToken);
            if(identity==null && previous==null) throw new Exception("A verified ChatGPT identity is required.");
            long now=System.currentTimeMillis()/1000,earliest=0;
            if(receivedAt<=0 || receivedAt>now+5 || receivedAt+seconds<=now+5) throw new Exception("OpenAI's saved token reply expired. Continue with ChatGPT again.");
            Object earliestValue=tokens.opt("earliest_refresh_at");
            if(earliestValue instanceof Number) earliest=((Number)earliestValue).longValue();
            else if(earliestValue instanceof String) {
                try { earliest=Long.parseLong((String)earliestValue); }
                catch(NumberFormatException e) { try { earliest=java.time.Instant.parse((String)earliestValue).getEpochSecond(); } catch(Exception ignored) { throw new Exception("OpenAI returned invalid token renewal information."); } }
            }
            if(earliest<0 || earliest>now+86400) throw new Exception("OpenAI returned invalid token renewal information.");
            return new Session(client,identity==null ? previous.subject : identity.getString("sub"),identity==null || !identity.has("email") ? (previous==null ? "" : previous.email) : identity.optString("email"),
                identity==null || !identity.has("name") ? (previous==null ? "" : previous.name) : identity.optString("name"),id,access,refresh,scope,receivedAt+seconds,earliest,
                previous==null ? identity.optString("nonce") : previous.originalNonce);
        }
    }
    private static final class AuthNetworkError extends java.io.IOException {
        final String operation,stage,kind,exceptionClass,requestId; final int status;
        AuthNetworkError(String operation,String stage,int status,String requestId,java.io.IOException cause) {
            super(networkMessage(operation,stage,status,cause));
            this.operation=operation;this.stage=stage;this.status=status;this.requestId=requestId;kind=networkKind(cause);
            Throwable underlying=underlying(cause);exceptionClass=underlying.getClass().getSimpleName();initCause(cause);
        }
        boolean retryable() { return !kind.equals("TLS") && !kind.equals("HTTP_RETRY"); }
        private static Throwable underlying(Throwable error) {
            Throwable current=error;
            for(int i=0;i<8 && current.getCause()!=null;i++)current=current.getCause();
            return current;
        }
        private static String networkKind(Throwable error) {
            for(Throwable item=error;item!=null;item=item.getCause()) {
                if(item instanceof javax.net.ssl.SSLException || item instanceof java.security.cert.CertificateException || item instanceof java.security.cert.CertPathValidatorException) return "TLS";
                if(item instanceof java.net.SocketTimeoutException) return "TIMEOUT";
                if(item instanceof java.net.UnknownHostException) return "DNS";
                if(item instanceof java.net.HttpRetryException) return "HTTP_RETRY";
                if(item instanceof java.io.EOFException) return "CONNECTION_CLOSED";
                if(item instanceof java.net.ConnectException || item instanceof java.net.NoRouteToHostException) return "CONNECT";
                if(item instanceof java.net.SocketException) return "CONNECTION_RESET";
            }
            return "IO";
        }
        private static String networkMessage(String operation,String stage,int status,Throwable error) {
            String label=operation.equals("keys") ? "signing keys" : operation.equals("code_exchange") ? "code exchange" : operation.equals("refresh") ? "session renewal" : operation.equals("revocation") ? "session revocation" : "sign-in setup";
            String kind=networkKind(error),message;
            if(kind.equals("TIMEOUT"))message="OpenAI "+label+" timed out.";
            else if(kind.equals("DNS"))message="Android could not resolve OpenAI's address during "+label+".";
            else if(kind.equals("TLS"))message="Android could not establish a trusted TLS connection for OpenAI "+label+". Check the phone's date/time or network filtering.";
            else if(kind.equals("CONNECTION_CLOSED") || kind.equals("CONNECTION_RESET"))message="The OpenAI connection closed during "+label+".";
            else if(kind.equals("HTTP_RETRY"))message="Android could not read OpenAI's HTTP response during "+label+".";
            else message="Android could not complete OpenAI "+label+".";
            if(status>0)message+=" HTTP "+status+".";
            return message+=" [AUTH_"+operation.toUpperCase(java.util.Locale.US)+"_"+stage.toUpperCase(java.util.Locale.US)+"_"+kind+"]";
        }
    }
    private static final class SecureStoreError extends Exception {
        SecureStoreError(Exception cause) { super("Could not securely save the ChatGPT reply. Free some phone storage and retry.",cause); }
    }
    private static final class OAuthError extends Exception {
        final int status; final String code,requestId;
        OAuthError(int status,String code,String requestId) {
            super(errorMessage(status,code,requestId)); this.status=status; this.code=code; this.requestId=requestId;
        }
        boolean terminalRefresh() {
            return code.equals("invalid_grant") || code.equals("invalid_refresh_token") || code.equals("token_expired") || code.equals("refresh_token_expired")
                || code.equals("refresh_token_invalidated") || code.equals("refresh_token_reused");
        }
        private static String errorMessage(int status,String code,String requestId) {
            String text;
            if("invalid_grant".equals(code)) text="The ChatGPT authorization expired or was already used. Continue with ChatGPT again.";
            else if("invalid_client".equals(code)) text="OpenAI did not accept Tango's client registration. This preview may not be enabled for your account.";
            else if("invalid_scope".equals(code) || "unauthorized_client".equals(code) || "unsupported_grant_type".equals(code)) text="Direct ChatGPT sign-in is not available for this account or this preview configuration.";
            else if(status==403) text="OpenAI blocked this sign-in because of account, workspace, region, or preview policy.";
            else if(status==429) text="OpenAI asked Tango to wait. Try signing in again later.";
            else if(status>=500) text="OpenAI sign-in is temporarily unavailable. Try again later.";
            else text="OpenAI sign-in failed. Try again.";
            text+=" (HTTP "+status+", "+code+")";
            if(requestId!=null && requestId.matches("[A-Za-z0-9_-]{1,160}")) text+=" Request "+requestId+".";
            return text;
        }
    }
}
