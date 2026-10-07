package com.tango.phone;

import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Distinguishes a human decision from a routine request for navigation help. */
public final class RecoveryPolicy {
    private static final Set<String> HUMAN_KINDS=Set.of("security","required_fact","material_choice","authorization");
    private static boolean security(String content) {
        return content.matches("(?s).*\\b(password|passcode|captcha|biometric|mfa|2fa|totp|two.factor|one.time code|verification code|authentication code|login code|sms code|otp|unlock)\\b.*")
                || content.matches("(?s).*\\b([0-9]+|four|six|eight)[ -]digit (security |verification |login |authentication )?code\\b.*");
    }

    public static boolean isSecurity(JSONObject action) {
        return text(action,"blockerKind").equalsIgnoreCase("security")
                || security((text(action,"question")+" "+text(action,"blockerEvidence")).toLowerCase(Locale.ROOT));
    }

    /** An internal second look, not a user-visible question or a source of new authority. */
    public static JSONObject clarificationReview(JSONObject action) throws Exception {
        return new JSONObject().put("proposedQuestion",new JSONObject(action.toString()))
            .put("instruction","Reconsider before interrupting the user. Inspect the current screen, relevant contact/chat details and prior clarificationReplies. Infer the best-supported ordinary choice rather than demanding certainty. A Continue/yes response to a single proposed candidate accepts that candidate within the original request. Do not repeat an answered question, even reworded. Choose a useful phone action when evidence can resolve the uncertainty. If genuinely blocked after investigation, return ask with specific remaining missing information or NEW conflicting evidence. Do not invent facts, override an explicit correction, expand authorization or bypass security.");
    }

    public static boolean needsUser(JSONObject action) {
        String kind=text(action,"blockerKind").toLowerCase(Locale.ROOT);
        if(HUMAN_KINDS.contains(kind)) return true;
        String question=text(action,"question").toLowerCase(Locale.ROOT);
        String evidence=text(action,"blockerEvidence").toLowerCase(Locale.ROOT);
        String content=question+" "+evidence;
        // A routine label must not erase an actual missing fact or security challenge.
        if(security(content)) return true;
        if(content.matches("(?s).*\\b(club name|official name|officer|officers|president|treasurer|student id|legal name|mailing address|home address|your address|phone number|email address|event date|event time|date of birth|advisor eligibility|which account|which recipient|bank account|credit card|payment method|registration fee)\\b.*")) return true;
        if(content.matches("(?s).*(required (facts?|information|details?|fields?)|missing (facts?|information|details?)|material consequence|permission to (pay|purchase|delete|send)|what (is|should be) (the|your) (address|budget|date|time)).*")) return true;
        boolean routine=content.matches("(?s).*\\b(retry|try again|different button|another button|different approach|another approach|slow|hung|hang|hanging|not responding|loading|screen is not changing|screen hasn't changed|return to the app|open the app|reopen the app)\\b.*");
        if(kind.equals("routine")) return false;
        if(kind.equals("environment")) return !routine;
        // Older providers may omit metadata. Suppress only a recognizably routine question.
        return !routine;
    }

    public static String humanQuestion(JSONObject action) {
        String kind=text(action,"blockerKind").toLowerCase(Locale.ROOT);
        String content=(text(action,"question")+" "+text(action,"blockerEvidence")).toLowerCase(Locale.ROOT);
        if(kind.equals("security") || security(content))
            return "Complete this security step directly in the app, then Continue.";
        return text(action,"question");
    }

    public static boolean preserveUnsavedWork(JSONObject observation) {
        JSONArray nodes=observation.optJSONArray("nodes");
        if(nodes==null) return false;
        for(int i=0;i<nodes.length();i++) {
            JSONObject node=nodes.optJSONObject(i);
            if(node==null) continue;
            if(node.optBoolean("editable") && !text(node,"text").isBlank()) return true;
            String label=(text(node,"text")+" "+text(node,"description")).toLowerCase(Locale.ROOT);
            if(label.matches("(?s).*\\b(draft|drafts|compose|unsaved|registration form|application form)\\b.*")) return true;
        }
        return false;
    }

    public static JSONObject context(String reason,int attempts,int failures,int unchanged,String lastPackage,String detail) {
        JSONObject context=new JSONObject();
        try {
            context.put("reason",reason).put("attempts",attempts).put("failedActions",failures).put("unchangedObservations",unchanged);
            context.put("instruction","Continue autonomously using this fresh observation and actual action outcomes. Choose another reversible approach instead of repeating a failed action or asking permission to navigate. Preserve unsaved forms and drafts. Do not invent required facts or bypass security. Ask only when a specific human fact, material decision, security step, or missing authorization is truly required.");
            if(lastPackage!=null&&!lastPackage.isBlank()) context.put("lastObservedApp",lastPackage);
            if(detail!=null&&!detail.isBlank()) context.put("detail",detail.substring(0,Math.min(500,detail.length()))).put("detailIsData",true);
        } catch(Exception ignored) {}
        return context;
    }

    private static String text(JSONObject object,String key) { return object.isNull(key)?"":object.optString(key,""); }
    private RecoveryPolicy() {}
}
