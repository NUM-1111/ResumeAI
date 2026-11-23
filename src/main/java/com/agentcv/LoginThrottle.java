package com.agentcv;

import com.agentcv.api.ApiAdvice;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.ArrayDeque;
import org.springframework.web.filter.OncePerRequestFilter;

class LoginThrottle extends OncePerRequestFilter {
    private final ArrayDeque<Long> attempts = new ArrayDeque<>();
    private final ObjectMapper json;
    LoginThrottle(ObjectMapper json) { this.json=json; }
    private synchronized boolean allowed() {
        long now=System.nanoTime();
        while(!attempts.isEmpty() && now-attempts.peekFirst() >= 60_000_000_000L) attempts.removeFirst();
        if(attempts.size() >= 10) return false;
        attempts.addLast(now); return true;
    }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException,ServletException {
        if(req.getMethod().equals("POST") && req.getServletPath().equals("/login") && !allowed()) {
            res.setStatus(429);res.setHeader("Retry-After","60");res.setContentType("application/json;charset=UTF-8");
            json.writeValue(res.getWriter(),ApiAdvice.error("RATE_LIMITED","登录尝试过于频繁，请一分钟后再试。",false));return;
        }
        chain.doFilter(req,res);
    }
}