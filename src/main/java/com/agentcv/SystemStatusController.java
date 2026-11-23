package com.agentcv;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SystemStatusController {
    @GetMapping("/api/v1/system/status")
    Map<String, String> status() {
        return Map.of("application", "AgentCV", "stage", "skeleton");
    }
}
