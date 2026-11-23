package com.agentcv.conversation;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("m00")
@RequestMapping("/api/v1")
class ConversationController {
    private final ConversationService service;
    ConversationController(ConversationService service){this.service=service;}
    @GetMapping("/model") Object model(){return service.config();}
    @GetMapping("/profiles/{p}/workspaces/{w}/ai-scope") Object scope(@PathVariable UUID p,@PathVariable UUID w){return service.scope(p,w);}
    @GetMapping("/profiles/{p}/workspaces/{w}/conversation") Object conversation(@PathVariable UUID p,@PathVariable UUID w){return service.conversation(p,w);}
    @PostMapping("/profiles/{p}/workspaces/{w}/conversation") ResponseEntity<?> send(@PathVariable UUID p,@PathVariable UUID w,@RequestHeader("Idempotency-Key") UUID key,@RequestBody ConversationService.Send body){return ResponseEntity.accepted().body(service.send(p,w,key,body));}
    @GetMapping("/profiles/{p}/workspaces/{w}/requests/{r}") Object request(@PathVariable UUID p,@PathVariable UUID w,@PathVariable UUID r){return service.get(p,w,r);}
    @PostMapping("/profiles/{p}/workspaces/{w}/requests/{r}/acknowledge-unknown") Object acknowledge(@PathVariable UUID p,@PathVariable UUID w,@PathVariable UUID r,@RequestHeader("Idempotency-Key") UUID key){return service.acknowledgeUnknown(p,w,r);}
    @PostMapping("/profiles/{p}/workspaces/{w}/requests/{r}/persist") Object persist(@PathVariable UUID p,@PathVariable UUID w,@PathVariable UUID r,@RequestHeader("Idempotency-Key") UUID key){return service.retryPersist(p,w,r);}
}