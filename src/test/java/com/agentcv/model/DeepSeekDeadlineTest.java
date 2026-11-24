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
            assertThatThrownBy(()->gateway.complete("{}" )).isInstanceOfSatisfying(ModelGateway.Failure.class,failure->assertThat(failure.unknown).isTrue());
            assertThat(headers.getCount()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(3));
            assertThat(calls.get()).isEqualTo(1);
        }finally{release.countDown();server.stop(0);executor.shutdownNow();}
    }
}