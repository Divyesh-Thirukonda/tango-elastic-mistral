package com.tango.phone;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.Set;

/** Local checks complement, but do not replace, the agent's understanding of the user's scope. */
public final class ActionGuard {
    private static final Set<String> TYPES = Set.of("tap","type","swipe","scroll","back","home","launch","open_url","wait","ask","done");
    public static void validate(JSONObject action) throws Exception {
        String type = action.optString("type");
        if (!TYPES.contains(type)) throw new Exception("AI returned an unsupported action: "+type);
        if (type.equals("tap") && action.isNull("target")) { coordinate(action,"x"); coordinate(action,"y"); }
        if (type.equals("type") && (action.isNull("target") || action.isNull("text"))) throw new Exception("Typing needs a current field and text.");
        if (type.equals("swipe")) for (String k : new String[]{"x","y","x2","y2"}) coordinate(action,k);
        if (type.equals("open_url")) {
            java.net.URI uri = new java.net.URI(action.optString("url"));
            if (!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null) throw new Exception("Only ordinary web URLs can be opened.");
        }
        if (type.equals("launch") && !action.optString("packageName").matches("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+")) throw new Exception("Invalid app package.");
        if (type.equals("ask") && action.optString("question").isBlank()) throw new Exception("The agent returned an empty question.");
        if (type.equals("done") && action.optString("summary").isBlank()) throw new Exception("The agent returned an empty result.");
    }
    private static void coordinate(JSONObject action,String key) throws Exception {
        double n = action.optDouble(key,Double.NaN);
        if (!Double.isFinite(n) || n<0 || n>1) throw new Exception("Invalid screen coordinate: "+key);
    }
    public static String blockReason(String goal, JSONObject obs, JSONObject action) {
        String type=action.optString("type");
        if (!type.equals("tap") && !type.equals("type")) return null;
        String g=goal.toLowerCase(Locale.ROOT);
        String marker="follow-up instruction from user:"; int followup=g.lastIndexOf(marker);
        String latest=followup>=0?g.substring(followup+marker.length()):"";
        boolean draft=g.matches("(?s).*(draft|do not send|don't send|unsent).*"), explicitSend=latest.matches("(?s).*\\b(send (it|the draft|that draft|the email|this email|the message))\\b.*") && !latest.matches("(?s).*(do not send|don't send|never send).*" );
        JSONArray nodes=obs.optJSONArray("nodes");
        if (nodes != null) for (int i=0;i<nodes.length();i++) {
            JSONObject node=nodes.optJSONObject(i);
            if (node==null)continue;
            boolean selected=node.optString("id").equals(action.optString("target"));
            if(type.equals("tap") && action.isNull("target")) {
                JSONArray bounds=node.optJSONArray("bounds");
                double x=action.optDouble("x",-1)*obs.optInt("width"),y=action.optDouble("y",-1)*obs.optInt("height");
                selected=bounds!=null&&bounds.length()==4&&x>=bounds.optDouble(0)&&x<=bounds.optDouble(2)&&y>=bounds.optDouble(1)&&y<=bounds.optDouble(3);
            }
            if (selected) {
                if (node.optBoolean("password")) return "Please enter your password directly in the app, then resume Tango.";
                for(String label:new String[]{node.optString("text"),node.optString("description")}) {
                    String l=label.toLowerCase(Locale.ROOT).trim();
                    if(type.equals("tap") && draft && !explicitSend && l.matches("(?s)^(send|send email|send message)( button)?$")) return "Your request is to keep a draft. Sending is blocked.";
                    if(l.matches("(?s)^(buy|buy now|purchase|pay|pay now|checkout|delete account|factory reset|uninstall)( button)?$")) return "This screen involves a purchase or account change. Please handle it yourself or clarify your request.";
                }
            }
        }
        return null;
    }
    private ActionGuard() {}
}
