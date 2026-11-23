package com.agentcv;

import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
class SessionController {
    @GetMapping("/api/v1/csrf")
    org.springframework.http.ResponseEntity<?> csrf(CsrfToken token) {
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(Map.of("token",token.getToken(),"header_name",token.getHeaderName()));
    }
    @GetMapping("/api/v1/session") Map<String,Object> session() { return Map.of("authenticated",true,"username","operator"); }
}