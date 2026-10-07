package com.tango.authsmoke;

import android.content.Context;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Full production decide()/HTTP/body path, inside the authenticated Android app. No live requests. */
public final class WireTransportChecks {
    private final Context context;
    private final Object auth;
    private final JSONObject checks;
    private final Class<?> api;
    private final AtomicInteger requests=new AtomicInteger();
    private final AtomicReference<FixtureHttp.Response> reply=new AtomicReference<>();
    private WireTransportChecks(Context context,Object auth,JSONObject checks) throws Exception {
        this.context=context;this.auth=auth;this.checks=checks;api=context.getClassLoader().loadClass("com.tango.phone.ApiClient");
    }
    private static void require(boolean okay,String reason){if(!okay)throw new AssertionError(reason);}
    private static Object field(Object value,String name) throws Exception {Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    private static Object call(Object value,String name,Class<?>[] types,Object...args) throws Exception {
        try{return value.getClass().getMethod(name,types).invoke(value,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw new RuntimeException(e.getCause());}
    }
    private static JSONObject action() throws Exception {
        JSONObject action=new JSONObject().put("type","back").put("reason","Save the offline fixture draft.");
        for(String key:new String[]{"target","text","packageName","url","direction","question","summary","x","y","x2","y2","durationMs"})action.put(key,JSONObject.NULL);
        return new JSONObject().put("action",action);
    }
    private static JSONObject response(String status,String text) throws Exception {
        return new JSONObject().put("id","resp_wire_fixture").put("object","response").put("status",status)
            .put("output",new JSONArray().put(new JSONObject().put("type","message").put("role","assistant")
                .put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",text)))));
    }
    private static String event(JSONObject event){return "data: "+event+"\n\n";}
    private static String completed() throws Exception {return event(new JSONObject().put("type","response.completed").put("response",response("completed",action().toString())));}
    private Object client() throws Exception {return api.getConstructor(Context.class).newInstance(context);}
    private String diagnostic() throws Exception {return (String)call(client(),"diagnosticText",new Class[]{});}
    private JSONObject decide(Object client) throws Exception {
        JSONObject task=new JSONObject().put("goal","WIRE_OFFLINE Save this draft.").put("observation",new JSONObject().put("nodes",new JSONArray()));
        return (JSONObject)call(client,"decide",new Class[]{JSONObject.class},task);
    }
    private void accept(String name,String body,String type) throws Exception {
        reply.set(new FixtureHttp.Response(200,body,type).header("x-request-id","req-wire-fixture"));
        require("back".equals(decide(client()).getJSONObject("action").getString("type")),"Completed body did not produce its structured action: "+name);checks.put(name,true);
        require(diagnostic().isEmpty(),"Successful account-validated inference retained stale diagnostics");
    }
    private Exception reject(String name,String body,String type) throws Exception {
        reply.set(new FixtureHttp.Response(200,body,type).header("x-request-id","req-wire-fixture"));
        Exception rejected=null;try{decide(client());}catch(Exception expected){rejected=expected;}
        require(rejected!=null,"Invalid body produced a phone action: "+name);
        require((boolean)call(auth,"hasPlanAccess",new Class[]{}),"Format/2xx error erased valid account credentials: "+name);checks.put(name,true);return rejected;
    }
    private void errorDiagnostics(String name,JSONObject body,String expectedCode,String expectedParam) throws Exception {
        Exception error=reject(name,body.toString(),"application/json; charset=utf-8");
        require(error.getClass().getName().endsWith("ApiClient$ProviderException"),"JSON admission error lost structured provider exception");
        require(expectedCode.equals(error.getClass().getField("errorCode").get(error)),"JSON error code lost");
        require(expectedParam.equals(error.getClass().getField("param").get(error)),"JSON error parameter lost");
        require("req-wire-fixture".equals(error.getClass().getField("requestId").get(error)),"HTTP request ID lost");
        require(!(boolean)call(error,"shouldRetry",new Class[]{}),"Permanent JSON admission error retried");
        String preserved=(String)error.getClass().getField("responseBody").get(error);
        require(new JSONObject(preserved).toString().equals(body.toString()),"JSON admission body was discarded");checks.put(name+"DiagnosticsPreserved",true);
        require(diagnostic().contains("HTTP: 200")&&diagnostic().contains("Body: json")&&diagnostic().contains("Request: req-wire-fixture"),"Copyable diagnostics lost actual wire metadata");
    }
    private FixtureHttp.Response delayed(String text,String type,CheckedRunnable changed) {
        return new FixtureHttp.Response(200,text,type).header("x-request-id","req-wire-fixture").source(()->new InputStream(){
            final ByteArrayInputStream source=new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));boolean invoked;
            private void once() throws IOException {if(!invoked){invoked=true;try{changed.run();}catch(Exception e){throw new IOException(e);}}}
            @Override public int read() throws IOException {int result=source.read();once();return result;}
            @Override public int read(byte[] bytes,int offset,int length)throws IOException {int count=source.read(bytes,offset,length);once();return count;}
        });
    }
    private interface CheckedRunnable {void run() throws Exception;}
    private void cancellation(String type,String body,String name) throws Exception {
        context.getSharedPreferences("tango",0).edit().putString("chatgpt_inference_diagnostic","fixture-prior-diagnostic").commit();
        Object client=client();reply.set(delayed(body,type,()->call(client,"cancel",new Class[]{})));
        boolean rejected=false;try{decide(client);}catch(Exception expected){rejected=true;}
        require(rejected,"Canceled HTTP response produced action");require((boolean)call(auth,"hasPlanAccess",new Class[]{}),"Cancellation erased credentials");checks.put(name,true);
        require("fixture-prior-diagnostic".equals(diagnostic()),"Canceled request changed prior diagnostic state");
    }
    @SuppressWarnings("unchecked")
    private void accountChanged(String type,String body,String name) throws Exception {
        String original=(String)call(auth,"activeClientId",new Class[]{});Object selected=field(auth,"current");
        Method json=selected.getClass().getDeclaredMethod("json");json.setAccessible(true);JSONObject alternate=new JSONObject(json.invoke(selected).toString()).put("client_id","oaiapp_wire_alternate");
        Method read=selected.getClass().getDeclaredMethod("read",JSONObject.class);read.setAccessible(true);Object second=read.invoke(null,alternate);
        Map<String,Object> sessions=(Map<String,Object>)field(auth,"sessions");sessions.put("oaiapp_wire_alternate",second);
        try {
            context.getSharedPreferences("tango",0).edit().putString("chatgpt_inference_diagnostic","fixture-prior-diagnostic").commit();
            reply.set(delayed(body,type,()->call(auth,"switchAccount",new Class[]{String.class},"oaiapp_wire_alternate")));
            boolean rejected=false;try{decide(client());}catch(Exception expected){rejected=true;}
            require(rejected&&"oaiapp_wire_alternate".equals(call(auth,"activeClientId",new Class[]{})),"Old-account response produced action after switch");checks.put(name,true);
            require("fixture-prior-diagnostic".equals(diagnostic()),"Unvalidated old-account result cleared prior diagnostic state");
        } finally {call(auth,"switchAccount",new Class[]{String.class},original);sessions.remove("oaiapp_wire_alternate");call(auth,"switchAccount",new Class[]{String.class},original);}
    }
    private void run() throws Exception {
        FixtureHttp.Responder previous=FixtureHttp.responder;
        FixtureHttp.responder=(url,method,body)->{
            if(!"api.openai.com".equals(url.getHost()))return previous.answer(url,method,body);
            if(url.getPath().equals("/v1/models"))return new FixtureHttp.Response(200,new JSONObject().put("models",new JSONArray().put(new JSONObject().put("slug","fixture-wire-model").put("display_name","Fixture wire model").put("visibility","list"))).toString());
            require(url.getPath().equals("/v1/responses")&&"POST".equals(method),"Wire fixture was not the real inference POST");
            JSONObject request=new JSONObject(body);require(request.getBoolean("stream")&&!request.getBoolean("store")&&request.has("input")&&request.has("text"),"Wire request lost production shape");
            requests.incrementAndGet();return reply.get();
        };
        try {
            accept("wireStandardSseAccepted",completed(),"text/event-stream; charset=utf-8");
            accept("wireSseWithoutContentTypeAccepted",completed(),null);
            accept("wireSseWithJsonHeaderAccepted",completed(),"application/json");
            accept("wireSseWithPlainHeaderAccepted",completed(),"text/plain");
            String json=response("completed",action().toString()).toString();
            accept("wireCompletedJsonAccepted",json,"application/json");
            accept("wireCompletedJsonWithoutHeaderAccepted"," \n\t"+json,null);
            accept("wireCompletedJsonWithSseHeaderAccepted",json,"text/event-stream");
            errorDiagnostics("wireJson200ErrorRejected",new JSONObject().put("error",new JSONObject().put("code","subscription_sharing_unsupported_capability").put("message","Fixture unsupported capability").put("param","text.format")),"subscription_sharing_unsupported_capability","text.format");
            errorDiagnostics("wireJson200DetailRejected",new JSONObject().put("detail","Fixture admission denied"),"","");
            errorDiagnostics("wireJson200QuotaRejected",new JSONObject().put("error",new JSONObject().put("code","subscription_sharing_usage_limit_exceeded").put("message","Fixture quota").put("param",JSONObject.NULL)),"subscription_sharing_usage_limit_exceeded","");
            reject("wireEmptyBodyRejected","","application/json");
            Exception html=reject("wireHtmlRejected","<!doctype html><html>private_fixture_credential code=private_fixture_code</html>","text/html");
            require(!html.getMessage().contains("private_fixture_"),"HTML body appeared in user diagnostics");checks.put("wireUnexpectedBodyDiagnosticsDoNotExposeBody",true);
            String metadata=diagnostic();require(metadata.contains("Body: html")&&metadata.contains("HTTP: 200")&&metadata.contains("Media: text/html")&&metadata.contains("Request: req-wire-fixture")&&metadata.contains("Model: fixture-")&&!metadata.contains("private_fixture_")&&!metadata.contains("WIRE_OFFLINE")&&!metadata.contains("fixture-access")&&!metadata.contains("fixture-refresh"),"Unexpected-body diagnostic missing safe metadata or leaking response/request body");
            checks.put("wireCopyableFormatDiagnosticsPreserveOnlySafeMetadata",true);
            reject("wireBareActionJsonRejected",action().toString(),"application/json");
            reject("wireEventEnvelopeJsonRejected",new JSONObject().put("type","response.completed").put("response",response("completed",action().toString())).toString(),"application/json");
            reject("wireTrailingJsonGarbageRejected",json+"unexpected trailing bytes","application/json");
            reject("wireUncompletedJsonRejected",response("in_progress",action().toString()).toString(),"application/json");
            reject("wireIncompleteJsonRejected",response("incomplete",action().toString()).toString(),"application/json");
            reject("wireCompletedJsonWithIncompleteDetailsRejected",response("completed",action().toString()).put("incomplete_details",new JSONObject().put("reason","max_output_tokens")).toString(),"application/json");
            reject("wireTruncatedJsonRejected",json.substring(0,json.length()-4),"application/json");
            reject("wireDeltaWithoutCompletionRejected",event(new JSONObject().put("type","response.output_text.delta").put("delta",action().toString())),"text/event-stream");
            reject("wireDoneWithoutCompletionRejected","data: [DONE]\n\n","text/event-stream");
            JSONObject missingStatus=response("completed",action().toString());missingStatus.remove("status");
            reject("wireSseMissingCompletionStatusRejected",event(new JSONObject().put("type","response.completed").put("response",missingStatus)),"text/event-stream");
            reject("wireTruncatedSseRejected",completed().substring(0,completed().length()-6),"application/octet-stream");
            reject("wireFailedJsonRejected",response("failed",action().toString()).put("error",new JSONObject().put("code","server_error").put("message","Fixture failed response")).toString(),"application/json");
            reject("wireRefusalJsonRejected",response("completed",action().toString()).put("output",new JSONArray().put(new JSONObject().put("type","message").put("content",new JSONArray().put(new JSONObject().put("type","refusal").put("refusal","Fixture refusal"))))).toString(),"application/json");
            reject("wireHugeActionJsonRejected",response("completed",new JSONObject().put("action",new JSONObject().put("type","back").put("reason","x".repeat(128001))).toString()).toString(),"application/json");
            JSONObject coerced=response("completed",action().toString());coerced.getJSONArray("output").getJSONObject(0).getJSONArray("content").getJSONObject(0).put("text",action());
            reject("wireObjectTextCoercionRejected",coerced.toString(),"application/json");
            reject("wireObjectDeltaCoercionRejected",event(new JSONObject().put("type","response.output_text.delta").put("delta",action()))+event(new JSONObject().put("type","response.completed").put("response",new JSONObject().put("status","completed"))),"text/event-stream");
            reject("wireJsonBodyLimitRejected",response("completed",action().toString()).put("padding","x".repeat(2000001)).toString(),"application/json");
            reject("wireSseBodyLimitRejected",(": "+"x".repeat(4096)+"\n").repeat(2000)+completed(),"text/event-stream");
            cancellation("text/event-stream",completed(),"wireSseCancellationDuringReadRejected");
            cancellation("application/json",json,"wireJsonCancellationDuringReadRejected");
            accountChanged("text/event-stream",completed(),"wireSseAccountSwitchDuringReadRejected");
            accountChanged("application/json",json,"wireJsonAccountSwitchDuringReadRejected");
            accept("wireSuccessfulValidatedDecideClearsPriorDiagnostics",json,"application/json");
            reply.set(new FixtureHttp.Response(200,event(new JSONObject().put("type","error").put("code","token_revoked").put("message","Fixture revoked token")),"text/event-stream").header("x-request-id","req-wire-fixture"));
            Exception revoked=null;try{decide(client());}catch(Exception expected){revoked=expected;}
            require(revoked!=null&&revoked.getClass().getName().endsWith("ApiClient$ProviderException")&&(boolean)call(revoked,"confirmsInvalidCredentials",new Class[]{}),"Exact SSE token revocation was treated as a format error");
            require(!(boolean)call(auth,"hasPlanAccess",new Class[]{})&&!(boolean)call(auth,"isSignedIn",new Class[]{})&&((String)field(field(auth,"current"),"accessToken")).isEmpty()&&((String)field(field(auth,"current"),"refreshToken")).isEmpty(),"Exact SSE token revocation retained credentials");
            checks.put("wireSse200ExactRevocationClearsCredentials",true);
            checks.put("offlineWireInferenceRequests",requests.get());
        } finally {FixtureHttp.responder=previous;}
    }
    public static void run(Context context,Object auth,JSONObject checks) throws Exception {new WireTransportChecks(context,auth,checks).run();}
}
