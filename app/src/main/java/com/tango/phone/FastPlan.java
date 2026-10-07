package com.tango.phone;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Set;

/** Short-lived, screen-guarded plans. Never persists a queue or reuses a snapshot ID. */
public final class FastPlan {
    public static final String INSTRUCTIONS=AgentPolicy.SYSTEM+"""
        FAST PLAN: Return action for this screen plus plan, an array of zero to THREE future steps.
        Each future step is tap, type, or scroll with packageName, screen, target, text, direction.
        screen and target are exact semantic selectors: viewId, text, description, className; unused fields null.
        screen must include an exact visible text or description that identifies the EXPECTED next screen.
        target must identify exactly one enabled current control by viewId/text/description AND className.
        Prefer stable viewId for editable fields (their text changes). Never predict snapshot IDs or coordinates.
        Plan only known, routine navigation or filling KNOWN text. No speculative controls, researched facts,
        game moves, security steps, final submission, sending, purchases, deletion, Back, or Done in future steps.
        If you need to read or reason about the next screen, return an empty plan. A mismatch triggers replan.
        Do not pre-write an email before recipient facts have been verified. Completion needs a fresh decision.
        """;

    public static JSONObject schema() throws Exception {
        JSONObject selectorProps=new JSONObject();
        for(String key:new String[]{"viewId","text","description","className"}) selectorProps.put(key,nullable("string"));
        JSONObject selector=object(selectorProps);
        JSONObject props=new JSONObject().put("type",new JSONObject().put("type","string").put("enum",new JSONArray(new String[]{"tap","type","scroll"})))
            .put("packageName",new JSONObject().put("type","string")).put("screen",selector).put("target",selector)
            .put("text",nullable("string")).put("direction",nullable("string"));
        JSONObject root=AgentPolicy.schema();
        root.getJSONObject("properties").put("plan",new JSONObject().put("type","array").put("items",object(props)));
        root.getJSONArray("required").put("plan"); return root;
    }
    static JSONObject nullable(String type) throws Exception {return new JSONObject().put("type",new JSONArray().put(type).put("null"));}
    static JSONObject object(JSONObject props) throws Exception {JSONArray keys=new JSONArray();java.util.Iterator<String> it=props.keys();while(it.hasNext())keys.put(it.next());return new JSONObject().put("type","object").put("properties",props).put("required",keys).put("additionalProperties",false);}

    public static JSONArray accept(JSONObject result) throws Exception {
        JSONArray plan=result.optJSONArray("plan");
        if(plan==null || plan.length()>3) return new JSONArray();
        String type=result.optJSONObject("action")==null?"":result.getJSONObject("action").optString("type");
        if(type.equals("ask") || type.equals("done")) return new JSONArray();
        return new JSONArray(plan.toString());
    }

    /** Null means drop the entire remainder and replan; never guess an ambiguous selector. */
    public static JSONObject resolve(JSONObject step,JSONObject obs) throws Exception {
        if(step==null || !Set.of("tap","type","scroll").contains(step.optString("type"))
            || !step.optString("packageName").equals(obs.optString("packageName"))
            || obs.optBoolean("locked") || obs.optBoolean("password") || obs.optBoolean("hasPassword")
            || !obs.optString("observationError").isEmpty()) return null;
        JSONObject screen=step.optJSONObject("screen"),target=step.optJSONObject("target");
        if(screen==null || target==null || (empty(screen,"text")&&empty(screen,"description"))
            || empty(target,"className") || (empty(target,"viewId")&&empty(target,"text")&&empty(target,"description"))) return null;
        JSONArray nodes=obs.optJSONArray("nodes"); if(nodes==null)return null;
        JSONObject match=null;int anchors=0,targets=0;
        for(int i=0;i<nodes.length();i++) {
            JSONObject node=nodes.optJSONObject(i);if(node==null)continue;
            if(node.optBoolean("password"))return null;
            if(matches(screen,node))anchors++;
            if(matches(target,node)) {targets++;match=node;}
        }
        if(anchors!=1 || targets!=1 || !match.optBoolean("enabled") || match.optString("id").isBlank())return null;
        String type=step.getString("type");
        if(type.equals("tap") && (!match.optBoolean("clickable") || (empty(target,"text")&&empty(target,"description")) || consequential(match)))return null;
        if(type.equals("type") && (!match.optBoolean("editable") || step.isNull("text")))return null;
        if(type.equals("scroll") && (!match.optBoolean("scrollable") || !Set.of("up","down","left","right").contains(step.optString("direction"))))return null;
        JSONObject action=new JSONObject().put("type",type).put("target",match.getString("id"))
            .put("text",step.opt("text")).put("direction",step.opt("direction")).put("reason","Following the checked plan.");
        ActionGuard.validate(action);return action;
    }
    private static boolean consequential(JSONObject node) {
        for(String key:new String[]{"text","description","viewId"}) if(node.optString(key).toLowerCase(java.util.Locale.ROOT).matches("(?s).*(send|submit|register|confirm|purchase|pay|buy|delete|discard|remove|sign.?out|log.?out|install|reset|allow|permission|continue|next|okay|\\bok\\b|\\byes\\b|save).*$"))return true;
        return false;
    }
    private static boolean empty(JSONObject selector,String key) {return selector.isNull(key)||selector.optString(key).isBlank();}
    private static boolean matches(JSONObject selector,JSONObject node) {
        boolean specified=false;
        for(String key:new String[]{"viewId","text","description","className"}) if(!empty(selector,key)){specified=true;if(!selector.optString(key).equals(node.optString(key)))return false;}
        return specified;
    }
    private FastPlan() {}
}
