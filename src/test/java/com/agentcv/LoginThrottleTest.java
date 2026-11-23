package com.agentcv;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTest {
    @Test void boundedAttemptsReturnExplicit429() throws Exception {
        LoginThrottle filter=new LoginThrottle(new ObjectMapper());
        AtomicInteger continued=new AtomicInteger();
        for(int i=0;i<11;i++) {
            var req=new MockHttpServletRequest("POST","/login");req.setServletPath("/login");
            var res=new MockHttpServletResponse();
            filter.doFilter(req,res,(a,b)->continued.incrementAndGet());
            if(i==10){assertThat(res.getStatus()).isEqualTo(429);assertThat(res.getHeader("Retry-After")).isEqualTo("60");}
        }
        assertThat(continued.get()).isEqualTo(10);
    }
}