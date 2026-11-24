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
            .followRedirects(HttpClient.Redirect.NEVER).build();
    @org.springframework.beans.factory.annotation.Autowired
    public DeepSeekGateway(@Value("${DEEPSEEK_API_KEY:}") String key,ObjectMapper json){this(key,json,URI.create("https://api.deepseek.com/chat/completions"),Duration.ofSeconds(60));}
    DeepSeekGateway(String key,ObjectMapper json,URI endpoint,Duration deadline){this.key=key;this.json=json;this.endpoint=endpoint;this.deadline=deadline;}
    public boolean configured(){return !key.isBlank();}
    public String model(){return "deepseek-flash";}
    public Result complete(String inputJson){
        if(!configured())throw new Failure("MODEL_NOT_CONFIGURED",false);
        try{
            HttpRequest request=HttpRequest.newBuilder(endpoint)
                    .timeout(deadline).header("Authorization","Bearer "+key)
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(inputJson)).build();
            // One send only; never retry a timeout or follow a redirect with credentials.
            var pending=client.sendAsync(request,info->new BoundedBody());
            HttpResponse<byte[]> response;
            try{response=pending.get(deadline.toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS);}
            catch(java.util.concurrent.ExecutionException failure){pending.cancel(true);if(failure.getCause() instanceof Failure known)throw known;throw failure;}
            catch(Exception failure){pending.cancel(true);throw failure;}
            {
                int status=response.statusCode();
                if(status!=200)throw new Failure("SUPPLIER_HTTP_"+status,status>=500||status==408);
                byte[] bytes=response.body();
                if(bytes.length>2*1024*1024)throw new Failure("MODEL_RESPONSE_TOO_LARGE",true);
                var result=json.readTree(bytes);
                String answer=result.path("choices").path(0).path("message").path("content").asText("");
                if(answer.isBlank())throw new Failure("MODEL_EMPTY_RESPONSE",true);
                Map<String,Object> usage=result.has("usage")?json.convertValue(result.get("usage"),new TypeReference<Map<String,Object>>(){}):Map.of();
                if(!"stop".equals(result.path("choices").path(0).path("finish_reason").asText()))
                    answer+="\n\n[本次回答达到输出限制或未正常结束，请核对完整性。]";
                return new Result(answer,result.path("id").asText(""),usage);
            }
        }catch(Failure e){throw e;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failure("MODEL_RESULT_UNKNOWN",true);}
        catch(Exception e){throw new Failure("MODEL_RESULT_UNKNOWN",true);}
    }
    /** Enforce the cap while receiving, not after an unbounded allocation. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> body=new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<byte[]> getBody(){return body;}
        public void onSubscribe(java.util.concurrent.Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers){
            for(var buffer:buffers){
                if(buffer.remaining()>2*1024*1024-bytes.size()){subscription.cancel();body.completeExceptionally(new Failure("MODEL_RESPONSE_TOO_LARGE",true));return;}
                byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error){body.completeExceptionally(error);}
        public void onComplete(){body.complete(bytes.toByteArray());}
    }
}