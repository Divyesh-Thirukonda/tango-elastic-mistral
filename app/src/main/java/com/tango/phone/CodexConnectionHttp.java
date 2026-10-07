package com.tango.phone;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Small, bounded HTTP/1.1 parser for the optional USB connection. No browser/CORS access. */
public final class CodexConnectionHttp {
    static final int MAX_HEADERS = 8192, MAX_BODY = 65536;
    static final class Request {
        final String method, path;
        final Map<String,String> headers;
        final int contentLength;
        Request(String method,String path,Map<String,String> headers,int contentLength) {
            this.method=method; this.path=path; this.headers=headers; this.contentLength=contentLength;
        }
        boolean authorized(String token) {
            String provided=headers.get("authorization");
            return token!=null && !token.isEmpty() && provided!=null && provided.startsWith("Bearer ")
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),provided.substring(7).getBytes(StandardCharsets.UTF_8));
        }
    }
    static final class Failure extends IOException {
        final int status;
        Failure(int status,String message) { super(message); this.status=status; }
    }
    static Request readHead(InputStream input) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); int matched=0;
        byte[] ending={13,10,13,10};
        while(matched<4) {
            int value=input.read(); if(value<0)throw new EOFException("Incomplete HTTP request.");
            bytes.write(value); if(bytes.size()>MAX_HEADERS)throw new Failure(431,"Request headers are too large.");
            matched=value==ending[matched]?matched+1:(value==13?1:0);
        }
        String[] lines=bytes.toString(StandardCharsets.ISO_8859_1.name()).split("\r\n");
        String[] first=lines[0].split(" ",-1);
        if(first.length!=3 || !first[2].equals("HTTP/1.1") || !(first[0].equals("GET")||first[0].equals("POST"))
                || !first[1].matches("/v1/[A-Za-z0-9/_-]*"))throw new Failure(400,"Use a supported HTTP request path.");
        Map<String,String> headers=new LinkedHashMap<>();
        for(int i=1;i<lines.length;i++) {
            int separator=lines[i].indexOf(':');
            if(separator<1 || !lines[i].substring(0,separator).matches("[A-Za-z0-9-]+"))throw new Failure(400,"Malformed request header.");
            String name=lines[i].substring(0,separator).toLowerCase(Locale.ROOT), value=lines[i].substring(separator+1).trim();
            if(headers.put(name,value)!=null)throw new Failure(400,"Duplicate request header.");
        }
        if(headers.containsKey("origin"))throw new Failure(403,"Use the local Codex client, not a browser.");
        if(headers.containsKey("transfer-encoding"))throw new Failure(400,"Chunked requests are not supported.");
        String declared=headers.getOrDefault("content-length","0");
        if(!declared.matches("[0-9]{1,9}"))throw new Failure(400,"Invalid Content-Length.");
        int length=Integer.parseInt(declared);
        if(length>MAX_BODY)throw new Failure(413,"Request body is too large.");
        if(first[0].equals("GET") && length!=0)throw new Failure(400,"GET requests cannot contain a body.");
        if(first[0].equals("POST") && !headers.getOrDefault("content-type","").toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;.*)?"))
            throw new Failure(415,"Use Content-Type: application/json.");
        return new Request(first[0],first[1],headers,length);
    }
    static String readBody(InputStream input,Request request) throws IOException {
        byte[] bytes=new byte[request.contentLength]; int offset=0;
        while(offset<bytes.length) {int n=input.read(bytes,offset,bytes.length-offset);if(n<0)throw new EOFException("Incomplete request body.");offset+=n;}
        return new String(bytes,StandardCharsets.UTF_8);
    }
    private CodexConnectionHttp() {}
}
