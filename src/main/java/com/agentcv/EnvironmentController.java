package com.agentcv;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class EnvironmentController {
    private final JdbcClient jdbc;
    private final Flyway flyway;

    EnvironmentController(JdbcClient jdbc, Flyway flyway) {
        this.jdbc = jdbc;
        this.flyway = flyway;
    }

    @GetMapping("/api/v1/csrf")
    ResponseEntity<Map<String, String>> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
                "token", token.getToken(), "headerName", token.getHeaderName(),
                "parameterName", token.getParameterName()));
    }

    @GetMapping("/api/v1/environment")
    ResponseEntity<Map<String, String>> environment() {
        String marker = jdbc.sql("select marker from environment_checkpoint where id = 1")
                .query(String.class).single();
        String version = flyway.info().current().getVersion().getVersion();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
                "application", "AgentCV", "database", "CONNECTED",
                "migrationVersion", version, "marker", marker,
                "scope", "ENVIRONMENT_ONLY"));
    }
}