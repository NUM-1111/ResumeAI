package com.agentcv.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekGateway implements ModelGateway {
    private final String key;
    private final ObjectMapper json;
    private final URI endpoint;
    private final Duration deadline;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    @org.springframework.beans.factory.annotation.Autowired
    public DeepSeekGateway(@Value("${DEEPSEEK_API_KEY:}") String key,ObjectMapper json,@Value("${agentcv.ai.response-timeout-seconds:180}") long timeout){this(key,json,URI.create("https://api.deepseek.com/chat/completions"),Duration.ofSeconds(timeout));}
    DeepSeekGateway(String key,ObjectMapper json,URI endpoint,Duration deadline){if(deadline.isNegative()||deadline.isZero()||deadline.compareTo(Duration.ofMinutes(10))>0)throw new IllegalArgumentException("Response timeout must be positive and at most 600 seconds");this.key=key;this.json=json;this.endpoint=endpoint;this.deadline=deadline;}
    public boolean configured(){return !key.isBlank();}
    public String model(){return "deepseek-flash";}
    public Result complete(String inputJson){
        if(!configured())throw new Failure("MODEL_NOT_CONFIGURED",false);
        var trace=new Trace();
        try{
            HttpRequest request=HttpRequest.newBuilder(endpoint)
                    .timeout(deadline).header("Authorization","Bearer "+key)
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(inputJson)).build();
            // One send only; never retry a timeout or follow a redirect with credentials.
            var pending=client.sendAsync(request,info->{trace.headers(info);return new BoundedBody(trace);});
            HttpResponse<byte[]> response;
            try{response=pending.get(deadline.toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS);}
            catch(java.util.concurrent.ExecutionException failure){pending.cancel(true);if(failure.getCause() instanceof Failure known)throw known;if(failure.getCause() instanceof HttpTimeoutException)throw new Failure("MODEL_RESPONSE_TIMEOUT",true);throw new Failure("MODEL_TRANSPORT_ERROR",true);}
            catch(java.util.concurrent.TimeoutException failure){pending.cancel(true);throw new Failure("MODEL_RESPONSE_TIMEOUT",true);}
            catch(Exception failure){pending.cancel(true);throw failure;}
            {
                int status=response.statusCode();
                if(status!=200)throw new Failure("SUPPLIER_HTTP_"+status,status>=500||status==408);
                byte[] bytes=response.body();
                if(bytes.length>2*1024*1024)throw new Failure("MODEL_RESPONSE_TOO_LARGE",true);
                var result=json.readTree(bytes);
                String answer=result.path("choices").path(0).path("message").path("content").asText("");
                Map<String,Object> usage=result.has("usage")?json.convertValue(result.get("usage"),new TypeReference<Map<String,Object>>(){}):Map.of();
                if(answer.isBlank())throw new Failure("MODEL_EMPTY_RESPONSE",true,result.path("id").asText(""),usage);
                if(!"stop".equals(result.path("choices").path(0).path("finish_reason").asText()))
                    answer+="\n\n[本次回答达到输出限制或未正常结束，请核对完整性。]";
                return new Result(answer,result.path("id").asText(""),usage,trace.snapshot());
            }
        }catch(Failure e){throw traced(e,trace);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw traced(new Failure("MODEL_WAIT_INTERRUPTED",true),trace);}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw traced(new Failure("MODEL_INVALID_RESPONSE",true),trace);}
        catch(Exception e){throw traced(new Failure("MODEL_RESULT_UNKNOWN",true),trace);}
    }
    private Failure traced(Failure failure,Trace trace){failure.diagnostics=trace.snapshot();return failure;}
    private static final class Trace {
        private final long started=System.nanoTime();
        private Long headersMs,firstByteMs,firstContentMs,lastByteMs;
        private Integer status;private String version;
        private long bytes,whitespace;
        synchronized void headers(HttpResponse.ResponseInfo info){headersMs=elapsed();status=info.statusCode();version=info.version().toString();}
        synchronized void received(byte[] part){
            long now=elapsed();if(firstByteMs==null)firstByteMs=now;lastByteMs=now;bytes+=part.length;
            for(byte value:part){if(value==32||value==9||value==10||value==13)whitespace++;else if(firstContentMs==null)firstContentMs=now;}
        }
        private long elapsed(){return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);}
        synchronized Map<String,Object> snapshot(){
            Map<String,Object> data=new java.util.LinkedHashMap<>();
            data.put("requested_http_version","HTTP_1_1");
            data.put("elapsed_ms",elapsed());data.put("bytes_received",bytes);data.put("whitespace_bytes",whitespace);
            data.put("stage",headersMs==null?"WAITING_HEADERS":bytes==0?"WAITING_BODY":firstContentMs==null?"KEEPALIVE_ONLY":"BODY_RECEIVING");
            if(headersMs!=null){data.put("headers_ms",headersMs);data.put("http_status",status);data.put("http_version",version);}
            if(firstByteMs!=null){data.put("first_byte_ms",firstByteMs);data.put("last_byte_ms",lastByteMs);}
            if(firstContentMs!=null)data.put("first_content_ms",firstContentMs);
            return java.util.Collections.unmodifiableMap(data);
        }
    }
    /** Enforce the cap while receiving, not after an unbounded allocation. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final Trace trace;
        BoundedBody(Trace trace){this.trace=trace;}
        private final java.util.concurrent.CompletableFuture<byte[]> body=new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<byte[]> getBody(){return body;}
        public void onSubscribe(java.util.concurrent.Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers){
            for(var buffer:buffers){
                if(buffer.remaining()>2*1024*1024-bytes.size()){subscription.cancel();body.completeExceptionally(new Failure("MODEL_RESPONSE_TOO_LARGE",true));return;}
                byte[] part=new byte[buffer.remaining()];buffer.get(part);trace.received(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error){body.completeExceptionally(error);}
        public void onComplete(){body.complete(bytes.toByteArray());}
    }
}