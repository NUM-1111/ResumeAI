package com.agentcv;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.function.LongSupplier;
import org.springframework.web.filter.OncePerRequestFilter;

/** Single-operator, bounded in-process limiter; restart clears the window. */
class LoginRateFilter extends OncePerRequestFilter {
    private final ArrayDeque<Long> attempts=new ArrayDeque<>();
    private final LongSupplier clock;
    LoginRateFilter() {this(System::currentTimeMillis);}
    LoginRateFilter(LongSupplier clock) {this.clock=clock;}
    synchronized boolean allow() {
        long now=clock.getAsLong();
        while(!attempts.isEmpty() && attempts.peekFirst()<=now-60000) attempts.removeFirst();
        if(attempts.size()>=10) return false;
        attempts.addLast(now);return true;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        if(request.getMethod().equals("POST") && request.getServletPath().equals("/login") && !allow()) {
            response.setStatus(429);response.setHeader("Retry-After","60");response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("登录请求过于频繁，请等待一分钟重试");return;
        }
        chain.doFilter(request,response);
    }
}