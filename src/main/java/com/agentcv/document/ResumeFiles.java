package com.agentcv.document;

import com.agentcv.api.ApiFailure;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

@Component
@org.springframework.context.annotation.Profile("m00")
public class ResumeFiles {
    public record Upload(UUID id,UUID profile,UUID workspace,String name,String hash,long size,Instant expires) {}
    private final Map<UUID,Upload> pending=new HashMap<>();
    private final Set<UUID> committed=new HashSet<>();
    private final Map<UUID,UUID> previewKeys=new HashMap<>();
    private final Path root;
    private final JdbcTemplate db;
    public ResumeFiles(@Value("${agentcv.files.root:.local/m00/resume-files}") String root,JdbcTemplate db){this.root=Path.of(root).toAbsolutePath().normalize();this.db=db;}
    private Path path(UUID id,boolean temporary){return root.resolve((temporary?"tmp-":"file-")+id);}
    public synchronized Upload stage(UUID p,UUID w,String name,byte[] data){
        cleanup();UUID id=UUID.randomUUID();
        try{
            Files.createDirectories(root);Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
            Files.createFile(path(id,true),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(path(id,true),data);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            Upload upload=new Upload(id,p,w,name,hash,data.length,Instant.now().plus(Duration.ofHours(1)));
            pending.put(id,upload);return upload;
        }catch(Exception e){throw new ApiFailure(503,"FILE_WRITE_FAILED","来源文件暂时无法保存，请保留文字后重试。");}
    }
    public synchronized Upload stage(UUID p,UUID w,UUID key,String name,byte[] data){
        UUID prior=previewKeys.get(key);
        if(prior!=null){
            Upload old=require(prior,p,w);
            try{
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
                if(!old.name.equals(name)||!old.hash.equals(hash))throw new ApiFailure(409,"IDEMPOTENCY_KEY_REUSED","同一上传凭证对应不同文件，请重新操作。");
            }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
            return old;
        }
        Upload result=stage(p,w,name,data);previewKeys.put(key,result.id);return result;
    }
    public synchronized Upload require(UUID id,UUID p,UUID w){
        Upload value=pending.get(id);
        if(value==null||!value.profile.equals(p)||!Objects.equals(value.workspace,w)||Instant.now().isAfter(value.expires))
            throw new ApiFailure(409,"UPLOAD_EXPIRED","上传预览已失效，请重新上传；页面文字仍保留。");
        return value;
    }
    public synchronized boolean finish(UUID id){
        committed.add(id);
        try{
            if(!Files.isRegularFile(path(id,false)))Files.move(path(id,true),path(id,false),StandardCopyOption.ATOMIC_MOVE);
            pending.remove(id);return true;
        }catch(IOException e){return false;}
    }
    public byte[] read(UUID id){
        try{return Files.readAllBytes(path(id,false));}
        catch(IOException e){throw new ApiFailure(404,"FILE_NOT_READY","来源文件不可用；已保存的简历仍可继续编辑。");}
    }
    public synchronized void cleanup(){
        Instant now=Instant.now();
        // Fail closed if the database cannot establish which temporary files are referenced.
        Set<UUID> referenced=new HashSet<>(db.queryForList("select file_id from resume_origin where file_id is not null",UUID.class));
        // During V3-only operation this table does not exist yet.
        if(Boolean.TRUE.equals(db.queryForObject("select to_regclass('material') is not null",Boolean.class)))
            referenced.addAll(db.queryForList("select file_id from material where file_id is not null",UUID.class));
        committed.addAll(referenced);
        pending.values().removeIf(value->{if(committed.contains(value.id)||now.isBefore(value.expires))return false;try{Files.deleteIfExists(path(value.id,true));return true;}catch(IOException e){return false;}});
        // Only temporary files, never delete a formal file or an active in-memory upload.
        if(Files.isDirectory(root))try(var entries=Files.list(root)){
            entries.filter(p->p.getFileName().toString().startsWith("tmp-")).forEach(p->{
                try{UUID id=UUID.fromString(p.getFileName().toString().substring(4));if(!committed.contains(id)&&!pending.containsKey(id)&&Files.getLastModifiedTime(p).toInstant().isBefore(now.minus(Duration.ofHours(1))))Files.deleteIfExists(p);}
                catch(IllegalArgumentException|IOException ignored){}
            });
        }catch(IOException ignored){}
        previewKeys.values().removeIf(id->!pending.containsKey(id));
        if(Files.isDirectory(root))try(var entries=Files.list(root)){
            entries.filter(p->p.getFileName().toString().startsWith("file-")).forEach(p->{
                try{
                    UUID id=UUID.fromString(p.getFileName().toString().substring(5));
                    if(!referenced.contains(id)&&!pending.containsKey(id)&&Files.getLastModifiedTime(p).toInstant().isBefore(now.minus(Duration.ofDays(1))))Files.deleteIfExists(p);
                }catch(IllegalArgumentException|IOException ignored){}
            });
        }catch(IOException ignored){}
    }
}