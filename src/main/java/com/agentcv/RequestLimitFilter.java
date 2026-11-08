package com.agentcv;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class RequestLimitFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(request.getRequestURI().startsWith("/api/v1/") && request.getContentType()!=null && request.getContentType().startsWith("application/json")) {
            byte[] body=request.getInputStream().readNBytes(524289);
            if(body.length>524288) {response.sendError(413);return;}
            HttpServletRequestWrapper wrapped=new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    ByteArrayInputStream input=new ByteArrayInputStream(body);
                    return new ServletInputStream() {
                        public int read() {return input.read();}
                        public boolean isFinished() {return input.available()==0;}
                        public boolean isReady() {return true;}
                        public void setReadListener(ReadListener listener) {throw new UnsupportedOperationException();}
                    };
                }
                @Override public BufferedReader getReader() {return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
            };
            chain.doFilter(wrapped,response);
        } else chain.doFilter(request,response);
    }
}