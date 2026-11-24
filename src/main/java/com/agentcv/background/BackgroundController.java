package com.agentcv.background;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("m00")
@RequestMapping("/api/v1/profiles/{p}")
class BackgroundController {
    private final BackgroundService service;
    private final BackgroundChangeService changes;
    BackgroundController(BackgroundService service,BackgroundChangeService changes){this.service=service;this.changes=changes;}
    @PostMapping("/backgrounds/{b}/changes") Object prepare(@PathVariable UUID p,@PathVariable UUID b,@RequestHeader("Idempotency-Key") UUID key,@RequestBody BackgroundChangeService.Prepare input){return changes.prepare(p,b,key,input);}
    @GetMapping("/workspaces/{w}/background-changes") Object changes(@PathVariable UUID p,@PathVariable UUID w){return changes.pending(p,w);}
    @PostMapping("/background-changes/{c}/confirm") Object apply(@PathVariable UUID p,@PathVariable UUID c,@RequestHeader("Idempotency-Key") UUID key,@RequestBody BackgroundChangeService.Confirm input){return changes.confirm(p,c,key,input);}
    public record Dismiss(UUID workspace_id) {}
    @PostMapping("/background-changes/{c}/dismiss") Object dismiss(@PathVariable UUID p,@PathVariable UUID c,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Dismiss input){return changes.dismiss(p,c,key,input.workspace_id);}
    @GetMapping("/backgrounds") Object list(@PathVariable UUID p){return service.list(p);}
    @GetMapping("/workspaces/{w}/background-candidates") Object pending(@PathVariable UUID p,@PathVariable UUID w){return service.pending(p,w);}
    @PostMapping("/background-candidates/{c}/confirm") Object confirm(@PathVariable UUID p,@PathVariable UUID c,@RequestHeader("Idempotency-Key") UUID key,@RequestBody BackgroundService.Confirm input){return service.confirm(p,c,key,input);}
}