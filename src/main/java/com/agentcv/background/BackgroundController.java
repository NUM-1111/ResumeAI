package com.agentcv.background;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("m00")
@RequestMapping("/api/v1/profiles/{p}")
class BackgroundController {
    private final BackgroundService service;
    BackgroundController(BackgroundService service){this.service=service;}
    @GetMapping("/backgrounds") Object list(@PathVariable UUID p){return service.list(p);}
    @GetMapping("/workspaces/{w}/background-candidates") Object pending(@PathVariable UUID p,@PathVariable UUID w){return service.pending(p,w);}
    @PostMapping("/background-candidates/{c}/confirm") Object confirm(@PathVariable UUID p,@PathVariable UUID c,@RequestHeader("Idempotency-Key") UUID key,@RequestBody BackgroundService.Confirm input){return service.confirm(p,c,key,input);}
}