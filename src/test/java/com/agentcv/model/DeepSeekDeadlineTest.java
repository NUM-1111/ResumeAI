package com.agentcv.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DeepSeekDeadlineTest {
    @Test void noHeadersAndPartialBodyHaveDifferentDiagnosticStages() throws Exception {
        for(boolean partial:new boolean[]{false,true}){
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            var executor=Executors.newSingleThreadExecutor();server.setExecutor(executor);var release=new CountDownLatch(1);
            server.createContext("/chat",exchange->{exchange.getRequestBody().readAllBytes();
                if(partial){exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write("{\"id\":".getBytes());exchange.getResponseBody().flush();}
                try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}
            });server.start();
            try{var gateway=new DeepSeekGateway("synthetic-secret",new ObjectMapper(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat"),Duration.ofMillis(600));
                assertThatThrownBy(()->gateway.complete("{}" )).isInstanceOfSatisfying(ModelGateway.Failure.class,error->{
                    assertThat(error.code).isEqualTo("MODEL_RESPONSE_TIMEOUT");
                    assertThat(error.diagnostics.get("stage")).isEqualTo(partial?"BODY_RECEIVING":"WAITING_HEADERS");
                    assertThat(error.diagnostics.get("requested_http_version")).isEqualTo("HTTP_1_1");
                    assertThat(error.diagnostics.toString()).doesNotContain("synthetic-secret","Authorization","http://");
                });
            }finally{release.countDown();server.stop(0);executor.shutdownNow();}
        }
    }
    @Test void slowCompleteBodySucceedsWithinConfiguredDeadlineWithoutRetry() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext("/chat",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,0);
            try(var output=exchange.getResponseBody()){
                output.write(' ');output.flush();
                try{Thread.sleep(350);}catch(InterruptedException error){Thread.currentThread().interrupt();}
                output.write("{\"id\":\"slow-test\",\"choices\":[{\"message\":{\"content\":\"slow answer\"},\"finish_reason\":\"stop\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        });server.start();
        try{var gateway=new DeepSeekGateway("test",new ObjectMapper(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat"),Duration.ofSeconds(2));
            var result=gateway.complete("{}");assertThat(result.answer()).isEqualTo("slow answer");assertThat(result.diagnostics()).containsEntry("http_version","HTTP_1_1");assertThat(calls.get()).isEqualTo(1);
        }finally{server.stop(0);}
    }
    @Test void malformedSupplierBodyIsDistinguishedFromTimeout() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat",exchange->{exchange.getRequestBody().readAllBytes();byte[] bytes="not-json".getBytes();exchange.sendResponseHeaders(200,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}});server.start();
        try{var gateway=new DeepSeekGateway("test",new ObjectMapper(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat"),Duration.ofSeconds(2));
            assertThatThrownBy(()->gateway.complete("{}")).isInstanceOfSatisfying(ModelGateway.Failure.class,error->assertThat(error.code).isEqualTo("MODEL_INVALID_RESPONSE"));
        }finally{server.stop(0);}
    }
    @Test void emptyAnswerRetainsSupplierUsageAndDoesNotRetry() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext("/chat",exchange->{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();byte[] body="{\"id\":\"synthetic-empty\",\"choices\":[{\"message\":{\"content\":\"   \"},\"finish_reason\":\"stop\"}],\"usage\":{\"total_tokens\":42}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);try(var output=exchange.getResponseBody()){output.write(body);}});server.start();
        try{
            var gateway=new DeepSeekGateway("test",new ObjectMapper(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat"),Duration.ofSeconds(2));
            assertThatThrownBy(()->gateway.complete("{}" )).isInstanceOfSatisfying(ModelGateway.Failure.class,failure->{assertThat(failure.code).isEqualTo("MODEL_EMPTY_RESPONSE");assertThat(failure.supplierId).isEqualTo("synthetic-empty");assertThat(failure.usage.get("total_tokens")).isEqualTo(42);});assertThat(calls.get()).isEqualTo(1);
        }finally{server.stop(0);}
    }
    @Test void bodyThatNeverCompletesTimesOutWithoutRetry() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var headers=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        var executor=Executors.newSingleThreadExecutor();server.setExecutor(executor);
        server.createContext("/chat",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(' ');exchange.getResponseBody().flush();headers.countDown();
            try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
            finally{exchange.close();}
        });server.start();
        try{
            var gateway=new DeepSeekGateway("synthetic-test-key",new ObjectMapper(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat"),Duration.ofMillis(800));
            long started=System.nanoTime();
            assertThatThrownBy(()->gateway.complete("{}" )).isInstanceOfSatisfying(ModelGateway.Failure.class,failure->{assertThat(failure.unknown).isTrue();assertThat(failure.code).isEqualTo("MODEL_RESPONSE_TIMEOUT");assertThat(failure.diagnostics).containsEntry("stage","KEEPALIVE_ONLY").containsEntry("http_status",200);assertThat(((Number)failure.diagnostics.get("bytes_received")).longValue()).isPositive();});
            assertThat(headers.getCount()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(3));
            assertThat(calls.get()).isEqualTo(1);
        }finally{release.countDown();server.stop(0);executor.shutdownNow();}
    }
}