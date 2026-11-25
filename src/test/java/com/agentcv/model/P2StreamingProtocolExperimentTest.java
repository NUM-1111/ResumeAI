package com.agentcv.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Isolated design experiment: deliberately not wired to the production gateway. */
class P2StreamingProtocolExperimentTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static String event(Object value) throws Exception {return "data: "+JSON.writeValueAsString(value)+"\r\n\r\n";}
    private static Map<String,Object> chunk(Object delta,Object finish){
        Map<String,Object> choice=new LinkedHashMap<>();choice.put("index",0);choice.put("delta",delta);choice.put("finish_reason",finish);
        return Map.of("choices",List.of(choice));
    }
    private static Map<String,Object> tool(int index,String id,String name,String arguments){
        var fn=new LinkedHashMap<String,Object>();if(name!=null)fn.put("name",name);fn.put("arguments",arguments);
        var call=new LinkedHashMap<String,Object>();call.put("index",index);if(id!=null){call.put("id",id);call.put("type","function");}call.put("function",fn);return call;
    }
    private static String validWire() throws Exception {
        return ": heartbeat\r\n\r\n"+event(chunk(Map.of("content","合成说明，不是真实经历。"),null))
            +event(chunk(Map.of("tool_calls",List.of(tool(0,"call-resume","propose_resume_change","{\"text\":\"接口"),tool(1,"call-background","propose_background_entries","{\"items\":[\"协助"))),null))
            +event(chunk(Map.of("tool_calls",List.of(tool(1,null,null,"部署\"]}"),tool(0,null,null,"开发\\n保留\\\"引号\\\"\"}"))),null))
            +event(chunk(Map.of(),"tool_calls"))+event(Map.of("choices",List.of(),"usage",Map.of("total_tokens",42)))+"data: [DONE]\r\n\r\n";
    }
    private static final class Draft {String id,name;final StringBuilder arguments=new StringBuilder();}
    /** Bounded SSE decoder + aggregation spike, not a complete provider/client implementation. */
    private static final class Probe {
        final StringBuilder text=new StringBuilder();final Map<Integer,Draft> tools=new TreeMap<>();
        String finish;boolean done;JsonNode usage;
        void read(InputStream input) throws Exception {
            var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            var reader=new InputStreamReader(input,decoder);StringBuilder line=new StringBuilder(),data=new StringBuilder();
            boolean cr=false;int total=0,value;
            while((value=reader.read())!=-1){
                if(++total>2*1024*1024)throw new IOException("STREAM_LIMIT");char c=(char)value;
                if(cr&&c=='\n'){cr=false;continue;}cr=false;
                if(c=='\r'||c=='\n'){consumeLine(line.toString(),data);line.setLength(0);cr=c=='\r';}
                else {line.append(c);if(line.length()>65536)throw new IOException("FRAME_LIMIT");}
            }
            // No implicit dispatch of incomplete frames at EOF.
            if(!done||!line.isEmpty()||!data.isEmpty())throw new IOException("INCOMPLETE_STREAM");
            if(!Set.of("stop","tool_calls").contains(finish==null?"":finish))throw new IOException("INCOMPLETE_RESULT");
            if((tools.isEmpty()&&!"stop".equals(finish))||(!tools.isEmpty()&&!"tool_calls".equals(finish)))throw new IOException("FINISH_MISMATCH");
            for(var draft:tools.values()){
                if(draft.id==null||!Set.of("propose_resume_change","propose_background_entries").contains(draft.name==null?"":draft.name))throw new IOException("INVALID_TOOL");
                var parser=JSON.getFactory().createParser(draft.arguments.toString());
                JsonNode args=JSON.readTree(parser);if(args==null||!args.isObject()||parser.nextToken()!=null)throw new IOException("INVALID_ARGUMENTS");
            }
        }
        void consumeLine(String line,StringBuilder data) throws Exception {
            if(line.isEmpty()){
                if(!data.isEmpty()){String body=data.substring(0,data.length()-1);data.setLength(0);accept(body);}return;
            }
            if(line.startsWith(":"))return;
            int colon=line.indexOf(':');String field=colon<0?line:line.substring(0,colon);
            String value=colon<0?"":line.substring(colon+1);if(value.startsWith(" "))value=value.substring(1);
            if(field.equals("data")){data.append(value).append('\n');if(data.length()>65536)throw new IOException("FRAME_LIMIT");}
        }
        void accept(String body) throws Exception {
            if(done)throw new IOException("AFTER_DONE");if(body.equals("[DONE]")){done=true;return;}
            var node=JSON.readTree(body);if(node.hasNonNull("usage"))usage=node.get("usage");
            for(var choice:node.path("choices")){
                if(choice.path("index").asInt(-1)!=0)throw new IOException("CHOICE_UNSUPPORTED");
                if(finish!=null)throw new IOException("AFTER_FINISH");
                var delta=choice.path("delta");if(delta.path("content").isTextual())text.append(delta.get("content").asText());
                for(var call:delta.path("tool_calls")){
                    int index=call.path("index").asInt(-1);if(index<0||index>=4)throw new IOException("TOOL_LIMIT");
                    var draft=tools.computeIfAbsent(index,k->new Draft());
                    if(call.has("id")){String id=call.get("id").asText();if(draft.id!=null&&!draft.id.equals(id))throw new IOException("TOOL_ID_CHANGED");draft.id=id;}
                    var function=call.path("function");if(function.has("name")){String name=function.get("name").asText();if(draft.name!=null&&!draft.name.equals(name))throw new IOException("TOOL_NAME_CHANGED");draft.name=name;}
                    draft.arguments.append(function.path("arguments").asText(""));
                }
                if(choice.hasNonNull("finish_reason"))finish=choice.get("finish_reason").asText();
            }
        }
    }
    private static Probe parse(String wire) throws Exception {var probe=new Probe();probe.read(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)));return probe;}

    @Test void fragmentedHttpStreamAggregatesInterleavedToolsWithoutDisplayingArguments() throws Exception {
        var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] wire=validWire().getBytes(StandardCharsets.UTF_8);
        server.createContext("/chat",exchange->{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            try(var output=exchange.getResponseBody()){for(byte value:wire){output.write(value);output.flush();}}});server.start();
        try{
            var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat")).timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.ofString("{\"stream\":true}")).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());var probe=new Probe();
            try(var input=response.body()){probe.read(input);}
            assertThat(probe.text.toString()).isEqualTo("合成说明，不是真实经历。");
            assertThat(probe.tools).hasSize(2);assertThat(JSON.readTree(probe.tools.get(0).arguments.toString()).path("text").asText()).isEqualTo("接口开发\n保留\"引号\"");
            assertThat(probe.usage.path("total_tokens").asInt()).isEqualTo(42);assertThat(calls.get()).isEqualTo(1);
        }finally{server.stop(0);}
    }
    @Test void decodingSurvivesEverySingleByteBoundary() throws Exception {
        byte[] bytes=validWire().getBytes(StandardCharsets.UTF_8);var probe=new Probe();
        var source=new ByteArrayInputStream(bytes){@Override public synchronized int read(byte[] b,int offset,int length){return super.read(b,offset,Math.min(length,1));}};
        probe.read(source);assertThat(probe.text.toString()).contains("合成");assertThat(probe.tools).hasSize(2);
    }
    @Test void toolOnlyAndPlainChatAreBothValid() throws Exception {
        var toolOnly=parse(event(chunk(Map.of("tool_calls",List.of(tool(0,"one","propose_resume_change","{\"text\":\"合成\"}"))),"tool_calls"))+"data: [DONE]\n\n");
        assertThat(toolOnly.text).isEmpty();assertThat(toolOnly.tools).hasSize(1);
        assertThat(parse(event(chunk(Map.of("content","建议"),"stop"))+"data: [DONE]\n\n").text.toString()).isEqualTo("建议");
    }
    @Test void missingTerminatorTruncatedArgumentsAndLengthFinishNeverBecomeReady() throws Exception {
        assertThatThrownBy(()->parse(validWire().replace("data: [DONE]\r\n\r\n",""))).hasMessage("INCOMPLETE_STREAM");
        assertThatThrownBy(()->parse(event(chunk(Map.of("tool_calls",List.of(tool(0,"one","propose_resume_change","{\"text\":"))),"tool_calls"))+"data: [DONE]\n\n")).isInstanceOf(IOException.class);
        assertThatThrownBy(()->parse(event(chunk(Map.of("content","部分"),"length"))+"data: [DONE]\n\n")).hasMessage("INCOMPLETE_RESULT");
    }
    @Test void multilineDataAndHeartbeatsRespectSseFraming() throws Exception {
        var wire=": heartbeat\n\ndata: {\"choices\":\ndata: [{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
        assertThat(parse(wire).text.toString()).isEqualTo("ok");
    }
    @Test void invalidEncodingOversizedFramesAndToolIdentityChangesAreRejected() throws Exception {
        assertThatThrownBy(()->new Probe().read(new ByteArrayInputStream(new byte[]{(byte)0xc3,0x28}))).isInstanceOf(CharacterCodingException.class);
        assertThatThrownBy(()->parse("data: "+"x".repeat(65537))).hasMessage("FRAME_LIMIT");
        String wire=event(chunk(Map.of("tool_calls",List.of(tool(0,"one","propose_resume_change","{"))),null))
            +event(chunk(Map.of("tool_calls",List.of(tool(0,"two",null,"}"))),"tool_calls"))+"data: [DONE]\n\n";
        assertThatThrownBy(()->parse(wire)).hasMessage("TOOL_ID_CHANGED");
    }
}