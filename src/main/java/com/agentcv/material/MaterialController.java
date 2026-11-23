package com.agentcv.material;

import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("m00")
@RequestMapping("/api/v1/profiles/{p}")
class MaterialController {
    private final MaterialService service;
    MaterialController(MaterialService service){this.service=service;}
    @GetMapping("/materials") Object list(@PathVariable UUID p,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int offset){return service.list(p,q,offset);}
    @PostMapping("/materials") ResponseEntity<?> create(@PathVariable UUID p,@RequestHeader("Idempotency-Key") UUID key,@RequestBody MaterialService.Create body){return ResponseEntity.status(201).body(service.create(p,key,body));}
    @GetMapping("/materials/{m}") Object get(@PathVariable UUID p,@PathVariable UUID m){return service.get(p,m);}
    @PatchMapping("/materials/{m}") Object edit(@PathVariable UUID p,@PathVariable UUID m,@RequestHeader("Idempotency-Key") UUID key,@RequestBody MaterialService.Edit body){return service.edit(p,m,key,body);}
    @PostMapping("/materials/{m}/file/retry") Object retry(@PathVariable UUID p,@PathVariable UUID m,@RequestHeader("Idempotency-Key") UUID key){service.finish(p,m);return service.get(p,m);}
    @GetMapping("/materials/{m}/file") ResponseEntity<byte[]> file(@PathVariable UUID p,@PathVariable UUID m){var original=service.original(p,m);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(original.name(),StandardCharsets.UTF_8).build().toString()).header("X-Content-Type-Options","nosniff").body(original.bytes());}
    @GetMapping("/workspaces/{w}/references") Object scope(@PathVariable UUID p,@PathVariable UUID w){return service.scope(p,w);}
    @PutMapping("/workspaces/{w}/references/{m}") Object add(@PathVariable UUID p,@PathVariable UUID w,@PathVariable UUID m,@RequestHeader("Idempotency-Key") UUID key,@RequestBody MaterialService.Reference body){return service.reference(p,w,m,key,body,true);}
    @DeleteMapping("/workspaces/{w}/references/{m}") Object remove(@PathVariable UUID p,@PathVariable UUID w,@PathVariable UUID m,@RequestHeader("Idempotency-Key") UUID key,@RequestBody MaterialService.Reference body){return service.reference(p,w,m,key,body,false);}
}