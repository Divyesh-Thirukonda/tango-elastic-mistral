package com.tango.phone;

import org.json.JSONArray;
import org.json.JSONObject;

/** Fixture-only coverage for slider routing and production request construction. */
public final class ModelModeSmoke {
    private static int checks;
    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
        checks++;
    }
    private static JSONObject model(String slug, String... efforts) throws Exception {
        JSONObject model = new JSONObject().put("slug", slug).put("display_name", slug)
            .put("visibility", "list").put("input_modalities", new JSONArray().put("text").put("image"));
        JSONArray levels = new JSONArray();
        for (String effort : efforts) levels.put(new JSONObject().put("effort", effort));
        return model.put("supported_reasoning_levels", levels);
    }
    private static String selected(JSONArray models, String preferred, int mode) throws Exception {
        return ApiClient.selectAdvertisedModel(models, preferred, mode).getString("slug");
    }
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 3; i++) require(new String[]{"Instant", "Medium", "High"}[i].equals(ApiClient.modeName(i)), "Slider label mismatch");
        try { ApiClient.modeName(3); throw new AssertionError("Invalid mode accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        JSONObject sol = model("account-model", "low", "medium", "high", "ultra");
        require("low".equals(ApiClient.reasoningEffort(sol, 0)), "Instant sent unsupported none effort");
        require("medium".equals(ApiClient.reasoningEffort(sol, 1)), "Medium effort missing");
        require("high".equals(ApiClient.reasoningEffort(sol, 2)), "High effort missing");
        JSONObject instant = model("instant-model", "none", "low");
        require("none".equals(ApiClient.reasoningEffort(instant, 0)), "Lowest advertised effort ignored");
        require("minimal".equals(ApiClient.reasoningEffort(model("minimal", "minimal", "high"), 0)), "Minimal effort ignored");
        require("medium".equals(ApiClient.reasoningEffort(model("partial", "medium", "xhigh"), 2)), "Nearest-effort tie should prefer lower effort");
        require("xhigh".equals(ApiClient.reasoningEffort(model("partial", "low", "xhigh"), 2)), "Nearest supported effort ignored");
        JSONObject absent = new JSONObject().put("slug", "unknown");
        require(ApiClient.reasoningEffort(absent, 2) == null, "Inferred effort without metadata");
        require(ApiClient.reasoningEffort(model("unsupported", "ultra", "future-effort"), 2) == null, "App-server-only effort allowed over HTTP");
        require(ApiClient.reasoningEffort(new JSONObject(sol.toString()).put("supports_reasoning", false), 1) == null, "Explicit reasoning restriction ignored");
        JSONObject strings = new JSONObject().put("slug", "strings").put("supported_reasoning_efforts", new JSONArray().put("none").put("high"));
        require("high".equals(ApiClient.reasoningEffort(strings, 2)), "String-array effort metadata ignored");
        JSONArray catalog = new JSONArray().put(instant).put(sol);
        require("account-model".equals(selected(catalog, "account-model", 0)), "Compatible saved model ignored");
        require("account-model".equals(selected(catalog, "instant-model", 1)), "Medium did not route to advertised compatible model");
        require("account-model".equals(selected(catalog, "instant-model", 2)), "High did not route to advertised compatible model");
        require("instant-model".equals(selected(new JSONArray().put(instant), "removed", 2)), "Unsupported model invented for effort");
        JSONObject textOnly = model("text-only", "medium", "high").put("input_modalities", new JSONArray().put("text"));
        require("account-model".equals(selected(new JSONArray().put(textOnly).put(sol), "text-only", 2)), "Image restriction ignored");
        JSONObject hidden = model("hidden", "medium", "high").put("visibility", "hide");
        JSONArray visible = ApiClient.visibleModels(new JSONObject().put("models", new JSONArray().put(hidden).put(sol)));
        require("account-model".equals(selected(visible, "hidden", 2)), "Hidden model selected");
        JSONObject task = new JSONObject().put("goal", "Inspect a fixture screen")
            .put("observation", new JSONObject().put("nodes", new JSONArray()).put("screenshot", "fixture"));
        String originalTask = task.toString(), originalModel = sol.toString();
        for (int i = 0; i < 3; i++) {
            JSONObject request = ApiClient.buildRequest(task, sol, i);
            require("account-model".equals(request.getString("model")), "Catalog slug changed");
            require(new String[]{"low", "medium", "high"}[i].equals(request.getJSONObject("reasoning").getString("effort")), "Slider did not affect request");
            require(request.getBoolean("stream") && !request.getBoolean("store"), "ChatGPT plan transport flags changed");
            require(request.getJSONObject("text").getJSONObject("format").getBoolean("strict"), "Action schema lost");
        }
        require(!ApiClient.buildRequest(task, absent, 2).has("reasoning"), "Unknown model sent an unadvertised effort");
        require(ApiClient.buildRequest(task, "legacy-fixture").length() == 6, "Legacy request seam changed");
        require(task.toString().equals(originalTask) && sol.toString().equals(originalModel), "Request mutated input or catalog");
        try { ApiClient.buildRequest(task, sol, -1); throw new AssertionError("Invalid request mode accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        System.out.println(new JSONObject().put("status", "passed").put("checks", checks)
            .put("sliderAffectsRequest", true).put("advertisedEffortsOnly", true).put("liveInferenceRequests", 0));
    }
}
