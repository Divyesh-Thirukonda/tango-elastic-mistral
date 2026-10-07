package com.tango.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Phone-native ChatGPT-plan transport. Credentials only reach the public OpenAI API. */
public final class ApiClient {
    private static final String MODELS_URL = "https://api.openai.com/v1/models";
    private static final String RESPONSES_URL = "https://api.openai.com/v1/responses";
    private static final long CATALOG_TTL_MS = 300_000;
    private static final int MAX_TEXT = 128_000;
    private static final String DIAGNOSTIC_KEY = "chatgpt_inference_diagnostic";
    private static final String MODE_KEY = "chatgpt_reasoning_mode";
    // Responses supports these HTTP efforts. Catalogs can also advertise
    // app-server-only values such as ultra; those must not reach this endpoint.
    private static final String[] HTTP_EFFORTS = {"none", "minimal", "low", "medium", "high", "xhigh", "max"};
    private static final Object CATALOG_LOCK = new Object();
    private static Catalog catalog;
    private final Context context;
    private final AtomicLong cancellation = new AtomicLong();
    private volatile HttpURLConnection active;

    private static final class Catalog {
        final String account;
        final JSONArray models;
        final long refreshedAt;
        Catalog(String account, JSONArray models) {
            this.account = account;
            this.models = models;
            refreshedAt = SystemClock.elapsedRealtime();
        }
    }

    public ApiClient(Context c) { context = c.getApplicationContext(); }

    /** Slider positions: Instant, Medium, High. The choice survives app restarts. */
    public int selectedMode() {
        int mode = context.getSharedPreferences("tango", 0).getInt(MODE_KEY, 1);
        return mode >= 0 && mode <= 2 ? mode : 1;
    }

    public void setMode(int mode) {
        validateMode(mode);
        context.getSharedPreferences("tango", 0).edit().putInt(MODE_KEY, mode).apply();
    }

    public static String modeName(int mode) {
        validateMode(mode);
        return new String[]{"Instant", "Medium", "High"}[mode];
    }

    private static void validateMode(int mode) {
        if (mode < 0 || mode > 2) throw new IllegalArgumentException("Choose Instant, Medium or High.");
    }

    /** Only transport metadata is saved. Response bodies and task content never enter this record. */
    public String diagnosticText() {
        return context.getSharedPreferences("tango", 0).getString(DIAGNOSTIC_KEY, "");
    }

    public void cancel() {
        cancellation.incrementAndGet();
        HttpURLConnection connection = active;
        if (connection != null) connection.disconnect();
    }

    /** Refresh the account-specific catalog; this method runs on a worker thread. */
    public JSONArray listModels() throws Exception {
        long ticket = cancellation.get();
        ChatGptAuth auth = authorized();
        String account = auth.activeClientId();
        try { return copy(refreshModels(auth, account, ticket).models); }
        catch (ProviderException error) { handleCredentialError(auth, account, error); throw error; }
    }

    public String selectedModelName() {
        SharedPreferences prefs = context.getSharedPreferences("tango", 0);
        String account = ChatGptAuth.get(context).activeClientId();
        if (!account.isEmpty() && account.equals(prefs.getString("chatgpt_model_client_id", "")))
            return prefs.getString("chatgpt_model_name", "Account model");
        return "Account model";
    }

    /** Select only a model advertised for the currently authenticated account. */
    public void selectModel(String slug) throws Exception {
        ChatGptAuth auth = authorized();
        String account = auth.activeClientId();
        synchronized (CATALOG_LOCK) {
            if (catalog == null || !catalog.account.equals(account))
                throw new Exception("Refresh your ChatGPT model list first.");
            JSONObject selected = findModel(catalog.models, slug);
            if (selected == null) throw new Exception("That model is not available to this ChatGPT account.");
            if (!phoneCapable(selected)) throw new Exception("That model does not advertise the inputs Tango needs.");
            saveSelection(account, selected);
        }
    }

    public JSONObject decide(JSONObject task) throws Exception {
        long ticket = cancellation.get();
        ChatGptAuth auth = authorized();
        String account = auth.activeClientId();
        try {
            checkSession(auth, account, ticket);
            if(task.has("taskAccount") && !account.equals(task.optString("taskAccount")))throw new IOException("The attachment account changed. Run the command again.");
            Catalog available;
            synchronized (CATALOG_LOCK) { available = catalog; }
            if (available == null || !available.account.equals(account)
                    || SystemClock.elapsedRealtime() - available.refreshedAt >= CATALOG_TTL_MS)
                available = refreshModels(auth, account, ticket);
            int mode = selectedMode();
            JSONObject selected = chooseModel(available.models, account, mode);
            String token = auth.accessToken();
            checkSession(auth, account, ticket);
            JSONObject result = stream(buildRequest(task, selected, mode), token, ticket);
            if ("choice".equals(task.optString("executionMode"))) result = FastDecision.resolve(result, task);
            checkSession(auth, account, ticket);
            context.getSharedPreferences("tango", 0).edit().remove(DIAGNOSTIC_KEY).apply();
            return result;
        } catch (ProviderException error) {
            handleCredentialError(auth, account, error);
            throw error;
        }
    }

    private static void handleCredentialError(ChatGptAuth auth, String account, ProviderException error) {
        if (!error.confirmsInvalidCredentials()) return;
        // Never invalidate a newly selected account because an old request failed.
        auth.invalidateCredentials(account, "Your ChatGPT session ended. Continue with ChatGPT again.");
        synchronized (CATALOG_LOCK) {
            if (catalog != null && catalog.account.equals(account)) catalog = null;
        }
    }

    private ChatGptAuth authorized() throws Exception {
        ChatGptAuth auth = ChatGptAuth.get(context);
        if (!auth.isSignedIn()) throw new Exception("Continue with ChatGPT before using Tango.");
        if (!auth.hasPlanAccess()) throw new Exception("Enable ChatGPT plan access before using Tango.");
        return auth;
    }

    private void checkSession(ChatGptAuth auth, String account, long ticket) throws Exception {
        checkCancelled(ticket);
        if (!auth.isSignedIn() || !auth.hasPlanAccess() || !account.equals(auth.activeClientId()))
            throw new IOException("The ChatGPT account changed. Run the command again after signing in.");
    }

    private void checkCancelled(long ticket) throws IOException {
        if (ticket != cancellation.get()) throw new IOException("AI request cancelled.");
    }

    private Catalog refreshModels(ChatGptAuth auth, String account, long ticket) throws Exception {
        String token = auth.accessToken();
        checkSession(auth, account, ticket);
        JSONObject response = requestModels(token, ticket);
        checkSession(auth, account, ticket);
        Catalog refreshed = new Catalog(account, visibleModels(response));
        synchronized (CATALOG_LOCK) { catalog = refreshed; }
        return refreshed;
    }

    static JSONArray visibleModels(JSONObject response) throws Exception {
        JSONArray all = response.optJSONArray("models");
        if (all == null) throw new Exception("OpenAI returned no ChatGPT model catalog.");
        JSONArray visible = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject model = all.optJSONObject(i);
            if (model != null && "list".equals(model.optString("visibility"))
                    && !model.optString("slug").isBlank()) visible.put(new JSONObject(model.toString()));
        }
        if (visible.length() == 0) throw new Exception("No ChatGPT models are available to this account or workspace.");
        return visible;
    }

    private JSONObject chooseModel(JSONArray models, String account, int mode) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("tango", 0);
        String saved = account.equals(prefs.getString("chatgpt_model_client_id", ""))
                ? prefs.getString("chatgpt_model_slug", "") : "";
        JSONObject selected = selectAdvertisedModel(models, saved, mode);
        saveSelection(account, selected);
        return selected;
    }

    static JSONObject selectAdvertisedModel(JSONArray models, String preferred) throws Exception {
        JSONObject selected = findModel(models, preferred);
        if (selected != null && phoneCapable(selected)) return selected;
        for (int i = 0; i < models.length(); i++) {
            JSONObject candidate = models.getJSONObject(i);
            if (phoneCapable(candidate)) return candidate;
        }
        throw new Exception("This account has no model suitable for Tango's screen and action inputs.");
    }

    /** Honor the saved model when possible, then account order for an exact effort. */
    static JSONObject selectAdvertisedModel(JSONArray models, String preferred, int mode) throws Exception {
        validateMode(mode);
        JSONObject selected = selectAdvertisedModel(models, preferred);
        if (supportsMode(selected, mode)) return selected;
        for (int i = 0; i < models.length(); i++) {
            JSONObject candidate = models.optJSONObject(i);
            if (candidate != null && phoneCapable(candidate) && supportsMode(candidate, mode)) return candidate;
        }
        // Metadata may be omitted or the requested effort unavailable. Keep a
        // suitable account-advertised model; never invent availability or effort.
        return selected;
    }

    private static boolean supportsMode(JSONObject model, int mode) {
        String effort = reasoningEffort(model, mode);
        return effort != null && (mode == 0 || (mode == 1 ? "medium" : "high").equals(effort));
    }

    /** Closest supported effort; Instant uses the model's lowest legal effort. */
    static String reasoningEffort(JSONObject model, int mode) {
        validateMode(mode);
        if (Boolean.FALSE.equals(model.opt("supports_reasoning"))) return null;
        JSONArray levels = model.optJSONArray("supported_reasoning_levels");
        if (levels == null) levels = model.optJSONArray("supported_reasoning_efforts");
        if (levels == null) return null;
        boolean[] supported = new boolean[HTTP_EFFORTS.length];
        for (int i = 0; i < levels.length(); i++) {
            Object value = levels.opt(i);
            String effort = value instanceof JSONObject ? ((JSONObject) value).optString("effort", "")
                : value instanceof String ? (String) value : "";
            for (int j = 0; j < HTTP_EFFORTS.length; j++)
                if (HTTP_EFFORTS[j].equals(effort)) supported[j] = true;
        }
        int target = mode == 0 ? 0 : mode == 1 ? 3 : 4;
        int best = -1;
        for (int i = 0; i < supported.length; i++) {
            if (supported[i] && (best == -1 || Math.abs(i - target) < Math.abs(best - target))) best = i;
        }
        return best == -1 ? null : HTTP_EFFORTS[best];
    }

    private static JSONObject findModel(JSONArray models, String slug) {
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (model != null && slug.equals(model.optString("slug"))) return model;
        }
        return null;
    }

    private static boolean phoneCapable(JSONObject model) {
        // The catalog may omit capability metadata. Honor explicit advertised
        // restrictions, retain all metadata, and let the API validate the rest.
        for (String key : new String[]{"supports_image", "supports_images", "supports_structured_outputs"})
            if (Boolean.FALSE.equals(model.opt(key))) return false;
        JSONArray inputs = model.optJSONArray("input_modalities");
        if (inputs == null) inputs = model.optJSONArray("supported_input_modalities");
        if (inputs != null) {
            boolean images = false;
            for (int i = 0; i < inputs.length(); i++) if ("image".equals(inputs.optString(i))) images = true;
            if (!images) return false;
        }
        return true;
    }

    private void saveSelection(String account, JSONObject model) {
        context.getSharedPreferences("tango", 0).edit()
            .putString("chatgpt_model_client_id", account)
            .putString("chatgpt_model_slug", model.optString("slug"))
            .putString("chatgpt_model_name", model.optString("display_name", model.optString("slug"))).apply();
    }

    private static JSONArray copy(JSONArray array) throws Exception { return new JSONArray(array.toString()); }

    static JSONObject buildRequest(JSONObject task, JSONObject model, int mode) throws Exception {
        JSONObject request = buildRequest(task, model.getString("slug"));
        String effort = reasoningEffort(model, mode);
        if (effort != null) request.put("reasoning", new JSONObject().put("effort", effort));
        return request;
    }

    static JSONObject buildRequest(JSONObject task, String model) throws Exception {
        JSONObject observation = new JSONObject(task.getJSONObject("observation").toString());
        String screenshot = observation.optString("screenshot", "");
        observation.remove("screenshot");
        JSONObject prompt = new JSONObject();
        java.util.Iterator<String> taskKeys=task.keys();
        while(taskKeys.hasNext()){String key=taskKeys.next();if(!key.equals("attachmentInputs")&&!key.equals("taskAccount")&&!key.equals("threadId"))prompt.put(key,task.get(key));}
        prompt.put("observation",observation);
        String mode=task.optString("executionMode");
        if (mode.equals("choice")) prompt.put("candidates",FastDecision.candidates(task));
        JSONArray content = new JSONArray().put(new JSONObject().put("type", "input_text").put("text", prompt.toString()));
        if (!screenshot.isEmpty()) content.put(new JSONObject().put("type", "input_image")
            .put("image_url", "data:image/jpeg;base64," + screenshot).put("detail", "low"));
        JSONArray attachments=task.optJSONArray("attachmentInputs");
        if(attachments!=null)for(int i=0;i<attachments.length();i++)content.put(attachments.getJSONObject(i));
        // These are the supported ChatGPT-plan HTTP fields. History is in the
        // task input; server-side conversation storage and hosted tools are off.
        return new JSONObject().put("model", model).put("store", false).put("stream", true)
            .put("instructions", mode.equals("plan") ? FastPlan.INSTRUCTIONS : mode.equals("choice") ? FastDecision.INSTRUCTIONS : AgentPolicy.SYSTEM)
            .put("input", new JSONArray().put(new JSONObject().put("role", "user").put("content", content)))
            .put("text", new JSONObject().put("format", new JSONObject().put("type", "json_schema")
                .put("name", mode.equals("plan") ? "phone_plan" : mode.equals("choice") ? "phone_choice" : "phone_action").put("strict", true).put("schema", mode.equals("plan") ? FastPlan.schema() : mode.equals("choice") ? FastDecision.schema() : AgentPolicy.schema())));
    }

    private HttpURLConnection connection(String url, String token, boolean streaming, long ticket) throws Exception {
        checkCancelled(ticket);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(120_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", streaming ? "text/event-stream" : "application/json");
        active = connection;
        try { checkCancelled(ticket); }
        catch (Exception error) { release(connection); throw error; }
        return connection;
    }

    private JSONObject requestModels(String token, long ticket) throws Exception {
        HttpURLConnection connection = connection(MODELS_URL, token, false, ticket);
        try {
            int status = connection.getResponseCode();
            checkCancelled(ticket);
            String body = readBody(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) throw failure(status, body, connection.getHeaderField("x-request-id"));
            try { return new JSONObject(body); }
            catch (Exception error) { throw new IOException("OpenAI returned an invalid model catalog."); }
        } finally { release(connection); }
    }

    private JSONObject stream(JSONObject body, String token, long ticket) throws Exception {
        HttpURLConnection connection = connection(RESPONSES_URL, token, true, ticket);
        int status = 0;
        String contentType = "", requestId = "";
        String model = body.optString("model");
        try {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            status = connection.getResponseCode();
            checkCancelled(ticket);
            requestId = connection.getHeaderField("x-request-id");
            contentType = connection.getHeaderField("Content-Type");
            try (InputStream input = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream()) {
                return parseResponseBody(input, contentType, requestId, model, status, () -> checkCancelled(ticket));
            }
        } catch (ProviderException error) {
            checkCancelled(ticket);
            saveDiagnostic(error);
            throw error;
        } catch (IOException error) {
            checkCancelled(ticket);
            // Network failures may contain URLs or device details in their messages.
            // Persist a classification and the known response headers only.
            saveDiagnostic(new ProviderException(status, "response_connection_interrupted", "", requestId, "",
                "The AI connection was interrupted before completion. No phone action was executed.", true)
                .transport(status, contentType, model, "network"));
            throw error;
        } finally { release(connection); }
    }

    private void saveDiagnostic(ProviderException error) {
        String version = "unknown";
        try { version = safeIdentifier(context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName); }
        catch (Exception ignored) {}
        String details = "Tango " + version + "\nProvider: OpenAI Responses\nBody: " + error.bodyKind
            + "\nHTTP: " + error.httpStatus + "\nMedia: " + error.contentType
            + "\nModel: " + error.model + "\nRequest: " + error.requestId
            + "\nCode: " + safeIdentifier(error.errorCode);
        context.getSharedPreferences("tango", 0).edit().putString(DIAGNOSTIC_KEY, details).apply();
    }

    private void release(HttpURLConnection connection) {
        connection.disconnect();
        if (active == connection) active = null;
    }

    interface CancellationCheck { void check() throws IOException; }

    static JSONObject parseResponseBody(InputStream input, String contentType, String requestId, String model, int status) throws Exception {
        return parseResponseBody(input, contentType, requestId, model, status, () -> {});
    }

    /** Headers describe the body; only a validated terminal response permits an action. */
    static JSONObject parseResponseBody(InputStream input, String contentType, String requestId, String model,
            int status, CancellationCheck cancelled) throws Exception {
        String kind = "empty";
        try {
            cancelled.check();
            if (input == null) {
                if (status < 200 || status >= 300) throw failure(status, "", requestId);
                throw invalidResponse("response_empty", "OpenAI returned an empty response. No phone action was executed.");
            }
            PushbackInputStream body = new PushbackInputStream(new CheckedInputStream(
                new BoundedInputStream(input, 8_000_000), cancelled), 8);
            kind = bodyKind(body);
            if (status < 200 || status >= 300) throw failure(status, readBody(body, cancelled), requestId);
            JSONObject result;
            if ("sse".equals(kind)) {
                result = parseResponseStream(body, requestId, cancelled);
            } else if ("json".equals(kind)) {
                String json = readBody(body, cancelled);
                JSONObject response = jsonObject(json);
                if (hasError(response) || response.has("detail") || "error".equals(response.optString("type")))
                    throw failure(status, json, requestId);
                if (!"response".equals(response.optString("object")))
                    throw invalidResponse("response_invalid_envelope", "OpenAI returned an unsupported response envelope. No phone action was executed.");
                String state = response.optString("status");
                if ("failed".equals(state)) throw failure(status, json, requestId);
                if ("incomplete".equals(state)) throw incomplete(response, requestId, status);
                if (!"completed".equals(state) || response.optJSONArray("output") == null)
                    throw invalidResponse("response_not_completed", "The AI response did not complete. No phone action was executed.");
                if (response.has("incomplete_details") && !response.isNull("incomplete_details"))
                    throw invalidResponse("response_invalid_completion", "OpenAI returned conflicting completion details. No phone action was executed.");
                result = completedAction(response, "", requestId);
            } else {
                throw invalidResponse("response_unrecognized_body", "OpenAI returned an unexpected response format. No phone action was executed. Copy AI details for diagnosis.");
            }
            cancelled.check();
            return result;
        } catch (ProviderException error) {
            cancelled.check();
            throw error.transport(status, contentType, model, kind, requestId);
        } catch (ResponseFormatException error) {
            cancelled.check();
            throw invalidResponse(error.code, error.getMessage()).transport(status, contentType, model, kind, requestId);
        }
    }

    /** Inspect at most a small prefix, including optional BOM/whitespace, without consuming content. */
    private static String bodyKind(PushbackInputStream input) throws IOException {
        int first = input.read();
        if (first == 0xef) {
            if (input.read() != 0xbb || input.read() != 0xbf) return "unknown";
            first = input.read();
        }
        int skipped = 0;
        while (first == ' ' || first == '\t' || first == '\r' || first == '\n') {
            if (++skipped >= 4096) return "unknown";
            first = input.read();
        }
        if (first == -1) return "empty";
        if (first == '{') { input.unread(first); return "json"; }
        if (first == '<') { input.unread(first); return "html"; }
        if (first == ':') { input.unread(first); return "sse"; }
        byte[] prefix = new byte[8];
        prefix[0] = (byte)first;
        int length = 1;
        String[] fields = {"data:", "event:", "id:", "retry:"};
        while (length <= 6) {
            String value = new String(prefix, 0, length, StandardCharsets.US_ASCII);
            boolean possible = false;
            for (String field : fields) {
                if (field.equals(value)) { input.unread(prefix, 0, length); return "sse"; }
                if (field.startsWith(value)) possible = true;
            }
            if (!possible) break;
            int next = input.read();
            if (next == -1) break;
            prefix[length++] = (byte)next;
        }
        input.unread(prefix, 0, length);
        return "unknown";
    }

    private static JSONObject jsonObject(String text) throws ResponseFormatException {
        try {
            JSONTokener tokener = new JSONTokener(text);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) throw new Exception();
            return (JSONObject)value;
        } catch (Exception error) {
            throw new ResponseFormatException("response_invalid_json", "OpenAI returned incomplete or invalid JSON. No phone action was executed.");
        }
    }

    private static boolean hasError(JSONObject response) {
        return response.has("error") && !response.isNull("error");
    }

    private static ProviderException invalidResponse(String code, String message) {
        return new ProviderException(0, code, "", "", "", message, false);
    }

    private static ProviderException incomplete(JSONObject response, String requestId, int status) {
        return new ProviderException(status, "response_incomplete", "", requestId, response.toString(),
            "AI response was incomplete. No phone action was executed.", false);
    }

    static JSONObject parseResponseStream(InputStream input, String requestId) throws Exception {
        return parseResponseStream(input, requestId, () -> {});
    }

    static JSONObject parseResponseStream(InputStream input, String requestId, CancellationCheck cancelled) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(new BoundedInputStream(input, 8_000_000), StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        StringBuilder text = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            cancelled.check();
            if (line.isEmpty()) {
                if (data.length() == 0) continue;
                JSONObject complete = streamEvent(data.toString(), text, requestId);
                data.setLength(0);
                if (complete != null) return complete;
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(line.substring(5).stripLeading());
                if (data.length() > 2_000_000) throw new ResponseFormatException("response_too_large", "An OpenAI stream event was too large.");
            }
        }
        cancelled.check();
        if (data.length() > 0) {
            JSONObject complete = streamEvent(data.toString(), text, requestId);
            if (complete != null) return complete;
        }
        throw new ProviderException(0, "response_interrupted", "", requestId, "",
            "The AI stream ended before completion. No phone action was executed.", true);
    }

    private static JSONObject streamEvent(String data, StringBuilder text, String requestId) throws Exception {
        if ("[DONE]".equals(data)) return null; // A marker alone is not completed inference.
        JSONObject event;
        event = jsonObject(data);
        String type = event.optString("type");
        if ("error".equals(type)) throw failure(0, event.toString(), requestId);
        if ("response.failed".equals(type)) {
            JSONObject response = event.optJSONObject("response");
            throw failure(0, (response == null ? event : response).toString(), requestId);
        }
        if ("response.incomplete".equals(type)) {
            JSONObject response = event.optJSONObject("response");
            throw incomplete(response == null ? event : response, requestId, 0);
        }
        if ("response.output_text.delta".equals(type)) {
            if (!(event.opt("delta") instanceof String))
                throw new ResponseFormatException("response_invalid_output", "OpenAI returned invalid action text.");
            text.append(event.optString("delta"));
            if (text.length() > MAX_TEXT) throw new ResponseFormatException("response_too_large", "The AI action response was too large.");
        }
        if (!"response.completed".equals(type)) return null;
        JSONObject response = event.optJSONObject("response");
        if (response == null || !"completed".equals(response.optString("status")))
            throw new ResponseFormatException("response_invalid_completion", "OpenAI returned an invalid completion event.");
        if (hasError(response)) throw failure(0, response.toString(), requestId);
        if (response.has("incomplete_details") && !response.isNull("incomplete_details"))
            throw new ResponseFormatException("response_invalid_completion", "OpenAI returned conflicting completion details. No phone action was executed.");
        return completedAction(response, text.toString(), requestId);
    }

    private static JSONObject completedAction(JSONObject response, String streamedText, String requestId) throws Exception {
        String output = outputText(response);
        if (output.isEmpty()) output = streamedText;
        if (output.isEmpty()) throw new ResponseFormatException("response_missing_action", "AI returned no phone action.");
        if (output.length() > MAX_TEXT) throw new ResponseFormatException("response_too_large", "The AI action response was too large.");
        JSONObject result = jsonObject(output);
        if (result.optJSONObject("action") == null && !(result.opt("choice") instanceof Number)) throw new ResponseFormatException("response_missing_action", "AI returned no phone action.");
        return result;
    }

    private static String outputText(JSONObject response) throws Exception {
        StringBuilder text = new StringBuilder();
        JSONArray output = response.optJSONArray("output");
        if (output == null) return "";
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null) throw new ResponseFormatException("response_invalid_output", "OpenAI returned invalid response output.");
            if (item.optString("type").endsWith("_call")) throw new ResponseFormatException("response_unsupported_tool", "AI requested an unsupported tool. No action was executed.");
            if (item.has("status") && !item.isNull("status") && !"completed".equals(item.optString("status")))
                throw new ResponseFormatException("response_invalid_output", "AI response output was not completed. No action was executed.");
            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part == null) throw new ResponseFormatException("response_invalid_output", "OpenAI returned invalid response content.");
                if ("refusal".equals(part.optString("type")))
                    throw new ProviderException(0, "model_refusal", "", "", response.toString(),
                        "The model declined this task. No phone action was executed.", false);
                if ("output_text".equals(part.optString("type"))) {
                    if (!(part.opt("text") instanceof String))
                        throw new ResponseFormatException("response_invalid_output", "OpenAI returned invalid action text.");
                    text.append(part.optString("text"));
                }
                if (text.length() > MAX_TEXT) throw new ResponseFormatException("response_too_large", "The AI action response was too large.");
            }
        }
        return text.toString();
    }

    static ProviderException failure(int status, String body, String requestId) {
        JSONObject response = null;
        try { response = new JSONObject(body); } catch (Exception ignored) {}
        JSONObject error = response == null ? null : response.optJSONObject("error");
        if (error == null && response != null && "error".equals(response.optString("type"))) error = response;
        JSONObject fields = error == null ? response : error;
        String code = fields == null || fields.isNull("code") ? "" : fields.optString("code");
        String param = fields == null || fields.isNull("param") ? "" : fields.optString("param");
        String message = error == null ? response == null ? "OpenAI request failed" : response.optString("detail", "OpenAI request failed")
            : error.optString("message", "OpenAI request failed");
        if (error == null && response != null && response.opt("error") instanceof String)
            message = response.optString("error");
        boolean retry = status == 500 || status == 502 || status == 503 || status == 504
            || "server_error".equals(code) || "internal_error".equals(code);
        if ("subscription_sharing_usage_limit_exceeded".equals(code)) {
            message = "Tango's ChatGPT usage limit was reached. Review ChatGPT Settings → Usage before continuing.";
            retry = false;
        } else if ("subscription_sharing_user_not_eligible".equals(code)) {
            message = "ChatGPT plan usage is unavailable for this account or workspace.";
            retry = false;
        } else if ("subscription_sharing_usage_unavailable".equals(code) || "subscription_sharing_user_unavailable".equals(code)) {
            message = "ChatGPT plan access is temporarily unavailable. Try again shortly.";
            retry = true;
        } else if ("subscription_sharing_unsupported_capability".equals(code)) {
            message = "This ChatGPT model or request capability is unsupported" + (param.isEmpty() ? "." : ": " + param);
            retry = false;
        } else if (status == 401 || "subscription_sharing_invalid_user".equals(code)) {
            message = "ChatGPT did not accept this account or permission. Check plan access or sign in again.";
            retry = false;
        } else if (status == 403) {
            message = "ChatGPT denied access: " + message;
            retry = false;
        } else if (status == 429) {
            message = "ChatGPT is limiting requests. Review usage or try again later.";
            retry = false;
        }
        return new ProviderException(status, code, param, requestId, body, message, retry);
    }

    /** Preserves provider diagnostics without logging credentials or response payloads. */
    public static final class ProviderException extends Exception {
        public final int httpStatus;
        public final String errorCode;
        public final String param;
        public final String requestId;
        public final String responseBody;
        public final String contentType;
        public final String model;
        public final String bodyKind;
        private final String summary;
        private final boolean retry;
        ProviderException(int status, String code, String param, String requestId, String body, String message, boolean retry) {
            this(status, code, param, requestId, body, message, retry, "", "", "unknown");
        }
        private ProviderException(int status, String code, String param, String requestId, String body, String message,
                boolean retry, String contentType, String model, String bodyKind) {
            super(safeMessage(message) + (safeIdentifier(code).isEmpty() ? "" : " [" + safeIdentifier(code) + "]")
                + (safeIdentifier(requestId).isEmpty() ? "" : " (request " + safeIdentifier(requestId) + ")"));
            httpStatus = status;
            errorCode = code;
            this.param = param;
            this.requestId = safeIdentifier(requestId);
            responseBody = body;
            this.contentType = safeMediaType(contentType);
            this.model = safeIdentifier(model);
            this.bodyKind = safeIdentifier(bodyKind);
            summary = message;
            this.retry = retry;
        }
        private ProviderException transport(int status, String contentType, String model, String kind) {
            return transport(status, contentType, model, kind, requestId);
        }
        private ProviderException transport(int status, String contentType, String model, String kind, String responseRequestId) {
            return new ProviderException(status, errorCode, param, requestId.isEmpty() ? responseRequestId : requestId,
                responseBody, summary, retry, contentType, model, kind);
        }
        public boolean shouldRetry() { return retry; }
        public boolean confirmsInvalidCredentials() {
            if (httpStatus != 0 && httpStatus != 401 && !(httpStatus >= 200 && httpStatus < 300)) return false;
            return switch (errorCode) {
                case "invalid_token", "invalid_access_token", "invalid_auth_token", "invalid_api_key",
                     "token_expired", "access_token_expired", "token_revoked", "access_token_revoked",
                     "token_invalidated", "revoked_token" -> true;
                default -> false;
            };
        }
    }

    private static String readBody(InputStream stream) throws Exception {
        return readBody(stream, () -> {});
    }

    private static String readBody(InputStream stream, CancellationCheck cancelled) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (stream != null) try (InputStream input = stream) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                cancelled.check();
                if (bytes.size() + read > 2_000_000) throw new ResponseFormatException("response_too_large", "OpenAI response was too large.");
                bytes.write(buffer, 0, read);
            }
        }
        cancelled.check();
        return bytes.toString(StandardCharsets.UTF_8.name());
    }

    private static String safeIdentifier(String value) {
        if (value == null || value.length() > 160 || !value.matches("[A-Za-z0-9_.:-]*")) return "";
        if (value.toLowerCase(java.util.Locale.ROOT).contains("sk-")
                || value.matches("(?i)bearer[:._-].*")
                || value.matches("[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}")) return "";
        return value;
    }

    private static String safeMediaType(String value) {
        if (value == null) return "missing";
        String media = value.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        return media.length() <= 100 && media.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+") ? media : "invalid";
    }

    private static String safeMessage(String message) {
        if (message == null) return "OpenAI request failed";
        String safe = message.replaceAll("(?i)(bearer\\s+|sk-)[A-Za-z0-9_./+=-]+", "[credential removed]")
            .replaceAll("[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}", "[credential removed]")
            .replaceAll("(?i)data:[^\\s]+", "[content removed]")
            .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[email removed]")
            .replaceAll("[\\r\\n\\t]", " ");
        return safe.substring(0, Math.min(safe.length(), 400));
    }

    private static final class ResponseFormatException extends IOException {
        final String code;
        ResponseFormatException(String code, String message) { super(message); this.code = code; }
    }

    private static final class CheckedInputStream extends FilterInputStream {
        private final CancellationCheck cancelled;
        CheckedInputStream(InputStream input, CancellationCheck cancelled) { super(input); this.cancelled = cancelled; }
        @Override public int read() throws IOException {
            cancelled.check();
            int result = in.read();
            cancelled.check();
            return result;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            cancelled.check();
            int result = in.read(buffer, offset, length);
            cancelled.check();
            return result;
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;
        BoundedInputStream(InputStream input, long limit) { super(input); remaining = limit; }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value != -1 && --remaining < 0) throw new ResponseFormatException("response_too_large", "OpenAI inference stream was too large.");
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = in.read(buffer, offset, length);
            if (read > 0 && (remaining -= read) < 0) throw new ResponseFormatException("response_too_large", "OpenAI inference stream was too large.");
            return read;
        }
    }
}
