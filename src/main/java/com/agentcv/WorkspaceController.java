package com.agentcv;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class WorkspaceController {
    private final WorkspaceService service;
    public WorkspaceController(WorkspaceService service) {this.service=service;}
    Map<String,Object> write(String scope,String key,Map<String,Object>b,UUID p,Supplier<Map<String,Object>> action) {
        return service.write(scope,WorkspaceService.id(key),b,p,action);
    }
    @GetMapping("/profiles") Object profiles(@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) UUID after) {return service.list(null,"profiles",limit,after);}
    @PostMapping("/profiles") ResponseEntity<?> createProfile(@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object>b) {
        return ResponseEntity.status(201).body(write("POST/profiles",key,b,null,()->service.createProfile(b)));
    }
    @GetMapping("/profiles/{p}/workspaces") Object workspaces(@PathVariable UUID p,@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) UUID after) {return service.list(p,"workspaces",limit,after);}
    @PostMapping("/profiles/{p}/workspaces") ResponseEntity<?> createWorkspace(@PathVariable UUID p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object>b) {
        return ResponseEntity.status(201).body(write("POST/"+p+"/workspaces",key,b,p,()->service.createWorkspace(p,b)));
    }
    @PatchMapping("/profiles/{p}/workspaces/{w}") Object changeWorkspace(@PathVariable UUID p,@PathVariable UUID w,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object>b) {
        return write("PATCH/"+p+"/"+w,key,b,p,()->service.changeWorkspace(p,w,b));
    }
    @GetMapping("/profiles/{p}/material-imports") Object imports(@PathVariable UUID p,@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) UUID after) {return service.list(p,"material-imports",limit,after);}
    @PostMapping("/profiles/{p}/material-imports") ResponseEntity<?> importText(@PathVariable UUID p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object>b) {
        return ResponseEntity.status(201).body(write("POST/"+p+"/imports",key,b,p,()->service.importText(p,b)));
    }
    @GetMapping("/profiles/{p}/material-imports/{m}") Object getImport(@PathVariable UUID p,@PathVariable UUID m) {return service.getImport(p,m);}
    @PostMapping("/profiles/{p}/material-imports/{m}/confirm") Object confirm(@PathVariable UUID p,@PathVariable UUID m,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object>b) {
        return write("CONFIRM/"+p+"/"+m,key,b,p,()->service.confirm(p,m,b));
    }
    @GetMapping("/profiles/{p}/workspaces/{w}/resume") Object resume(@PathVariable UUID p,@PathVariable UUID w) {return service.resume(p,w);}
    @PostMapping("/profiles/{p}/workspaces/{w}/resume/initialize") Object initialize(@PathVariable UUID p,@PathVariable UUID w,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object>b) {
        return write("INITIALIZE/"+p+"/"+w,key,b,p,()->service.initialize(p,w,b));
    }
    @PutMapping("/profiles/{p}/workspaces/{w}/resume") Object save(@PathVariable UUID p,@PathVariable UUID w,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object>b) {
        return write("SAVE/"+p+"/"+w,key,b,p,()->service.save(p,w,b));
    }
}