package com.agentcv.workspace;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("m00")
@RequestMapping("/api/v1/profiles")
class WorkspaceController {
    private final WorkspaceService service;
    WorkspaceController(WorkspaceService service) { this.service=service; }
    @GetMapping Object profiles(@RequestParam(required=false) UUID after,@RequestParam(defaultValue="") String q) { return service.profiles(after,q); }
    @PostMapping ResponseEntity<?> createProfile(@RequestHeader("Idempotency-Key") UUID key,@RequestBody WorkspaceService.CreateProfile body) {
        return ResponseEntity.status(201).body(service.createProfile(key,body));
    }
    @GetMapping("/{p}/workspaces") Object workspaces(@PathVariable UUID p,@RequestParam(required=false) UUID after,@RequestParam(defaultValue="") String q) { return service.workspaces(p,after,q); }
    @PostMapping("/{p}/workspaces") ResponseEntity<?> createWorkspace(@PathVariable UUID p,@RequestHeader("Idempotency-Key") UUID key,@RequestBody WorkspaceService.CreateWorkspace body) {
        return ResponseEntity.status(201).body(service.createWorkspace(p,key,body));
    }
    @GetMapping("/{p}/workspaces/{w}") Object workspace(@PathVariable UUID p,@PathVariable UUID w) { return service.getWorkspace(p,w); }
    @GetMapping("/{p}/workspaces/{w}/resume") Object resume(@PathVariable UUID p,@PathVariable UUID w) { return service.resume(p,w); }
    @PostMapping("/{p}/workspaces/{w}/resume/initialize") Object initialize(@PathVariable UUID p,@PathVariable UUID w,@RequestHeader("Idempotency-Key") UUID key,@RequestBody WorkspaceService.EstablishInput body) { return service.establish(p,w,key,body); }
    @PutMapping("/{p}/workspaces/{w}/resume") Object save(@PathVariable UUID p,@PathVariable UUID w,@RequestHeader("Idempotency-Key") UUID key,@RequestBody WorkspaceService.ResumeInput body) { return service.save(p,w,key,body,false); }
}