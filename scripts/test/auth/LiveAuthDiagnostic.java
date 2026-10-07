package com.tango.authsmoke;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.HttpRetryException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public read-only network probes and an explicitly invalid dummy OAuth exchange. No user tokens. */
public final class LiveAuthDiagnostic extends Instrumentation {
    private final JSONArray probes=new JSONArray();
    private boolean localFixture;
    @Override public void onCreate(Bundle args){super.onCreate(args);localFixture=args!=null&&"true".equals(args.getString("localFixture"));start();}
    private JSONObject probe(String label,String endpoint,boolean fixed) throws Exception {
        long started=SystemClock.elapsedRealtime();JSONObject record=new JSONObject().put("probe",label);
        HttpURLConnection connection=null;
        try {
            connection=(HttpURLConnection)new URL(endpoint).openConnection();
            if("10.0.2.2".equals(connection.getURL().getHost())&&connection.getURL().getPort()==8792) {
                // Test-only pin for the controlled fixture. Production OpenAI probes retain Android's normal trust.
                final X509Certificate pinned;
                try(InputStream cert=new java.io.FileInputStream("/data/local/tmp/tango-fixture-cert.pem")){pinned=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(cert);}
                SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(null,new javax.net.ssl.TrustManager[]{new X509TrustManager(){
                    @Override public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[]{pinned};}
                    @Override public void checkClientTrusted(X509Certificate[] chain,String type)throws java.security.cert.CertificateException{throw new java.security.cert.CertificateException("Server-only fixture trust");}
                    @Override public void checkServerTrusted(X509Certificate[] chain,String type)throws java.security.cert.CertificateException{try{if(chain.length==0||!java.util.Arrays.equals(chain[0].getEncoded(),pinned.getEncoded()))throw new java.security.cert.CertificateException("Fixture certificate mismatch");pinned.checkValidity();}catch(java.security.cert.CertificateEncodingException e){throw new java.security.cert.CertificateException(e);}}
                }},null);((HttpsURLConnection)connection).setSSLSocketFactory(ssl.getSocketFactory());
            }
            connection.setConnectTimeout(12000);connection.setReadTimeout(20000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("User-Agent","Tango-Android/0.2");
            if(label.startsWith("dummy_token")) {
                // These values have never been authorized and cannot produce a valid account token.
                byte[] payload=("grant_type=authorization_code&client_id=oaiapp_tango_diagnostic_invalid&code=tango_diagnostic_invalid_code"
                    +"&code_verifier=diagnostic_invalid_verifier_has_no_real_authorization&redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback"
                    +"&resource=https%3A%2F%2Fapi.openai.com%2Fv1").getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
                if(fixed)connection.setFixedLengthStreamingMode(payload.length);
                try(java.io.OutputStream out=connection.getOutputStream()){out.write(payload);}
            }
            int status=connection.getResponseCode();record.put("status",status).put("contentType",connection.getContentType());
            InputStream stream=status>=200&&status<300?connection.getInputStream():connection.getErrorStream();
            String raw="";if(stream!=null)try(InputStream in=stream){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] chunk=new byte[4096];int count;
                while((count=in.read(chunk))!=-1){if(bytes.size()+count>1_000_000)throw new Exception("Public diagnostic response too large");bytes.write(chunk,0,count);}raw=bytes.toString("UTF-8");}
            try {
                JSONObject json=new JSONObject(raw);record.put("json",true);
                Object error=json.opt("error");String code=error instanceof String?(String)error:error instanceof JSONObject?((JSONObject)error).optString("code"):"";
                if(code.matches("[A-Za-z0-9_.-]{1,120}"))record.put("errorCode",code);
                if(label.startsWith("discovery"))record.put("expectedIssuer", "https://auth.openai.com".equals(json.optString("issuer")))
                    .put("expectedTokenEndpoint","https://auth.openai.com/api/accounts/oauth/token".equals(json.optString("token_endpoint")));
                if(label.equals("jwks"))record.put("publicKeys",json.optJSONArray("keys")==null?0:json.getJSONArray("keys").length());
            } catch(org.json.JSONException malformed){record.put("json",false);}
            record.put("success",label.contains("redirect")?status==302:label.startsWith("dummy_token")?status>=400&&status<500:status==200);
        } catch(Exception error) {
            record.put("success",false).put("exception",error.getClass().getName());
            if(error instanceof HttpRetryException){int status=((HttpRetryException)error).responseCode();record.put("retryStatus",status);if(endpoint.startsWith("https://10.0.2.2:8792/"))record.put("success",status==401||status==302);}
            // Exception class and cause classes distinguish TLS/DNS/timeouts without logging credentials or URLs.
            JSONArray chain=new JSONArray();Throwable cause=error;for(int i=0;cause!=null&&i<8;i++,cause=cause.getCause())chain.put(cause.getClass().getName());record.put("causeClasses",chain);
            String message=error.getMessage();if(message!=null){String safe=message.replaceAll("https?://\\S+","[endpoint]").replaceAll("(?i)(code|token|verifier|state|nonce)=[^\\s&]+","$1=[redacted]");record.put("safeMessage",safe.substring(0,Math.min(200,safe.length())));}
        } finally {if(connection!=null)connection.disconnect();record.put("elapsedMs",SystemClock.elapsedRealtime()-started);probes.put(record);}
        return record;
    }
    @Override public void onStart(){Bundle result=new Bundle();try{
        probe("discovery","https://auth.openai.com/.well-known/openid-configuration",false);
        probe("jwks","https://auth.openai.com/.well-known/jwks.json",false);
        probe("dummy_token_fixed","https://auth.openai.com/api/accounts/oauth/token",true);
        probe("dummy_token_buffered","https://auth.openai.com/api/accounts/oauth/token",false);
        // Repeat a public GET after the POST to expose connection-pool/TLS reuse problems.
        probe("discovery_after_post","https://auth.openai.com/.well-known/openid-configuration",false);
        if(localFixture){
            probe("dummy_token_local401_fixed","https://10.0.2.2:8792/fixed401",true);
            probe("dummy_token_local401_buffered","https://10.0.2.2:8792/fixed401",false);
            probe("dummy_token_local403_fixed","https://10.0.2.2:8792/fixed403",true);
            probe("dummy_token_local_redirect_fixed","https://10.0.2.2:8792/redirect",true);
        }
        boolean success=true;for(int i=0;i<probes.length();i++)success&=probes.getJSONObject(i).optBoolean("success");
        result.putString("success",Boolean.toString(success));result.putString("probes",probes.toString());result.putString("userCredentialsUsed","false");result.putString("inferenceRequests","0");
    }catch(Throwable error){result.putString("success","false");result.putString("errorClass",error.getClass().getName());}finish("true".equals(result.getString("success"))?0:1,result);}
}
