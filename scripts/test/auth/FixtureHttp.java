package com.tango.authsmoke;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Process-local test transport. Every HTTPS connection is intercepted or rejected. */
public final class FixtureHttp {
    public interface Responder { Response answer(URL url, String method, String body) throws Exception; }
    public interface BodySource { InputStream open() throws java.io.IOException; }
    public static final class Response {
        final int code;
        final String body;
        final String contentType;
        BodySource source;
        final java.util.Map<String,String> headers=new java.util.HashMap<>();
        public Response(int code, String body) { this(code,body,"application/json"); }
        public Response(int code, String body, String type) { this.code=code; this.body=body; this.contentType=type; }
        public Response header(String name,String value) { headers.put(name.toLowerCase(java.util.Locale.ROOT),value);return this; }
        public Response source(BodySource value) { source=value;return this; }
    }
    public static volatile Responder responder;
    public static final List<String> calls=new ArrayList<>();
    public static void install() {
        URL.setURLStreamHandlerFactory(protocol -> protocol.equals("https") ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) { return new Connection(url); }
        } : null);
    }
    private static final class Connection extends HttpURLConnection {
        private final ByteArrayOutputStream sent=new ByteArrayOutputStream();
        private Response response;
        Connection(URL url) { super(url); }
        private synchronized Response result() throws java.io.IOException {
            if(response==null) {
                synchronized(calls) { calls.add(url.getHost()+url.getPath()); }
                try {
                    if(responder==null)throw new AssertionError("Unexpected HTTPS request: "+url.getHost()+url.getPath());
                    response=responder.answer(url,getRequestMethod(),sent.toString(StandardCharsets.UTF_8.name()));
                    if(response==null)throw new AssertionError("Unexpected HTTPS request: "+url.getHost()+url.getPath());
                } catch(Exception e) { throw new java.io.IOException(e); }
            }
            return response;
        }
        @Override public int getResponseCode() throws java.io.IOException { return result().code; }
        @Override public InputStream getInputStream() throws java.io.IOException {
            Response r=result(); if(r.code>=400)throw new java.io.IOException("HTTP "+r.code);
            return r.source==null?new ByteArrayInputStream(r.body.getBytes(StandardCharsets.UTF_8)):r.source.open();
        }
        @Override public InputStream getErrorStream() {
            try { return new ByteArrayInputStream(result().body.getBytes(StandardCharsets.UTF_8)); }
            catch(Exception error) { throw new RuntimeException(error); }
        }
        @Override public String getContentType() { try { return result().contentType; } catch(Exception e) { throw new RuntimeException(e); } }
        @Override public String getHeaderField(String name) { try{return name.equalsIgnoreCase("Content-Type")?getContentType():result().headers.get(name.toLowerCase(java.util.Locale.ROOT));}catch(Exception e){throw new RuntimeException(e);} }
        @Override public OutputStream getOutputStream() { return sent; }
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { connected=true; }
    }
    private FixtureHttp() {}
}
