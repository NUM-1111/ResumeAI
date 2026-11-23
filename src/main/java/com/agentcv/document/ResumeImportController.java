package com.agentcv.document;

import com.agentcv.workspace.WorkspaceService;
import com.agentcv.api.ApiFailure;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("m00")
@RequestMapping("/api/v1/profiles/{p}/workspaces/{w}/resume")
class ResumeImportController {
    private final ResumeParser parser;private final ResumeFiles files;private final WorkspaceService service;
    ResumeImportController(ResumeParser parser,ResumeFiles files,WorkspaceService service){this.parser=parser;this.files=files;this.service=service;}
    record UploadInput(String name,String base64) {}
    @PostMapping("/upload-preview") Object upload(@PathVariable UUID p,@PathVariable UUID w,@RequestHeader("Idempotency-Key") UUID key,@RequestBody UploadInput body){
        service.workspace(p,w);
        if(body.base64==null||body.base64.length()>349528)throw new ApiFailure(413,"FILE_TOO_LARGE","文件超过 256 KiB 或内容无效。");
        byte[] bytes;try{bytes=Base64.getDecoder().decode(body.base64);}catch(IllegalArgumentException e){throw new ApiFailure(400,"VALIDATION_ERROR","文件传输格式无效，请重新选择。");}
        var parsed=parser.parse(body.name,bytes);
        var upload=files.stage(p,w,key,body.name,bytes);
        return Map.of("upload_id",upload.id(),"text",parsed.text(),"limitation",parsed.limitation(),"original_name",upload.name(),"expires_at",upload.expires().toString());
    }
    @PostMapping("/origin/retry") Object retry(@PathVariable UUID p,@PathVariable UUID w,@RequestHeader("Idempotency-Key") UUID key){return service.retryFile(p,w);}
    @GetMapping("/origin/file") ResponseEntity<byte[]> original(@PathVariable UUID p,@PathVariable UUID w){
        var file=service.original(p,w);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options","nosniff").body(file.bytes());
    }
}