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
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public DeepSeekGateway(@Value("${DEEPSEEK_API_KEY:}") String key,ObjectMapper json){this.key=key;this.json=json;}
    public boolean configured(){return !key.isBlank();}
    public String model(){return "deepseek-flash";}
    public Result complete(String inputJson){
        if(!configured())throw new Failure("MODEL_NOT_CONFIGURED",false);
        try{
            HttpRequest request=HttpRequest.newBuilder(URI.create("https://api.deepseek.com/chat/completions"))
                    .timeout(Duration.ofSeconds(120)).header("Authorization","Bearer "+key)
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(inputJson)).build();
            // One send only; never retry a timeout or follow a redirect with credentials.
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()){
                int status=response.statusCode();
                if(status!=200)throw new Failure("SUPPLIER_HTTP_"+status,status>=500||status==408);
                byte[] bytes=stream.readNBytes(2*1024*1024+1);
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
}