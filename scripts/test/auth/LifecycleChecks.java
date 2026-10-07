package com.tango.authsmoke;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline system-browser launch, real loopback callbacks, token exchange and Keystore persistence. */
public final class LifecycleChecks {
    private static final String CLIENT="oaiapp_fixture",SCOPES="openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private final Instrumentation test;
    private final Context context;
    private Object auth;
    private final Activity activity;
    private final ProtocolChecks protocol;
    private final JSONObject checks;
    private final Class<?> listenerType;
    private final AtomicReference<Intent> browser=new AtomicReference<>();
    private final AtomicInteger exchanges=new AtomicInteger(),refreshes=new AtomicInteger(),revocations=new AtomicInteger();
    private final AtomicInteger phoneRequests=new AtomicInteger(),pausePending=new AtomicInteger(),pauseDelivered=new AtomicInteger(),stopPending=new AtomicInteger(),stopDelivered=new AtomicInteger();
    private volatile String mode="identity",expectedVerifier="",expectedRedirect="",expectedClient=CLIENT;
    private volatile String lastRefresh="fixture-refresh-1",nextAccess="fixture-access-2";
    private volatile long responseDelay;
    private volatile boolean repliedTokens;
    private LifecycleChecks(Instrumentation test,Context context,Object auth,Activity activity,ProtocolChecks protocol,JSONObject checks) throws Exception {
        this.test=test;this.context=context;this.auth=auth;this.activity=activity;this.protocol=protocol;this.checks=checks;
        listenerType=context.getClassLoader().loadClass("com.tango.phone.ChatGptAuth$Listener");
    }
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    private static Object field(Object target,String name) throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void set(Object target,String name,Object value) throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static Object call(Object target,String name,Class<?>[] types,Object... args) throws Exception {
        try{return target.getClass().getMethod(name,types).invoke(target,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw new RuntimeException(e.getCause());}
    }
    private String token() throws Exception { return (String)call(auth,"accessToken",new Class[]{}); }
    private boolean plan() throws Exception {return (boolean)call(auth,"hasPlanAccess",new Class[]{});}
    private Map<String,String> form(String body) throws Exception {
        Map<String,String> result=new LinkedHashMap<>();for(String part:body.split("&")){int split=part.indexOf('=');result.put(URLDecoder.decode(part.substring(0,split),"UTF-8"),URLDecoder.decode(part.substring(split+1),"UTF-8"));}return result;
    }
    private JSONObject tokens(boolean refresh) throws Exception {
        JSONObject result=new JSONObject().put("access_token",refresh?nextAccess:"fixture-access-1").put("token_type","Bearer").put("expires_in",3600);
        if(mode.equals("identity"))result.put("scope","openid profile email");
        else result.put("scope",mode.equals("scope_lost")?"openid profile email":SCOPES).put("refresh_token",refresh?"fixture-refresh-"+(refreshes.get()+1):"fixture-refresh-1");
        if(!refresh)result.put("id_token",protocol.token(protocol.claims(expectedClient,(String)field(field(auth,"pending"),"nonce")),mode.equals("verify_timeout")?"fixture-rsa-staged-signin":"fixture-rsa"));
        if(refresh&&mode.equals("refresh_verify_timeout")){JSONObject identity=protocol.claims(expectedClient,"unused");identity.remove("nonce");result.put("id_token",protocol.token(identity,"fixture-rsa-staged-refresh"));repliedTokens=true;}
        if(refresh&&mode.equals("refresh_bad_identity")){JSONObject identity=protocol.claims(expectedClient,"unused");identity.remove("nonce");identity.put("sub","fixture-other-subject");result.put("id_token",protocol.token(identity));}
        if(refresh&&mode.equals("refresh_bad_schema"))result.put("token_type","MAC");
        return result;
    }
    private FixtureHttp.Response respond(java.net.URL url,String method,String body) throws Exception {
        if("api.openai.com".equals(url.getHost())) {
            if(url.getPath().equals("/v1/models"))return new FixtureHttp.Response(200,new JSONObject().put("models",new JSONArray().put(new JSONObject().put("slug","fixture-model").put("display_name","Fixture model").put("visibility","list"))).toString());
            require(url.getPath().equals("/v1/responses"),"Unexpected phone provider path");phoneRequests.incrementAndGet();
            JSONObject action;
            if(body.contains("NATIVE_ASK_RESUME"))action=body.contains("Board Game Club")?new JSONObject().put("type","done").put("summary","Offline task complete"):new JSONObject().put("type","ask").put("question","Which club name?");
            else if(body.contains("NATIVE_PAUSE_DELAY")||body.contains("NATIVE_STOP_DELAY")) {
                boolean pause=body.contains("NATIVE_PAUSE_DELAY");(pause?pausePending:stopPending).incrementAndGet();
                // Simulates an HTTP transport whose response arrives even after disconnect/cancel.
                long until=SystemClock.uptimeMillis()+1800;while(SystemClock.uptimeMillis()<until)try{Thread.sleep(100);}catch(InterruptedException ignored){}
                (pause?pauseDelivered:stopDelivered).incrementAndGet();action=new JSONObject().put("type","home").put("reason","Delayed fixture action");
            } else throw new AssertionError("Unexpected phone task request");
            JSONObject response=new JSONObject().put("type","response.completed").put("response",new JSONObject().put("status","completed")
                .put("output",new JSONArray().put(new JSONObject().put("type","message").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",new JSONObject().put("action",action).toString()))))));
            if(body.contains("NATIVE_STOP_DELAY")||(body.contains("NATIVE_ASK_RESUME")&&body.contains("Board Game Club")))
                return new FixtureHttp.Response(200,response.getJSONObject("response").put("object","response").toString(),"application/json");
            return new FixtureHttp.Response(200,"data: "+response+"\n\n","text/event-stream");
        }
        require("auth.openai.com".equals(url.getHost()),"Auth contacted a non-OpenAI endpoint");
        if(url.getPath().equals("/.well-known/openid-configuration"))return new FixtureHttp.Response(200,new JSONObject().put("issuer","https://auth.openai.com")
            .put("authorization_endpoint","https://auth.openai.com/api/accounts/authorize").put("token_endpoint","https://auth.openai.com/api/accounts/oauth/token")
            .put("revocation_endpoint","https://auth.openai.com/api/accounts/oauth/revoke").put("jwks_uri","https://auth.openai.com/.well-known/jwks.json")
            .put("id_token_signing_alg_values_supported",new JSONArray().put("RS256")).toString());
        if(url.getPath().equals("/.well-known/jwks.json")){
            if(mode.equals("preflight_timeout"))throw new java.net.SocketTimeoutException("https://auth.openai.com/diagnostic?code=private-fixture-code&refresh_token=private-fixture-refresh");
            if((mode.equals("verify_timeout")||mode.equals("refresh_verify_timeout"))&&repliedTokens)throw new java.net.SocketTimeoutException("code=private-fixture-code refresh_token=private-fixture-refresh");
            JSONObject published=new JSONObject(protocol.jwks.toString());
            if(mode.equals("plan")||mode.equals("refresh"))published.getJSONArray("keys").put(new JSONObject(protocol.jwks.getJSONArray("keys").getJSONObject(0).toString()).put("kid","fixture-rsa-staged-signin"));
            if(mode.equals("refresh"))published.getJSONArray("keys").put(new JSONObject(protocol.jwks.getJSONArray("keys").getJSONObject(0).toString()).put("kid","fixture-rsa-staged-refresh"));
            return new FixtureHttp.Response(200,published.toString());
        }
        Map<String,String> values=form(body);
        require("POST".equals(method),"Token operation was not POST");
        if(url.getPath().equals("/api/accounts/oauth/revoke")) {
            revocations.incrementAndGet();require("refresh_token".equals(values.get("token_type_hint")),"Wrong revocation hint");return new FixtureHttp.Response(200,"");
        }
        require(url.getPath().equals("/api/accounts/oauth/token"),"Unexpected auth path");
        require("https://api.openai.com/v1".equals(values.get("resource")),"OAuth resource missing");
        require(expectedClient.equals(values.get("client_id")),"Token exchange used dynamic or wrong client");
        if("authorization_code".equals(values.get("grant_type"))) {
            exchanges.incrementAndGet();require(expectedVerifier.equals(values.get("code_verifier")),"PKCE verifier changed");require(expectedRedirect.equals(values.get("redirect_uri")),"Callback URI changed on exchange");
            if(mode.equals("invalid_grant"))return new FixtureHttp.Response(400,"{\"error\":\"invalid_grant\"}");
            if(mode.equals("token_timeout"))throw new java.net.SocketTimeoutException("code=private-fixture-code refresh_token=private-fixture-refresh");
            if(mode.equals("token_eof"))throw new java.io.EOFException("code=private-fixture-code refresh_token=private-fixture-refresh");
            if(mode.equals("token_tls"))throw new javax.net.ssl.SSLHandshakeException("code=private-fixture-code refresh_token=private-fixture-refresh");
            JSONObject issued=tokens(false);
            if(mode.equals("verify_timeout"))repliedTokens=true;
            return new FixtureHttp.Response(200,issued.toString());
        }
        require("refresh_token".equals(values.get("grant_type")),"Unexpected token grant");
        int count=refreshes.incrementAndGet();require(lastRefresh.equals(values.get("refresh_token")),"Refresh reused an old rotating token");require(!values.containsKey("scope"),"Refresh should retain original scope");
        if(responseDelay>0)Thread.sleep(responseDelay);
        if(mode.equals("transient"))return new FixtureHttp.Response(503,"{\"error\":\"server_error\"}");
        if(mode.equals("terminal"))return new FixtureHttp.Response(400,"{\"error\":\"invalid_grant\"}");
        JSONObject tokens=tokens(true);lastRefresh=tokens.getString("refresh_token");return new FixtureHttp.Response(200,tokens.toString());
    }
    private final class ListenerState {
        final CountDownLatch finished=new CountDownLatch(1);
        volatile String error="";
        final Object proxy=Proxy.newProxyInstance(listenerType.getClassLoader(),new Class[]{listenerType},(p,m,a)->{
            if(m.getName().equals("onSuccess"))finished.countDown();else if(m.getName().equals("onError")){error=(String)a[0];finished.countDown();}return null;
        });
        void await(boolean success) throws Exception {
            if(!finished.await(10,TimeUnit.SECONDS)) {
                Object pending=field(auth,"pending");
                throw new AssertionError("Auth completion did not arrive: foreground="+field(auth,"foreground")+", pending="+(pending!=null)+", fixtureListener="+(pending!=null&&field(pending,"listener")==proxy)+", status="+call(auth,"status",new Class[]{}));
            }
            require(success==error.isEmpty(),"Unexpected auth result: "+error);
        }
    }
    private Object start(Activity activity,String client,boolean consent,ListenerState state) throws Exception {
        browser.set(null);
        test.runOnMainSync(()->{try{call(auth,"setForeground",new Class[]{boolean.class},true);call(auth,"beginSignIn",new Class[]{Activity.class,String.class,boolean.class,listenerType},activity,client,consent,state.proxy);}catch(Exception e){throw new RuntimeException(e);}});
        long until=SystemClock.uptimeMillis()+5000;while((field(auth,"pending")==null||browser.get()==null)&&SystemClock.uptimeMillis()<until)SystemClock.sleep(30);
        Object pending=field(auth,"pending");require(pending!=null&&browser.get()!=null,"Authorization browser did not launch");
        // The intercepted external browser still schedules an Activity pause/resume. Wait for
        // that real lifecycle to settle before simulating the user returning to this fixture.
        test.waitForIdleSync();SystemClock.sleep(250);
        test.runOnMainSync(()->{try{call(auth,"attachListener",new Class[]{listenerType},state.proxy);call(auth,"setForeground",new Class[]{boolean.class},true);}catch(Exception e){throw new RuntimeException(e);}});
        expectedVerifier=(String)field(pending,"verifier");expectedRedirect=(String)field(pending,"redirectUri");
        Uri address=browser.get().getData();
        require("https".equals(address.getScheme())&&"auth.openai.com".equals(address.getHost()),"Authorization launched an untrusted browser address");
        require(expectedRedirect.equals(address.getQueryParameter("redirect_uri")),"Browser callback did not match listener");
        require("S256".equals(address.getQueryParameter("code_challenge_method")),"PKCE method missing");
        String challenge=(String)context.getClassLoader().loadClass("com.tango.phone.AuthProtocol").getMethod("challenge",String.class).invoke(null,expectedVerifier);
        require(challenge.equals(address.getQueryParameter("code_challenge")),"Browser PKCE challenge wrong");
        require(address.getQueryParameter("nonce").equals(field(pending,"nonce")),"Browser nonce wrong");
        require(address.getQueryParameter("state").equals(field(pending,"state")),"Browser state wrong");
        require(address.getQueryParameter("ext_agent_host_id").equals(call(auth,"hostId",new Class[]{})),"Host identity missing");
        require(address.getQueryParameter("scope").contains("chatgpt.tokens.use.direct"),"Direct scope not requested");
        return pending;
    }
    private String callback(Object pending,String target,String host) throws Exception {
        Uri uri=Uri.parse((String)field(pending,"redirectUri"));
        try(Socket socket=new Socket("127.0.0.1",uri.getPort())) {
            socket.setSoTimeout(4000);socket.getOutputStream().write(("GET "+target+" HTTP/1.1\r\nHost: "+host+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[1024];int n;while((n=socket.getInputStream().read(bytes))!=-1)out.write(bytes,0,n);return out.toString("UTF-8");
        }
    }
    private String callback(Object pending,String state,boolean initial) throws Exception {
        Uri uri=Uri.parse((String)field(pending,"redirectUri"));
        return callback(pending,"/auth/callback?code=fixture-code&state="+state+(initial?"&client_id="+expectedClient:""),"127.0.0.1:"+uri.getPort());
    }
    private void expire() throws Exception {set(field(auth,"current"),"expiresAt",System.currentTimeMillis()/1000-1);}
    private JSONObject stored() throws Exception {
        Class<?> secrets=context.getClassLoader().loadClass("com.tango.phone.SecretStore");
        return new JSONObject((String)secrets.getMethod("get",Context.class,String.class).invoke(null,context,"chatgpt_sessions_v1"));
    }
    private JSONObject selectedStored() throws Exception {
        JSONObject root=stored();JSONArray accounts=root.getJSONArray("accounts");for(int i=0;i<accounts.length();i++)if(root.getString("active_client_id").equals(accounts.getJSONObject(i).getString("client_id")))return accounts.getJSONObject(i);throw new AssertionError("Saved active account missing");
    }
    private void run() throws Exception {
        FixtureHttp.responder=this::respond;
        Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_VIEW.equals(intent.getAction())&&intent.getData()!=null&&"auth.openai.com".equals(intent.getData().getHost())){browser.set(new Intent(intent));return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}return null;}};
        test.addMonitor(monitor);
        try {
            failureScenarios();mode="identity";int lifecycleExchangeBase=exchanges.get();
            String host=(String)call(auth,"hostId",new Class[]{});
            ListenerState badState=new ListenerState();Object rejected=start(activity,"",false,badState);
            require("dynamic_agent_client".equals(browser.get().getData().getQueryParameter("client_id")),"First login did not dynamically register");
            require("Tango".equals(browser.get().getData().getQueryParameter("agent_name_hint")),"Wrong app registration name");
            require(callback(rejected,"bad",true).startsWith("HTTP/1.1 400"),"Wrong-state network callback accepted");badState.await(false);
            require(exchanges.get()==lifecycleExchangeBase,"Wrong state exchanged a code");checks.put("networkCallbackStateDeniedBeforeExchange",true);
            ListenerState identity=new ListenerState();Object first=start(activity,"",false,identity);
            Uri firstUri=Uri.parse(expectedRedirect);
            require(callback(first,"/auth/callback?code=x&state="+field(first,"state")+"&client_id="+CLIENT,"localhost:"+firstUri.getPort()).startsWith("HTTP/1.1 400"),"Wrong Host callback accepted");checks.put("networkCallbackHostDenied",true);
            mode="invalid_grant";require(callback(first,(String)field(first,"state"),true).startsWith("HTTP/1.1 200"),"Initial registration callback failed");identity.await(false);
            require(!plan(),"Invalid authorization code granted AI access");
            mode="identity";ListenerState retry=new ListenerState();first=start(activity,suggestedClient(),false,retry);
            require(CLIENT.equals(browser.get().getData().getQueryParameter("client_id"))&&!browser.get().getData().getQueryParameterNames().contains("agent_name_hint"),"invalid_grant did not retain issued registration");checks.put("invalidGrantReusesIssuedClient",true);
            String firstState=(String)field(first,"state");require(callback(first,firstState,false).startsWith("HTTP/1.1 200"),"Real loopback callback failed");retry.await(true);
            require((boolean)call(auth,"isSignedIn",new Class[]{})&&!plan(),"Identity-only grant did not gate plan access");checks.put("identityOnlySessionCannotRunAi",true);
            boolean replayClosed=false;try{callback(first,firstState,false);}catch(Exception expected){replayClosed=true;}require(replayClosed&&exchanges.get()==lifecycleExchangeBase+2,"Callback replay exchanged another token");checks.put("callbackSingleUseReplayDenied",true);
            mode="plan";ListenerState granted=new ListenerState();Object second=start(activity,CLIENT,true,granted);
            require(CLIENT.equals(browser.get().getData().getQueryParameter("client_id"))&&!browser.get().getData().getQueryParameterNames().contains("agent_name_hint"),"Returning login replaced registration");
            require("consent".equals(browser.get().getData().getQueryParameter("prompt")),"Missing plan consent request");require(browser.get().getData().getQueryParameter("id_token_hint")!=null,"Returning identity hint omitted");
            int beforeReturn=exchanges.get();call(auth,"setForeground",new Class[]{boolean.class},false);
            require(callback(second,(String)field(second,"state"),false).startsWith("HTTP/1.1 200"),"Returning callback failed");SystemClock.sleep(700);
            require(exchanges.get()==beforeReturn&&granted.finished.getCount()==1,"Background callback exchanged code before foreground return");checks.put("backgroundCallbackWaitsForForeground",true);
            ListenerState recovered=new ListenerState();call(auth,"attachListener",new Class[]{listenerType},recovered.proxy);call(auth,"setForeground",new Class[]{boolean.class},true);recovered.await(true);
            require(granted.finished.getCount()==1,"Replaced activity listener received late success");checks.put("pendingListenerReattachmentCompletesAfterReturn",true);
            require(plan(),"Validated direct plan access missing");checks.put("officialLoopbackPkceExchange",true);checks.put("issuedClientReusedForConsent",true);
            require(host.equals(call(auth,"hostId",new Class[]{})),"Host changed across sign-ins");checks.put("stablePhoneHostIdentity",true);
            JSONObject saved=selectedStored();require("fixture-refresh-1".equals(saved.getString("refresh_token")),"Refresh token not persisted");checks.put("keystoreSessionPersistence",true);
            WireTransportChecks.run(context,auth,checks);
            if(!plan())freshPlanSession();
            expire();mode="refresh";String refreshed=token();require("fixture-access-2".equals(refreshed),"Refresh did not return new access token");
            saved=selectedStored();require(lastRefresh.equals(saved.getString("refresh_token"))&&refreshed.equals(saved.getString("access_token")),"Token rotation not saved together");checks.put("refreshRotationAtomic",true);
            expire();nextAccess="fixture-access-3";int before=refreshes.get();CountDownLatch ready=new CountDownLatch(4),done=new CountDownLatch(4);AtomicReference<Throwable> failure=new AtomicReference<>();
            for(int i=0;i<4;i++)new Thread(()->{ready.countDown();try{ready.await();require("fixture-access-3".equals(token()),"Concurrent refresh returned stale access");}catch(Throwable e){failure.set(e);}finally{done.countDown();}}).start();
            require(done.await(10,TimeUnit.SECONDS)&&failure.get()==null,"Concurrent refresh failed: "+failure.get());require(refreshes.get()==before+1,"Concurrent refresh raced rotating tokens");checks.put("refreshSerialized",true);
            refreshStageRecovery();
            invalidRefreshCandidates();
            expire();mode="transient";boolean transientRejected=false;try{token();}catch(Exception expected){transientRejected=true;}require(transientRejected&&plan(),"Transient refresh failure cleared credentials");checks.put("transientRefreshPreservesSession",true);
            mode="refresh";nextAccess="fixture-access-4";token();
            agentLoop();
            AutonomyChecks.run(test,context,checks);
            FastChecks.run(test,context,checks);
            ThreadChecks.run(test,context,checks);
            AttachmentChecks.run(test,context,checks);
            UiStateChecks.run(test,context,activity,checks);
            VoiceChecks.run(test,context,activity,checks);
            accountBinding(activity);
            expire();mode="scope_lost";boolean scopeRejected=false;try{token();}catch(Exception expected){scopeRejected=true;}require(scopeRejected&&!plan(),"Lost direct scope still permits AI");checks.put("revokedPlanScopeBlocksAi",true);
            // Restore through a fresh verified returning authorization before testing terminal renewal.
            mode="plan";ListenerState again=new ListenerState();Object third=start(activity,CLIENT,true,again);callback(third,(String)field(third,"state"),false);again.await(true);lastRefresh="fixture-refresh-1";
            expire();mode="terminal";boolean terminalRejected=false;try{token();}catch(Exception expected){terminalRejected=true;}require(terminalRejected&&!plan(),"Terminal refresh failure kept credentials active");require(selectedStored().getString("refresh_token").isEmpty(),"Terminal failure left renewable credentials saved");checks.put("terminalRefreshClearsCredentials",true);
            mode="plan";ListenerState signed=new ListenerState();Object fourth=start(activity,CLIENT,false,signed);callback(fourth,(String)field(fourth,"state"),false);signed.await(true);
            ListenerState out=new ListenerState();call(auth,"signOut",new Class[]{listenerType},out.proxy);require(!plan(),"Sign-out did not immediately block AI");out.await(true);
            saved=selectedStored();require(saved.getString("access_token").isEmpty()&&saved.getString("refresh_token").isEmpty()&&saved.getString("id_token").isEmpty(),"Sign-out retained tokens");require(CLIENT.equals(saved.getString("client_id")),"Sign-out lost registered client");require(revocations.get()==1,"Remote revocation not attempted");checks.put("signOutRevokesAndRetainsRegistration",true);
            require(host.equals(call(auth,"hostId",new Class[]{})),"Sign-out rotated stable host");
            int authRequests=0,modelRequests=0,totalRequests;
            synchronized(FixtureHttp.calls){totalRequests=FixtureHttp.calls.size();for(String request:FixtureHttp.calls){if(request.startsWith("auth.openai.com/"))authRequests++;if(request.equals("api.openai.com/v1/models"))modelRequests++;}}
            checks.put("offlineAuthHttpsRequests",authRequests);checks.put("offlineTotalHttpsRequests",totalRequests);checks.put("offlineModelCatalogRequests",modelRequests);checks.put("offlineTokenExchanges",exchanges.get());
        } finally {test.removeMonitor(monitor);call(auth,"cancelSignIn",new Class[]{});}
    }
    private void accountBinding(Activity activity) throws Exception {
        Class<?> service=context.getClassLoader().loadClass("com.tango.phone.AgentService");
        test.runOnMainSync(()->context.startForegroundService(new Intent().setClassName("com.tango.phone","com.tango.phone.AgentService").setAction("pause")));
        test.runOnMainSync(()->context.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        SystemClock.sleep(400);Object instance=service.getField("instance").get(null);require(instance!=null,"Agent service absent for dispatch gate fixture");
        set(instance,"taskAccount",CLIENT);service.getField("state").set(null,"running");int generation=((AtomicInteger)field(instance,"generation")).get();
        Method canAct=service.getMethod("canAct",int.class);require((boolean)canAct.invoke(null,generation),"Valid account task dispatch was denied");checks.put("matchingAccountDispatchAllowed",true);
        set(instance,"taskAccount","oaiapp_other");require(!(boolean)canAct.invoke(null,generation),"A different account could dispatch task actions");checks.put("differentAccountDispatchDenied",true);
        set(instance,"taskAccount",CLIENT);service.getField("state").set(null,"paused");require(!(boolean)canAct.invoke(null,generation),"Paused auth task could dispatch");checks.put("pausedAccountDispatchDenied",true);
        test.runOnMainSync(()->context.stopService(new Intent().setClassName("com.tango.phone","com.tango.phone.AgentService")));SystemClock.sleep(400);
    }
    private String suggestedClient() throws Exception{return (String)call(auth,"suggestedSignInClientId",new Class[]{});}
    private void safeDiagnostic(String error,String phase,String category) throws Exception {
        require(!error.contains("private-fixture-code")&&!error.contains("private-fixture-refresh")&&!error.contains("https://auth.openai.com/diagnostic"),"Auth diagnostics exposed request secrets");
        String lower=error.toLowerCase(java.util.Locale.ROOT);require(lower.contains(phase),"Missing auth failure phase: "+error);require(lower.contains(category)||(category.equals("timeout")&&lower.contains("timed out")),"Missing auth failure category: "+error);
        String text=(String)call(auth,"diagnosticText",new Class[]{});
        require(!text.contains("private-fixture-")&&!text.contains("https://")&&!text.contains("fixture-access")&&!text.contains("fixture-refresh"),"Copyable diagnostic contains credentials or a URL");
        JSONObject diagnostic=new JSONObject(text);String kind=category.equals("timeout")?"TIMEOUT":category.equals("closed")?"CONNECTION_CLOSED":"TLS";
        require((phase.equals("signing keys")?"keys":"code_exchange").equals(diagnostic.getString("phase"))&&kind.equals(diagnostic.getString("kind"))&&"response".equals(diagnostic.getString("stage")),"Copyable diagnostic does not identify the failing native transport phase");
    }
    private void failureScenarios() throws Exception {
        mode="preflight_timeout";set(auth,"cachedKeys",null);browser.set(null);int initial=exchanges.get();ListenerState preflight=new ListenerState();
        test.runOnMainSync(()->{try{call(auth,"setForeground",new Class[]{boolean.class},true);call(auth,"beginSignIn",new Class[]{Activity.class,String.class,boolean.class,listenerType},activity,"",false,preflight.proxy);}catch(Exception e){throw new RuntimeException(e);}});
        preflight.await(false);require(browser.get()==null&&exchanges.get()==initial,"Failed preflight opened browser or exchanged a code");
        safeDiagnostic(preflight.error,"signing keys","timeout");checks.put("failedJwksPreflightDoesNotConsumeCode",true);checks.put("preflightFailureDiagnosticSafe",true);
        for(String failure:new String[]{"token_timeout","token_eof","token_tls"}) {
            mode=failure;ListenerState listener=new ListenerState();String client=failure.equals("token_timeout")?"":suggestedClient();
            Object attempt=start(activity,client,false,listener);int before=exchanges.get();callback(attempt,(String)field(attempt,"state"),client.isEmpty());listener.await(false);
            require(exchanges.get()==before+1,"Authorization code POST was retried after "+failure);require(!plan()&&!(boolean)call(auth,"isSignedIn",new Class[]{}),"Network failure created an unverified identity");
            safeDiagnostic(listener.error,"code exchange",failure.equals("token_timeout")?"timeout":failure.equals("token_eof")?"closed":"tls");
            require(CLIENT.equals(suggestedClient()),"Network failure lost issued registration");
            java.lang.reflect.Constructor<?> constructor=auth.getClass().getDeclaredConstructor(Context.class);constructor.setAccessible(true);Object restarted=constructor.newInstance(context);
            require(CLIENT.equals(call(restarted,"suggestedSignInClientId",new Class[]{}))&&!(boolean)call(restarted,"hasPlanAccess",new Class[]{})&&!(boolean)call(restarted,"isSignedIn",new Class[]{}),"Restart lost pending registration or fabricated identity");
            checks.put(failure+"NoPostRetry",true);checks.put(failure+"SafePhaseDiagnostic",true);checks.put(failure+"RegistrationSurvivesRestart",true);
        }
        mode="verify_timeout";repliedTokens=false;ListenerState failedVerification=new ListenerState();Object verifyAttempt=start(activity,suggestedClient(),false,failedVerification);int beforeVerify=exchanges.get();
        callback(verifyAttempt,(String)field(verifyAttempt,"state"),false);failedVerification.await(false);
        require(exchanges.get()==beforeVerify+1&&!plan(),"Interrupted identity validation repeated exchange or enabled AI");
        java.lang.reflect.Constructor<?> constructor=auth.getClass().getDeclaredConstructor(Context.class);constructor.setAccessible(true);Object restored=constructor.newInstance(context);
        require(!(boolean)call(restored,"hasPlanAccess",new Class[]{})&&CLIENT.equals(call(restored,"suggestedSignInClientId",new Class[]{})),"Staged token reply lost registration or authorized unverified identity");checks.put("unverifiedTokenReplySurvivesRestartWithoutAiAccess",true);
        replaceAuth(restored);
        mode="plan";browser.set(null);ListenerState verifiedRecovery=new ListenerState();test.runOnMainSync(()->{try{
            call(auth,"setForeground",new Class[]{boolean.class},true);call(auth,"beginSignIn",new Class[]{Activity.class,String.class,boolean.class,listenerType},activity,CLIENT,false,verifiedRecovery.proxy);
        }catch(Exception e){throw new RuntimeException(e);}});verifiedRecovery.await(true);
        require(plan()&&exchanges.get()==beforeVerify+1&&browser.get()==null,"Identity validation recovery required another code exchange/browser");checks.put("stagedIdentityReplyResumesWithoutReusingCode",true);
        call(auth,"invalidateCredentials",new Class[]{String.class,String.class},CLIENT,"Fixture account reset");require(!plan(),"Fixture reset did not clear AI access");
    }
    private void replaceAuth(Object replacement) throws Exception {
        Field singleton=auth.getClass().getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,replacement);auth=replacement;set(activity,"auth",replacement);
    }
    private void refreshStageRecovery() throws Exception {
        expire();mode="refresh_verify_timeout";repliedTokens=false;nextAccess="fixture-access-recovered-refresh";int before=refreshes.get();boolean failed=false;
        try{token();}catch(Exception expected){failed=true;}require(failed&&refreshes.get()==before+1,"Post-refresh verification outage did not stage one response");
        JSONObject encrypted=stored();require(encrypted.optJSONArray("staged_tokens")!=null&&encrypted.getJSONArray("staged_tokens").length()>0,"Interrupted refresh reply was not saved");
        java.lang.reflect.Constructor<?> constructor=auth.getClass().getDeclaredConstructor(Context.class);constructor.setAccessible(true);replaceAuth(constructor.newInstance(context));
        mode="refresh";String recovered=token();require(nextAccess.equals(recovered)&&refreshes.get()==before+1,"Restarted renewal repeated rotating-refresh POST");
        JSONObject saved=selectedStored();require(lastRefresh.equals(saved.getString("refresh_token")),"Restarted renewal lost latest replacement refresh token");
        checks.put("rotatedRefreshReplySurvivesRestart",true);checks.put("stagedRefreshRecoveryUsesNoSecondPost",true);
    }
    private void freshPlanSession() throws Exception {
        mode="plan";ListenerState signed=new ListenerState();Object attempt=start(activity,CLIENT,false,signed);
        callback(attempt,(String)field(attempt,"state"),false);signed.await(true);lastRefresh="fixture-refresh-1";
    }
    private void invalidRefreshCandidates() throws Exception {
        for(String failure:new String[]{"refresh_bad_identity","refresh_bad_schema"}) {
            expire();mode=failure;int before=refreshes.get();boolean rejected=false;try{token();}catch(Exception expected){rejected=true;}
            require(rejected&&refreshes.get()==before+1&&!plan()&&!(boolean)call(auth,"isSignedIn",new Class[]{}),"Permanent invalid renewal retained a signed-in session");
            JSONObject saved=selectedStored();require(saved.getString("refresh_token").isEmpty()&&saved.getString("access_token").isEmpty()&&stored().getJSONArray("staged_tokens").length()==0,"Permanent invalid renewal retained renewable credentials/staged reply");
            try{token();throw new AssertionError("Invalid renewal still returns a token");}catch(Exception expected){}
            require(refreshes.get()==before+1,"Invalid renewal replayed a consumed refresh token");checks.put(failure+"ClearsCredentialsWithoutRepost",true);
            freshPlanSession();
        }
        // A replacement refresh token was already issued before JWKS timed out. Expire that
        // encrypted response on disk and restart the auth manager: the old token must not retry.
        expire();mode="refresh_verify_timeout";repliedTokens=false;set(auth,"cachedKeys",null);boolean interrupted=false;
        try{token();}catch(Exception expected){interrupted=true;}require(interrupted,"Expired-stage fixture did not interrupt JWKS validation");
        JSONObject saved=stored();JSONArray staged=saved.getJSONArray("staged_tokens");require(staged.length()==1,"Expired-stage fixture missing encrypted renewal reply");
        staged.getJSONObject(0).put("received_at",System.currentTimeMillis()/1000-7200);
        context.getClassLoader().loadClass("com.tango.phone.SecretStore").getMethod("putAtomic",Context.class,String.class,String.class).invoke(null,context,"chatgpt_sessions_v1",saved.toString());
        java.lang.reflect.Constructor<?> constructor=auth.getClass().getDeclaredConstructor(Context.class);constructor.setAccessible(true);replaceAuth(constructor.newInstance(context));
        mode="refresh";int before=refreshes.get();boolean rejected=false;try{token();}catch(Exception expected){rejected=true;}
        require(rejected&&refreshes.get()==before&&!plan()&&selectedStored().getString("refresh_token").isEmpty()&&stored().getJSONArray("staged_tokens").length()==0,"Expired staged renewal replayed an old token or remained usable");
        checks.put("expiredRefreshReplySurvivesRestartAndFailsClosedWithoutPost",true);freshPlanSession();
    }
    private interface Condition {boolean ready() throws Exception;}
    private void waitFor(Condition condition,String message) throws Exception {long until=SystemClock.uptimeMillis()+12000;while(SystemClock.uptimeMillis()<until){if(condition.ready())return;SystemClock.sleep(100);}throw new AssertionError(message);}
    private void agentLoop() throws Exception {
        Class<?> agent=context.getClassLoader().loadClass("com.tango.phone.AgentService"),controller=context.getClassLoader().loadClass("com.tango.phone.PhoneController");
        Method observe=controller.getMethod("observe",Context.class);
        context.getSharedPreferences("tango",0).edit().putBoolean("listen_on_launch",false).commit();
        startPhoneTask("NATIVE_ASK_RESUME Find the club registration form.","start",null);
        waitFor(()->"waiting".equals(agent.getField("state").get(null)),"Direct native loop never asked");
        require("Which club name?".equals(agent.getField("question").get(null)),"Unexpected native task blocker: "+agent.getField("question").get(null));checks.put("directNativeAskReachedWaiting",true);
        startPhoneTask(null,"resume","Board Game Club");waitFor(()->"done".equals(agent.getField("state").get(null)),"Direct resumed loop did not finish");
        require(((String)agent.getField("goal").get(null)).contains("Follow-up instruction from user: Board Game Club"),"Native follow-up not preserved");checks.put("directNativeResumeReachedDone",true);
        checks.put("directNativeCompletedJsonResumeReachedDone",true);
        startPhoneTask("NATIVE_PAUSE_DELAY Keep Settings open.","start",null);waitFor(()->pausePending.get()==1,"Pause fixture never requested inference");
        Object paused=agent.getField("instance").get(null);test.runOnMainSync(()->{try{call(paused,"pauseTask",new Class[]{String.class},"Native fixture pause");}catch(Exception e){throw new RuntimeException(e);}});
        waitFor(()->pauseDelivered.get()==1,"Delayed pause result not delivered");SystemClock.sleep(600);
        require("paused".equals(agent.getField("state").get(null))&&agent.getField("steps").getInt(null)==0,"Delayed paused response dispatched action");
        require(((JSONObject)observe.invoke(null,context)).optString("packageName").contains("settings"),"Delayed pause Home escaped cancellation");checks.put("directNativeLatePauseResponseDiscarded",true);
        startPhoneTask("NATIVE_STOP_DELAY Keep Settings open.","start",null);waitFor(()->stopPending.get()==1,"Stop fixture never requested inference");
        Object stopped=agent.getField("instance").get(null);test.runOnMainSync(()->{try{call(stopped,"stopTask",new Class[]{});}catch(Exception e){throw new RuntimeException(e);}});
        waitFor(()->stopDelivered.get()==1,"Delayed stop result not delivered");SystemClock.sleep(600);
        require("stopped".equals(agent.getField("state").get(null))&&agent.getField("steps").getInt(null)==0,"Delayed stopped response dispatched action");
        require(((JSONObject)observe.invoke(null,context)).optString("packageName").contains("settings"),"Delayed stopped Home escaped cancellation");checks.put("directNativeLateStopResponseDiscarded",true);checks.put("offlineDirectPhoneRequests",phoneRequests.get());
        checks.put("directNativeLateStopJsonResponseDiscarded",true);
    }
    private void startPhoneTask(String goal,String action,String reply) throws Exception {
        test.runOnMainSync(()->context.startActivity(new Intent().setClassName("com.tango.phone","com.tango.phone.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP)));
        SystemClock.sleep(400);
        test.runOnMainSync(()->{
            context.startForegroundService(new Intent().setClassName("com.tango.phone","com.tango.phone.AgentService").setAction(action).putExtra("newThread",action.equals("start")).putExtra("goal",goal).putExtra("reply",reply));
            context.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
    }
    public static void run(Instrumentation test,Context context,Object auth,Activity activity,ProtocolChecks protocol,JSONObject checks) throws Exception {new LifecycleChecks(test,context,auth,activity,protocol,checks).run();}
}
