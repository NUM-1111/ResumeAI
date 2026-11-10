package com.agentcv;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class RecycleController {
    private final WorkspaceService writes;
    private final RecycleService recycle;
    public RecycleController(WorkspaceService writes,RecycleService recycle){this.writes=writes;this.recycle=recycle;}
    @GetMapping("/profiles/{p}/deletion-preview") Object preview(@PathVariable UUID p,@RequestParam String kind,@RequestParam UUID id){return recycle.preview(p,kind,id);}
    @PostMapping("/profiles/{p}/recycle-operations") Object delete(@PathVariable UUID p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object>b){
        return writes.write("RECYCLE/"+p,WorkspaceService.id(key),b,null,()->recycle.recycle(p,b));
    }
    @GetMapping("/recycle-entries") Object entries(@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)UUID after){return recycle.entries(limit,after);}
    @PostMapping("/profiles/{p}/recycle-entries/{entry}/restore") Object restore(@PathVariable UUID p,@PathVariable UUID entry,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object>b){
        return writes.write("RESTORE/"+p+"/"+entry,WorkspaceService.id(key),b,null,()->recycle.restore(p,entry,b));
    }
}