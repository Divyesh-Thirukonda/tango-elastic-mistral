package com.tango.authsmoke;

import android.util.Base64;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.math.BigInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real RSA signatures test OIDC validation, rather than accepting decoded JWT payloads. */
public final class ProtocolChecks {
    private final Class<?> protocol;
    private final JSONObject checks;
    public final KeyPair keys;
    public final JSONObject jwks;
    public ProtocolChecks(ClassLoader loader, JSONObject checks) throws Exception {
        protocol=loader.loadClass("com.tango.phone.AuthProtocol"); this.checks=checks;
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); keys=generator.generateKeyPair();
        RSAPublicKey rsa=(RSAPublicKey)keys.getPublic();
        jwks=new JSONObject().put("keys",new JSONArray().put(new JSONObject().put("kid","fixture-rsa")
            .put("kty","RSA").put("use","sig").put("alg","RS256").put("n",b64(unsigned(rsa.getModulus())))
            .put("e",b64(unsigned(rsa.getPublicExponent())))));
    }
    private static byte[] unsigned(BigInteger number) {
        byte[] bytes=number.toByteArray();
        if(bytes.length>1 && bytes[0]==0) { byte[] clean=new byte[bytes.length-1]; System.arraycopy(bytes,1,clean,0,clean.length); return clean; }
        return bytes;
    }
    private static String b64(byte[] bytes) { return Base64.encodeToString(bytes,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING); }
    public JSONObject claims(String client,String nonce) throws Exception {
        long now=System.currentTimeMillis()/1000;
        return new JSONObject().put("iss","https://auth.openai.com").put("aud",client).put("sub","fixture-user")
            .put("email","fixture@example.invalid").put("iat",now-1).put("exp",now+3600).put("nonce",nonce);
    }
    public String token(JSONObject claims) throws Exception {
        return token(new JSONObject().put("alg","RS256").put("kid","fixture-rsa"),claims);
    }
    public String token(JSONObject claims,String kid) throws Exception {return token(new JSONObject().put("alg","RS256").put("kid",kid),claims);}
    private String token(JSONObject header, JSONObject claims) throws Exception {
        String input=b64(header.toString().getBytes(StandardCharsets.UTF_8))+"."+b64(claims.toString().getBytes(StandardCharsets.UTF_8));
        Signature signer=Signature.getInstance("SHA256withRSA"); signer.initSign(keys.getPrivate()); signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input+"."+b64(signer.sign());
    }
    private Object call(String name,Class<?>[] types,Object... args) throws Exception {
        try { return protocol.getMethod(name,types).invoke(null,args); }
        catch(InvocationTargetException e) { if(e.getCause() instanceof Exception)throw (Exception)e.getCause(); throw new RuntimeException(e.getCause()); }
    }
    private void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private void rejected(String name, Throwing check) throws Exception {
        boolean failed=false; try { check.run(); } catch(Exception expected) { failed=true; }
        require(failed,name+" was incorrectly accepted"); checks.put(name,true);
    }
    public interface Throwing { void run() throws Exception; }
    private JSONObject verify(String token, JSONObject keys, String client, String nonce) throws Exception {
        return (JSONObject)call("verifyIdentity",new Class[]{String.class,JSONObject.class,String.class,String.class,long.class},token,keys,client,nonce,System.currentTimeMillis()/1000);
    }
    private Object callback(String uri,String state,String client,boolean initial) throws Exception {
        return call("validateCallback",new Class[]{String.class,String.class,String.class,boolean.class},uri,state,client,initial);
    }
    public void run() throws Exception {
        String challenge=(String)call("challenge",new Class[]{String.class},"dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");
        require("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM".equals(challenge),"RFC 7636 S256 test vector failed"); checks.put("pkceS256Vector",true);
        require((boolean)call("planGranted",new Class[]{String.class},"openid resource.invoke chatgpt.tokens.use.direct"),"Required scopes rejected");
        require(!(boolean)call("planGranted",new Class[]{String.class},"openid email profile"),"Identity-only login granted inference"); checks.put("identityOnlyScopeDenied",true);
        require(!(boolean)call("planGranted",new Class[]{String.class},"resource.invoke chatgpt.tokens.use.direct.extra"),"Scope prefix bypassed inference gate"); checks.put("scopePrefixDenied",true);
        Object cb=callback("/auth/callback?state=fresh&code=code1&client_id=oaiapp_fixture","fresh","dynamic_agent_client",true);
        require("oaiapp_fixture".equals(cb.getClass().getField("clientId").get(cb)),"Issued client missing"); checks.put("issuedClientAccepted",true);
        Object returning=callback("/auth/callback?state=fresh&code=code1","fresh","oaiapp_fixture",false);
        require("oaiapp_fixture".equals(returning.getClass().getField("clientId").get(returning)),"Returning client lost"); checks.put("returningClientRetained",true);
        rejected("callbackStateMismatchDenied",()->callback("/auth/callback?state=bad&code=code1&client_id=oaiapp_fixture","fresh","dynamic_agent_client",true));
        rejected("callbackDuplicateStateDenied",()->callback("/auth/callback?state=fresh&state=bad&code=code1&client_id=oaiapp_fixture","fresh","dynamic_agent_client",true));
        rejected("callbackWrongPathDenied",()->callback("/callback?state=fresh&code=code1&client_id=oaiapp_fixture","fresh","dynamic_agent_client",true));
        rejected("callbackAbsoluteUriDenied",()->callback("https://example.invalid/auth/callback?state=fresh&code=code1&client_id=oaiapp_fixture","fresh","dynamic_agent_client",true));
        rejected("callbackDynamicClientDenied",()->callback("/auth/callback?state=fresh&code=code1&client_id=dynamic_agent_client","fresh","dynamic_agent_client",true));
        rejected("callbackReturningClientSwapDenied",()->callback("/auth/callback?state=fresh&code=code1&client_id=oaiapp_other","fresh","oaiapp_fixture",false));
        rejected("callbackDeniedWithWrongStateDenied",()->callback("/auth/callback?state=bad&error=access_denied","fresh","oaiapp_fixture",false));
        Object denied=callback("/auth/callback?state=fresh&error=access_denied","fresh","oaiapp_fixture",false);
        require("access_denied".equals(denied.getClass().getField("error").get(denied)),"OAuth denial lost"); checks.put("oauthDeniedHandled",true);
        JSONObject original=claims("oaiapp_fixture","nonce1"); String valid=token(original);
        require("fixture-user".equals(verify(valid,jwks,"oaiapp_fixture","nonce1").getString("sub")),"Valid signed identity not verified"); checks.put("rsaSignatureVerified",true);
        String[] pieces=valid.split("\\."); String tampered=pieces[0]+"."+b64(claims("oaiapp_fixture","attacker").toString().getBytes(StandardCharsets.UTF_8))+"."+pieces[2];
        rejected("jwtTamperDenied",()->verify(tampered,jwks,"oaiapp_fixture","nonce1"));
        rejected("jwtWrongNonceDenied",()->verify(valid,jwks,"oaiapp_fixture","nonce2"));
        rejected("jwtWrongAudienceDenied",()->verify(valid,jwks,"oaiapp_other","nonce1"));
        rejected("jwtWrongIssuerDenied",()->verify(token(claims("oaiapp_fixture","nonce1").put("iss","https://attacker.invalid")),jwks,"oaiapp_fixture","nonce1"));
        rejected("jwtExpiredDenied",()->verify(token(claims("oaiapp_fixture","nonce1").put("exp",System.currentTimeMillis()/1000-30)),jwks,"oaiapp_fixture","nonce1"));
        rejected("jwtFutureNotBeforeDenied",()->verify(token(claims("oaiapp_fixture","nonce1").put("nbf",System.currentTimeMillis()/1000+60)),jwks,"oaiapp_fixture","nonce1"));
        rejected("jwtFloatingExpiryDenied",()->verify(token(claims("oaiapp_fixture","nonce1").put("exp",1_900_000_000.5)),jwks,"oaiapp_fixture","nonce1"));
        rejected("jwtAlgorithmNoneDenied",()->verify(token(new JSONObject().put("alg","none").put("kid","fixture-rsa"),original),jwks,"oaiapp_fixture","nonce1"));
        rejected("jwtRemoteKeyInjectionDenied",()->verify(token(new JSONObject().put("alg","RS256").put("kid","fixture-rsa").put("jku","https://attacker.invalid"),original),jwks,"oaiapp_fixture","nonce1"));
        rejected("jwtAmbiguousKeyDenied",()->verify(valid,new JSONObject().put("keys",new JSONArray().put(jwks.getJSONArray("keys").get(0)).put(jwks.getJSONArray("keys").get(0))),"oaiapp_fixture","nonce1"));
        rejected("jwtMultipleAudienceWithoutAzpDenied",()->verify(token(claims("oaiapp_fixture","nonce1").put("aud",new JSONArray().put("oaiapp_fixture").put("other"))),jwks,"oaiapp_fixture","nonce1"));
        require("fixture-user".equals(verify(token(claims("oaiapp_fixture","nonce1").put("aud",new JSONArray().put("oaiapp_fixture").put("other")).put("azp","oaiapp_fixture")),jwks,"oaiapp_fixture","nonce1").getString("sub")),"Valid azp audience rejected"); checks.put("jwtMultipleAudienceWithAzpAccepted",true);
        rejected("jwtAccessHashMismatchDenied",()->call("verifyAccessHash",new Class[]{JSONObject.class,String.class},new JSONObject().put("at_hash","attacker"),"fixture-access"));
    }
}
