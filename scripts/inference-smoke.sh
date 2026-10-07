#!/usr/bin/env bash
# Deterministic tests of the production Responses request, model catalog and SSE parser.
# Uses Android's compile stubs + org.json; no sign-in, inference or provider requests.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TASK_TEST="$TASK_ROOT/tools/inference-smoke"
TASK_JAVA="$(cat "$TASK_ROOT/tools/java-home.txt")/bin"
TASK_ANDROID_JAR="$TASK_ROOT/tools/android-sdk/platforms/android-35/android.jar"
mkdir -p "$TASK_TEST/src/com/tango/phone" "$TASK_TEST/classes" "$TASK_ROOT/dist"
if [[ ! -f "$TASK_TEST/json-20240303.jar" ]]; then
  curl -fsSL https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o "$TASK_TEST/json-20240303.jar"
fi
cat > "$TASK_TEST/src/com/tango/phone/ChatGptAuth.java" <<'JAVA'
package com.tango.phone;
import android.content.Context;
// Only a compile seam: tests call the production static parser/request/catalog functions.
final class ChatGptAuth {
    static ChatGptAuth get(Context context) { throw new AssertionError("No authentication or networking in this test."); }
    boolean hasPlanAccess() { return false; }
    boolean isSignedIn() { return false; }
    String activeClientId() { return ""; }
    String accessToken() { throw new AssertionError("No tokens in this test."); }
    void invalidateCredentials(String account,String reason) { throw new AssertionError("No credentials in this test."); }
}
JAVA
cat > "$TASK_TEST/src/com/tango/phone/InferenceSmoke.java" <<'JAVA'
package com.tango.phone;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
public final class InferenceSmoke {
    static int checks;
    static void require(boolean ok,String reason) { if(!ok)throw new AssertionError(reason); checks++; }
    interface Test { void run() throws Exception; }
    static void rejected(Test run,String reason) throws Exception {
        try { run.run(); } catch(Exception expected) { checks++; return; }
        throw new AssertionError(reason);
    }
    static ByteArrayInputStream input(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    static String event(JSONObject value) { return "data: "+value+"\n\n"; }
    static JSONObject action() { return new JSONObject().put("action",new JSONObject().put("type","back").put("reason","Save the email draft.")); }
    static JSONObject completed(String output) {
        return new JSONObject().put("type","response.completed").put("response",new JSONObject().put("status","completed")
            .put("output",new JSONArray().put(new JSONObject().put("type","message").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",output))))));
    }
    static String delta(String text) { return event(new JSONObject().put("type","response.output_text.delta").put("delta",text)); }
    static JSONObject stream(String events) throws Exception { return ApiClient.parseResponseStream(input(events),"req-fixture"); }
    static void requestShape() throws Exception {
        JSONObject task=new JSONObject().put("sessionId","fixture").put("goal","Save a Cornell advisor draft")
            .put("history",new JSONArray().put(new JSONObject().put("userReply","Board Game Club")))
            .put("observation",new JSONObject().put("nodes",new JSONArray()).put("screenshot","aGVsbG8="));
        String original=task.toString();
        JSONObject body=ApiClient.buildRequest(task,"advertised-model");
        require("advertised-model".equals(body.getString("model")),"Hardcoded model replaced account model");
        require(!body.getBoolean("store")&&body.getBoolean("stream"),"SIWC transport flags missing");
        require(body.length()==6,"Unsupported request fields added");
        for(String unsupported:new String[]{"max_output_tokens","previous_response_id","conversation","tools","background","temperature","metadata"})
            require(!body.has(unsupported),"Unsupported SIWC field "+unsupported);
        JSONObject message=body.getJSONArray("input").getJSONObject(0);
        require("user".equals(message.getString("role")),"System message sent instead of instructions");
        JSONArray content=message.getJSONArray("content");
        JSONObject text=new JSONObject(content.getJSONObject(0).getString("text"));
        require(!text.getJSONObject("observation").has("screenshot"),"Screenshot duplicated in text");
        require(text.getJSONArray("history").length()==1,"Task history lost");
        require(content.getJSONObject(1).getString("image_url").startsWith("data:image/jpeg;base64,"),"Screenshot image omitted");
        require(body.getJSONObject("text").getJSONObject("format").getBoolean("strict"),"Strict action schema omitted");
        require(original.equals(task.toString()),"Input task mutated");
        task.getJSONObject("observation").remove("screenshot");
        require(ApiClient.buildRequest(task,"m").getJSONArray("input").getJSONObject(0).getJSONArray("content").length()==1,"Empty screenshot included");
    }
    static void fastFormats() throws Exception {
        JSONObject task=new JSONObject().put("goal","Draft a note").put("observation",new JSONObject().put("nodes",new JSONArray()));
        for(String mode:new String[]{"plan","choice"}) {
            task.put("executionMode",mode);JSONObject body=ApiClient.buildRequest(task,"advertised-model");
            require(body.length()==6&&body.optString("model").equals("advertised-model"),"Fast format changed supported fields/model");
            JSONObject schema=body.getJSONObject("text").getJSONObject("format").getJSONObject("schema");
            require(!schema.getBoolean("additionalProperties")&&schema.getJSONArray("required").length()==2,"Fast root is not strict");
        }
        JSONObject node=new JSONObject().put("id","fresh:1").put("text","Inbox").put("viewId","mail:id/inbox").put("className","android.widget.Button").put("enabled",true).put("clickable",true);
        JSONObject title=new JSONObject().put("id","title").put("text","Mail").put("className","android.widget.TextView");
        JSONObject observation=new JSONObject().put("packageName","mail.app").put("nodes",new JSONArray().put(title).put(node));
        JSONObject step=new JSONObject().put("type","tap").put("packageName","mail.app").put("screen",new JSONObject().put("text","Mail"))
            .put("target",new JSONObject().put("viewId","mail:id/inbox").put("text","Inbox").put("className","android.widget.Button"));
        require(FastPlan.resolve(step,observation).optString("target").equals("fresh:1"),"Plan did not bind current target");
        node.put("enabled",false);require(FastPlan.resolve(step,observation)==null,"Disabled control selected");node.put("enabled",true);
        node.put("text","Send");step.getJSONObject("target").put("text","Send");require(FastPlan.resolve(step,observation)==null,"Queued Send allowed");
        node.put("text","Save");step.getJSONObject("target").put("text","Save");require(FastPlan.resolve(step,observation)==null,"Queued consequential Save allowed");
        node.put("text","Inbox");step.getJSONObject("target").put("text","Inbox");title.put("text","Changed screen");require(FastPlan.resolve(step,observation)==null,"Missing screen marker accepted");title.put("text","Mail");
        node.put("password",true);require(FastPlan.resolve(step,observation)==null,"Password node accepted");node.put("password",false);
        step.put("type","type").put("text","Known draft text");node.put("editable",true);step.getJSONObject("target").put("text",JSONObject.NULL);node.put("text","Existing field value");
        require(FastPlan.resolve(step,observation).optString("text").equals("Known draft text"),"Stable editable selector failed when field value changed");
        step.put("text",JSONObject.NULL);require(FastPlan.resolve(step,observation)==null,"Missing typed text accepted");
        JSONObject result=stream(event(completed(new JSONObject().put("choice",0).put("action",JSONObject.NULL).toString())));
        require(FastDecision.resolve(result,task).getJSONObject("action").optString("type").equals("back"),"Choice parser/expansion failed");
        rejected(()->FastDecision.resolve(new JSONObject().put("choice",1.5).put("action",JSONObject.NULL),task),"Fractional choice accepted");
        rejected(()->FastDecision.resolve(new JSONObject().put("choice",99).put("action",JSONObject.NULL),task),"Out of range choice accepted");
        rejected(()->FastDecision.resolve(new JSONObject().put("choice",0).put("action",new JSONObject().put("type","home")),task),"Conflicting action and choice accepted");
    }
    static void models() throws Exception {
        JSONObject hidden=new JSONObject().put("visibility","hide").put("slug","hidden");
        JSONObject a=new JSONObject().put("visibility","list").put("slug","a").put("display_name","A").put("supports_image",false);
        JSONObject b=new JSONObject().put("visibility","list").put("slug","b").put("display_name","B").put("input_modalities",new JSONArray().put("text").put("image")).put("custom_metadata",42);
        JSONObject c=new JSONObject().put("visibility","list").put("slug","c").put("display_name","C");
        JSONArray visible=ApiClient.visibleModels(new JSONObject().put("models",new JSONArray().put(hidden).put(a).put(b).put(c)));
        require(visible.length()==3,"Model visibility ignored");
        require("a".equals(visible.getJSONObject(0).getString("slug"))&&"b".equals(visible.getJSONObject(1).getString("slug")),"Server ordering changed");
        require(visible.getJSONObject(1).getInt("custom_metadata")==42,"Advertised metadata discarded");
        require("b".equals(ApiClient.selectAdvertisedModel(visible,"").getString("slug")),"Explicit image restriction ignored");
        require("c".equals(ApiClient.selectAdvertisedModel(visible,"c").getString("slug")),"Saved advertised choice ignored");
        require("b".equals(ApiClient.selectAdvertisedModel(visible,"no-longer-available").getString("slug")),"Removed choice reused");
        rejected(()->ApiClient.visibleModels(new JSONObject().put("data",new JSONArray().put(c))),"Wrong API catalog accepted");
        rejected(()->ApiClient.visibleModels(new JSONObject().put("models",new JSONArray().put(hidden))),"No visible models accepted");
        rejected(()->ApiClient.selectAdvertisedModel(new JSONArray().put(a),"a"),"Explicitly unsupported model chosen");
    }
    static void streams() throws Exception {
        String value=action().toString();
        require("back".equals(stream(event(completed(value))).getJSONObject("action").getString("type")),"Completed action lost");
        JSONObject completion=new JSONObject().put("type","response.completed").put("response",new JSONObject().put("status","completed"));
        require(stream(": heartbeat\r\n\r\n"+delta(value)+event(completion)).has("action"),"Delta/completed fallback failed");
        rejected(()->stream(delta(value)),"Partial action executed without completion");
        rejected(()->stream(delta(value)+"data: [DONE]\n\n"),"DONE marker accepted as completed inference");
        rejected(()->stream("data: not-json\n\n"),"Malformed stream accepted");
        rejected(()->stream(event(completed("not-an-action"))),"Malformed completed action accepted");
        rejected(()->stream(event(completion)),"Empty completion accepted");
        JSONObject incomplete=new JSONObject().put("type","response.incomplete").put("response",new JSONObject().put("incomplete_details",new JSONObject().put("reason","max_output_tokens")));
        rejected(()->stream(delta(value)+event(incomplete)),"Incomplete action accepted");
        JSONObject error=new JSONObject().put("code","subscription_sharing_usage_limit_exceeded").put("message","quota fixture").put("param",JSONObject.NULL);
        JSONObject failed=new JSONObject().put("type","response.failed").put("response",new JSONObject().put("status","failed").put("error",error));
        try { stream(delta(value)+event(failed)); throw new AssertionError("Mid-stream quota error accepted"); }
        catch(ApiClient.ProviderException expected) {
            require(!expected.shouldRetry(),"Quota error retried");
            require("subscription_sharing_usage_limit_exceeded".equals(expected.errorCode),"Error code lost");
            require("req-fixture".equals(expected.requestId),"Request id lost");
            require(new JSONObject(expected.responseBody).getJSONObject("error").getString("message").equals("quota fixture"),"Error body shape lost");
        }
        JSONObject streamError=new JSONObject().put("type","error").put("code","subscription_sharing_unsupported_capability").put("message","unsupported fixture").put("param","text.format");
        try { stream(event(streamError)); throw new AssertionError("Unsupported capability accepted"); }
        catch(ApiClient.ProviderException expected) { require(!expected.shouldRetry()&&"text.format".equals(expected.param),"Capability recovery lost"); }
        JSONObject refusal=completed(value);
        refusal.getJSONObject("response").getJSONArray("output").getJSONObject(0).put("content",new JSONArray().put(new JSONObject().put("type","refusal").put("refusal","Fixture refusal")));
        rejected(()->stream(event(refusal)),"Refusal converted to action");
        JSONObject tool=completed(value);
        tool.getJSONObject("response").getJSONArray("output").put(new JSONObject().put("type","function_call"));
        rejected(()->stream(event(tool)),"Unexpected host tool allowed");
        rejected(()->ApiClient.parseResponseStream(input(event(completed(value))),"req-fixture",()->{throw new IOException("Cancelled fixture");}),"Cancelled stream produced action");
        String large=delta("x".repeat(128_001))+event(completion);
        rejected(()->stream(large),"Unbounded action text accepted");
    }
    static void recovery() throws Exception {
        ApiClient.ProviderException detail=ApiClient.failure(403,"{\"detail\":\"region restriction fixture\"}","req-admission");
        require(detail.httpStatus==403&&!detail.shouldRetry()&&detail.getMessage().contains("region restriction fixture"),"Direct admission detail lost");
        require(new JSONObject(detail.responseBody).has("detail"),"Admission body normalized destructively");
        require(ApiClient.failure(503,"{\"detail\":\"unavailable fixture\"}",null).shouldRetry(),"Transient admission not retryable");
        require(!ApiClient.failure(401,"{\"detail\":\"identity fixture\"}",null).shouldRetry(),"Rejected identity blindly retried");
        require(!ApiClient.failure(429,"{}",null).shouldRetry(),"Rate limit blindly retried");
        String usage="{\"error\":{\"code\":\"subscription_sharing_usage_unavailable\",\"message\":\"fixture\"}}";
        require(ApiClient.failure(0,usage,"req-stream").shouldRetry(),"Transient stream usage failure not retryable");
        require(ApiClient.failure(401,"{\"error\":{\"code\":\"invalid_token\",\"message\":\"fixture\"}}",null).confirmsInvalidCredentials(),"Confirmed invalid token ignored");
        require(ApiClient.failure(0,"{\"error\":{\"code\":\"token_revoked\",\"message\":\"fixture\"}}",null).confirmsInvalidCredentials(),"Stream revocation ignored");
        require(!ApiClient.failure(401,"{\"detail\":\"identity policy fixture\"}",null).confirmsInvalidCredentials(),"Unconfirmed identity failure erased credentials");
        require(!ApiClient.failure(401,"{\"error\":{\"code\":\"subscription_sharing_invalid_user\",\"message\":\"fixture\"}}",null).confirmsInvalidCredentials(),"Subscriber context failure treated as confirmed revocation");
        require(!ApiClient.failure(503,usage,null).confirmsInvalidCredentials(),"Temporary failure erased credentials");
    }
    public static void main(String[] args) throws Exception {
        requestShape(); fastFormats(); models(); streams(); recovery();
        System.out.println(new JSONObject().put("status","passed").put("checks",checks).put("liveInferenceRequests",0)
            .put("requestShape",true).put("advertisedCatalog",true).put("completedStreamRequired",true)
            .put("midStreamErrors",true).put("cancellation",true).put("boundedPayload",true));
    }
}
JAVA
"$TASK_JAVA/javac" -source 17 -target 17 -cp "$TASK_TEST/json-20240303.jar:$TASK_ANDROID_JAR" -d "$TASK_TEST/classes" \
  "$TASK_TEST/src/com/tango/phone/ChatGptAuth.java" \
  "$TASK_ROOT/app/src/main/java/com/tango/phone/ActionGuard.java" \
  "$TASK_ROOT/app/src/main/java/com/tango/phone/FastPlan.java" \
  "$TASK_ROOT/app/src/main/java/com/tango/phone/FastDecision.java" \
  "$TASK_ROOT/app/src/main/java/com/tango/phone/AgentPolicy.java" \
  "$TASK_ROOT/app/src/main/java/com/tango/phone/ApiClient.java" \
  "$TASK_TEST/src/com/tango/phone/InferenceSmoke.java"
"$TASK_JAVA/java" -cp "$TASK_TEST/classes:$TASK_TEST/json-20240303.jar:$TASK_ANDROID_JAR" com.tango.phone.InferenceSmoke | tee "$TASK_ROOT/dist/inference-smoke.txt"
