package com.agentcv;
import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
class LoginRateTest {
 @Test void boundedWindowRejectsThenExpires(){
  AtomicLong now=new AtomicLong(100000);LoginRateFilter limiter=new LoginRateFilter(now::get);
  for(int i=0;i<10;i++)assertThat(limiter.allow()).isTrue();
  assertThat(limiter.allow()).isFalse();now.addAndGet(60000);assertThat(limiter.allow()).isTrue();
 }
}