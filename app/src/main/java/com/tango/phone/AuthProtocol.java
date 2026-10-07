package com.tango.phone;

import android.util.Base64;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Small, fail-closed OAuth/OIDC primitives. Identity is never trusted before signature verification. */
public final class AuthProtocol {
    public static final String ISSUER = "https://auth.openai.com";
    public static final String RESOURCE = "https://api.openai.com/v1";
    public static final String DYNAMIC_CLIENT = "dynamic_agent_client";
    public static final String SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    public static final String CALLBACK_PATH = "/auth/callback";
    private static final int URL_FLAGS = Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static String randomValue(int bytes) {
        byte[] value = new byte[bytes]; RANDOM.nextBytes(value);
        return Base64.encodeToString(value, URL_FLAGS);
    }
    public static String challenge(String verifier) throws Exception {
        return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)), URL_FLAGS);
    }
    public static boolean same(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
    public static boolean hasScope(String scopes, String wanted) {
        if (scopes == null) return false;
        for (String scope : scopes.trim().split("\\s+")) if (wanted.equals(scope)) return true;
        return false;
    }
    public static boolean planGranted(String scopes) {
        return hasScope(scopes, "chatgpt.tokens.use.direct") && hasScope(scopes, "resource.invoke");
    }
    public static final class Callback {
        public final String code, clientId, error;
        private Callback(String c, String id, String e) { code=c; clientId=id; error=e; }
    }
    /** State must be checked before an OAuth error or code is acted on. Caller consumes an attempt once. */
    public static Callback validateCallback(String target, String state, String requestedClient, boolean initial) throws Exception {
        if (target == null || target.length() > 32768) throw new Exception("Invalid sign-in callback.");
        URI uri = new URI(target);
        if (uri.isAbsolute() || uri.getRawAuthority()!=null || uri.getRawFragment()!=null || !CALLBACK_PATH.equals(uri.getRawPath()))
            throw new Exception("Invalid sign-in callback path.");
        Map<String,String> values = new LinkedHashMap<>();
        String query=uri.getRawQuery();
        if (query == null) throw new Exception("The sign-in callback was incomplete.");
        for (String part : query.split("&", -1)) {
            int separator=part.indexOf('=');
            String key=URLDecoder.decode(separator<0 ? part : part.substring(0,separator), StandardCharsets.UTF_8.name());
            String value=URLDecoder.decode(separator<0 ? "" : part.substring(separator+1), StandardCharsets.UTF_8.name());
            if (values.put(key,value)!=null) throw new Exception("The sign-in callback contained duplicate parameters.");
        }
        if (!same(state,values.get("state"))) throw new Exception("Sign-in could not be verified. Start again.");
        String error=values.get("error");
        if (error!=null && !error.isEmpty()) return new Callback("", requestedClient, safeCode(error));
        String code=values.get("code");
        if (code==null || code.isEmpty() || code.length()>16384) throw new Exception("The callback did not contain an authorization code.");
        String supplied=values.get("client_id");
        String client=initial ? supplied : requestedClient;
        if (client==null || !client.matches("[A-Za-z0-9_.:-]{1,512}") || DYNAMIC_CLIENT.equals(client))
            throw new Exception("OpenAI did not issue a client registration. Try signing in again.");
        if (!initial && supplied!=null && !same(requestedClient,supplied))
            throw new Exception("OpenAI returned a different client registration. Sign-in was rejected.");
        return new Callback(code,client,"");
    }
    static String safeCode(String value) {
        return value!=null && value.matches("[A-Za-z0-9_.-]{1,120}") ? value : "unknown_error";
    }
    private static byte[] decode(String value) throws Exception {
        if (value==null || value.isEmpty() || !value.matches("[A-Za-z0-9_-]+")) throw new Exception("Malformed identity token.");
        return Base64.decode(value, URL_FLAGS);
    }
    public static String keyId(String token) throws Exception {
        if (token==null || token.length()>65536) throw new Exception("Malformed identity token.");
        String[] parts=token.split("\\.",-1);
        if (parts.length!=3) throw new Exception("Malformed identity token.");
        JSONObject header=new JSONObject(new String(decode(parts[0]),StandardCharsets.UTF_8));
        if (!"RS256".equals(header.optString("alg")) || header.has("crit") || header.has("jku") || header.has("x5u"))
            throw new Exception("Unsupported identity-token signing method.");
        Object kid=header.opt("kid");
        if (!(kid instanceof String) || ((String)kid).isEmpty() || ((String)kid).length()>256)
            throw new Exception("The identity token did not identify a signing key.");
        return (String)kid;
    }
    public static boolean containsKey(JSONObject jwks, String kid) {
        JSONArray keys=jwks.optJSONArray("keys");
        if(keys!=null) for(int i=0;i<keys.length();i++) {
            JSONObject key=keys.optJSONObject(i);
            if(key!=null && kid.equals(key.optString("kid"))) return true;
        }
        return false;
    }
    /** expectedNonce is mandatory for authorization; refresh ID tokens may omit it. */
    public static JSONObject verifyIdentity(String token, JSONObject jwks, String clientId, String expectedNonce, long nowSeconds) throws Exception {
        String kid=keyId(token);
        String[] parts=token.split("\\.",-1);
        JSONArray keys=jwks.optJSONArray("keys"); JSONObject chosen=null;
        if(keys!=null) for(int i=0;i<keys.length();i++) {
            JSONObject key=keys.optJSONObject(i);
            if(key!=null && kid.equals(key.optString("kid"))) {
                if(chosen!=null) throw new Exception("Ambiguous identity-token signing key.");
                chosen=key;
            }
        }
        if(chosen==null || !"RSA".equals(chosen.optString("kty")) || (chosen.has("use") && !"sig".equals(chosen.optString("use")))
                || (chosen.has("alg") && !"RS256".equals(chosen.optString("alg"))))
            throw new Exception("The identity-token signing key could not be verified.");
        BigInteger modulus=new BigInteger(1,decode(chosen.getString("n")));
        BigInteger exponent=new BigInteger(1,decode(chosen.getString("e")));
        if(modulus.bitLength()<2048 || modulus.bitLength()>8192 || exponent.compareTo(BigInteger.valueOf(3))<0 || !exponent.testBit(0))
            throw new Exception("Invalid identity-token signing key.");
        Signature verifier=Signature.getInstance("SHA256withRSA");
        verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus,exponent)));
        verifier.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
        if(!verifier.verify(decode(parts[2]))) throw new Exception("The identity token's signature was invalid.");
        JSONObject claims=new JSONObject(new String(decode(parts[1]),StandardCharsets.UTF_8));
        if(!ISSUER.equals(claims.opt("iss"))) throw new Exception("The identity token's issuer was invalid.");
        Object aud=claims.opt("aud"); boolean audience=false; int audiences=0;
        if(aud instanceof String) { audience=clientId.equals(aud); audiences=1; }
        else if(aud instanceof JSONArray) {
            JSONArray array=(JSONArray)aud; audiences=array.length();
            for(int i=0;i<array.length();i++) {
                if(!(array.opt(i) instanceof String)) throw new Exception("Invalid identity-token audience.");
                if(clientId.equals(array.getString(i))) audience=true;
            }
        }
        if(!audience || (claims.has("azp") && !clientId.equals(claims.opt("azp"))) || (audiences>1 && !clientId.equals(claims.opt("azp"))))
            throw new Exception("The identity token was issued for a different client.");
        Object sub=claims.opt("sub");
        if(!(sub instanceof String) || ((String)sub).isEmpty() || ((String)sub).length()>2048) throw new Exception("The identity token did not contain an account identity.");
        long exp=integerClaim(claims,"exp"), iat=integerClaim(claims,"iat");
        if(exp<=nowSeconds-5 || iat>nowSeconds+5 || exp<=iat) throw new Exception("The identity token was expired or its time was invalid.");
        if(claims.has("nbf") && integerClaim(claims,"nbf")>nowSeconds+5) throw new Exception("The identity token is not valid yet.");
        if(expectedNonce!=null && !same(expectedNonce,claims.optString("nonce",null))) throw new Exception("The sign-in identity could not be verified. Start again.");
        return claims;
    }
    private static long integerClaim(JSONObject object,String name) throws Exception {
        Object value=object.opt(name);
        if(!(value instanceof Integer) && !(value instanceof Long)) throw new Exception("The identity token contained an invalid "+name+" claim.");
        long result=((Number)value).longValue();
        if(result<=0) throw new Exception("The identity token contained an invalid "+name+" claim.");
        return result;
    }
    public static void verifyAccessHash(JSONObject claims, String accessToken) throws Exception {
        if(!claims.has("at_hash")) return;
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII));
        byte[] half=new byte[digest.length/2]; System.arraycopy(digest,0,half,0,half.length);
        if(!same(Base64.encodeToString(half,URL_FLAGS),claims.optString("at_hash",null))) throw new Exception("The identity and access tokens did not match.");
    }
    private AuthProtocol() {}
}
