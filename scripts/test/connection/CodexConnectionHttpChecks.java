package com.tango.phone;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public final class CodexConnectionHttpChecks {
    private static int checks;
    private static void require(boolean condition,String message) {checks++;if(!condition)throw new AssertionError(message);}
    private static CodexConnectionHttp.Request read(String request) throws Exception {return CodexConnectionHttp.readHead(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)));}
    private static void reject(String request,int status) throws Exception {
        try {read(request);throw new AssertionError("Accepted invalid request");}
        catch(CodexConnectionHttp.Failure expected) {require(expected.status==status,"Incorrect rejection status");}
    }
    public static void main(String[] args) throws Exception {
        String token="a".repeat(43);
        CodexConnectionHttp.Request request=read("GET /v1/status HTTP/1.1\r\nHost: 127.0.0.1:8766\r\nAuthorization: Bearer "+token+"\r\n\r\n");
        require(request.authorized(token),"Current pairing token rejected");
        require(!request.authorized("b".repeat(43)),"Wrong pairing token accepted");
        require(!request.authorized(null),"Revoked pairing token accepted");
        require(!read("GET /v1/status HTTP/1.1\r\nHost: localhost\r\n\r\n").authorized(token),"Unauthenticated request accepted");
        reject("GET /v1/status HTTP/1.1\r\nAuthorization: Bearer "+token+"\r\nAuthorization: Bearer other\r\n\r\n",400);
        reject("GET /v1/status HTTP/1.1\r\nOrigin: https://example.com\r\n\r\n",403);
        reject("POST /v1/prompts HTTP/1.1\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n",400);
        reject("POST /v1/prompts HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: 65537\r\n\r\n",413);
        reject("POST /v1/prompts HTTP/1.1\r\nContent-Length: 2\r\n\r\n",415);
        reject("POST /v1/prompts HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: -1\r\n\r\n",400);
        reject("GET /v1/status HTTP/1.1\r\nContent-Length: 2\r\n\r\n",400);
        reject("GET /v1/threads/../secret HTTP/1.1\r\n\r\n",400);
        reject("GET http://example.com/v1/status HTTP/1.1\r\n\r\n",400);
        reject("GET /v1/status HTTP/1.1\r\nX-Large: "+"a".repeat(8192)+"\r\n\r\n",431);
        String payload="{\"prompt\":\"Hello 👋\"}";
        byte[] body=payload.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream input=new ByteArrayInputStream(("POST /v1/prompts HTTP/1.1\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+body.length+"\r\n\r\n"+payload).getBytes(StandardCharsets.UTF_8));
        request=CodexConnectionHttp.readHead(input);require(CodexConnectionHttp.readBody(input,request).equals(payload),"Unicode prompt changed during parsing");
        try {CodexConnectionHttp.readBody(new ByteArrayInputStream(new byte[0]),request);throw new AssertionError("Incomplete body accepted");}
        catch(java.io.EOFException expected) {checks++;}
        System.out.println("Codex HTTP parser: "+checks+" offline checks passed.");
    }
}
