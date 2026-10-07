package com.tango.phone;

import org.json.JSONArray;
import org.json.JSONObject;

/** Experimental finite-choice format, using the same authorized OpenAI model and transport. */
public final class FastDecision {
    public static final String INSTRUCTIONS=AgentPolicy.SYSTEM+"""
        CHOICE EXPERIMENT: candidates lists current-screen actions built by the phone.
        To choose one return choice: its integer index, action: null. To type, launch, navigate a URL,
        use coordinates, ask, or finish, return choice: null and a normal action. Never invent an index.
        A short response does not eliminate the per-action model request. Do not use a candidate merely
        because it exists; honor all task authorization, draft, security and factual rules above.
        """;
    public static JSONArray candidates(JSONObject task) throws Exception {
        JSONArray result=new JSONArray();JSONObject obs=task.getJSONObject("observation");JSONArray nodes=obs.optJSONArray("nodes");
        if(nodes!=null)for(int i=0;i<nodes.length()&&result.length()<180;i++) {
            JSONObject node=nodes.optJSONObject(i);if(node==null||!node.optBoolean("enabled")||node.optBoolean("password"))continue;
            if(node.optBoolean("clickable")) result.put(new JSONObject().put("type","tap").put("target",node.optString("id")));
            if(node.optBoolean("scrollable"))for(String direction:new String[]{"up","down"})result.put(new JSONObject().put("type","scroll").put("target",node.optString("id")).put("direction",direction));
        }
        result.put(new JSONObject().put("type","back")).put(new JSONObject().put("type","home"))
            .put(new JSONObject().put("type","wait").put("durationMs",500));return result;
    }
    public static JSONObject schema() throws Exception {
        JSONObject action=AgentPolicy.schema().getJSONObject("properties").getJSONObject("action");
        return FastPlan.object(new JSONObject().put("choice",FastPlan.nullable("integer"))
            .put("action",new JSONObject().put("anyOf",new JSONArray().put(action).put(new JSONObject().put("type","null")))));
    }
    public static JSONObject resolve(JSONObject result,JSONObject task) throws Exception {
        if(result.isNull("choice")) {ActionGuard.validate(result.getJSONObject("action"));return result;}
        Object raw=result.opt("choice");JSONArray candidates=candidates(task);
        if(!(raw instanceof Number) || ((Number)raw).doubleValue()!=((Number)raw).intValue()
            || ((Number)raw).intValue()<0 || ((Number)raw).intValue()>=candidates.length() || !result.isNull("action"))throw new Exception("AI returned an invalid candidate choice.");
        JSONObject action=new JSONObject(candidates.getJSONObject(((Number)raw).intValue()).toString()).put("reason","Using the selected control.");
        ActionGuard.validate(action);return new JSONObject().put("action",action);
    }
    private FastDecision() {}
}
