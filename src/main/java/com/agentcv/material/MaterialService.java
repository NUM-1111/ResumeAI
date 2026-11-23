package com.agentcv.material;

import com.agentcv.api.ApiFailure;
import com.agentcv.document.*;
import com.agentcv.workspace.WorkspaceService;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Profile("m00")
public class MaterialService {
    private final JdbcTemplate db; private final WorkspaceService workspaces; private final ResumeParser parser; private final ResumeFiles files;
    public MaterialService(JdbcTemplate db,WorkspaceService workspaces,ResumeParser parser,ResumeFiles files){this.db=db;this.workspaces=workspaces;this.parser=parser;this.files=files;}
    public record Create(String name,String text,String base64,boolean keep_duplicate) {}
    public record Edit(String name,String text,Long expected_version) {}
    public record Reference(Long expected_scope_version) {}
    private ApiFailure missing(){return new ApiFailure(404,"NOT_FOUND","个人材料不可用。");}
    private String name(String value){if(value==null||value.isBlank()||value.strip().length()>200||value.chars().anyMatch(Character::isISOControl))throw new ApiFailure(400,"VALIDATION_ERROR","材料名称不能为空且最多 200 字符。");return value.strip();}
    private String text(String value){if(value==null||value.isBlank()||value.getBytes(StandardCharsets.UTF_8).length>262144||value.chars().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))throw new ApiFailure(400,"VALIDATION_ERROR","请填写有效文本，最多 256 KiB。");return value;}
    private String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    public Map<String,Object> get(UUID p,UUID m){
        workspaces.profile(p);var rows=db.queryForList("select * from material where id=? and profile_id=?",m,p);if(rows.isEmpty())throw missing();
        var result=rows.get(0);result.remove("original_hash");result.remove("file_id");
        result.put("references",db.queryForList("select workspace_id from workspace_material_reference where profile_id=? and material_id=?",UUID.class,p,m));
        return result;
    }
    public Map<String,Object> list(UUID p,String q,int offset){
        workspaces.profile(p);if(q.length()>200||offset<0)throw new ApiFailure(400,"VALIDATION_ERROR","搜索或分页参数无效。");
        var rows=db.queryForList("select id,name,row_version,text_version,created_at,content_updated_at,limitation,file_status from material where profile_id=? and position(lower(?) in lower(name))>0 order by content_updated_at desc,id desc limit 26 offset ?",p,q,offset);
        boolean more=rows.size()>25;return Map.of("items",rows.subList(0,Math.min(25,rows.size())),"has_more",more);
    }
    public Map<String,Object> create(UUID p,UUID key,Create input){
        workspaces.profile(p);String title=name(input.name);
        // Replay before parsing/staging: a committed upload no longer has a temporary token.
        if(db.queryForObject("select count(*) from operation where scope=? and request_key=?",Integer.class,"MATERIAL_CREATE/"+p,key)>0){
            var replay=workspaces.write("MATERIAL_CREATE/"+p,key,input,()->workspaces.profile(p),()->{throw new IllegalStateException("Missing replay");});
            UUID id=UUID.fromString(replay.get("id").toString());finish(p,id);return get(p,id);
        }
        if((input.text==null)==(input.base64==null))throw new ApiFailure(400,"VALIDATION_ERROR","请选择文件或粘贴文字其中一种。");
        byte[] bytes;String value,limitation="";ResumeFiles.Upload upload=null;
        if(input.base64!=null){
            if(input.base64.length()>349528)throw new ApiFailure(413,"FILE_TOO_LARGE","文件超过 256 KiB。");
            try{bytes=Base64.getDecoder().decode(input.base64);}catch(IllegalArgumentException e){throw new ApiFailure(400,"VALIDATION_ERROR","文件传输格式无效。");}
            // name doubles as original name on upload; rename later never changes it.
            var parsed=parser.parse(title,bytes);value=text(parsed.text());limitation=parsed.limitation();
            upload=files.stage(p,null,key,title,bytes);
        }else{value=text(input.text);bytes=value.getBytes(StandardCharsets.UTF_8);}
        String fingerprint=hash(bytes),content=value,limits=limitation;ResumeFiles.Upload original=upload;
        var result=workspaces.write("MATERIAL_CREATE/"+p,key,input,()->workspaces.profile(p),()->{
            db.queryForObject("select id from profile where id=? for update",UUID.class,p);
            if(!input.keep_duplicate&&db.queryForObject("select count(*) from material where profile_id=? and (name=? or original_hash=?)",Integer.class,p,title,fingerprint)>0)
                throw new ApiFailure(409,"DUPLICATE","已有同名或相同原始内容的材料，是否仍保留两份？");
            UUID id=UUID.randomUUID();
            db.update("insert into material(id,profile_id,name,text,original_name,file_id,file_status,limitation,original_hash) values (?,?,?,?,?,?,?,?,?)",id,p,title,content,original==null?null:original.name(),original==null?null:original.id(),original==null?null:"PENDING",limits,fingerprint);
            return Map.of("id",id.toString());
        });
        UUID id=UUID.fromString(result.get("id").toString());
        // Always repair the file belonging to the committed material, never a replay's orphan.
        try{finish(p,id);}catch(org.springframework.dao.DataAccessException ignored){
            return Map.of("id",id.toString(),"saved",true,"file_warning","材料已保存，原件状态暂未确认，请重新打开详情核对；不要重复上传。");
        }
        return get(p,id);
    }
    public Map<String,Object> edit(UUID p,UUID m,UUID key,Edit input){
        if(input.expected_version==null||input.expected_version<1||(input.name==null)==(input.text==null))throw new ApiFailure(400,"VALIDATION_ERROR","请单独修改名称或文本，并提供版本。");
        String title=input.name==null?null:name(input.name),value=input.text==null?null:text(input.text);
        return workspaces.write("MATERIAL_EDIT/"+p+"/"+m,key,input,()->get(p,m),()->{
            int updated=title!=null?db.update("update material set name=?,row_version=row_version+1 where id=? and profile_id=? and row_version=?",title,m,p,input.expected_version):
                db.update("update material set text=?,row_version=row_version+1,text_version=text_version+1,content_updated_at=CURRENT_TIMESTAMP where id=? and profile_id=? and row_version=?",value,m,p,input.expected_version);
            if(updated!=1)throw new ApiFailure(409,"VERSION_CONFLICT","材料已在别处更新。本页输入保留，请查看最新内容后核对。");
            return get(p,m);
        });
    }
    public Map<String,Object> scope(UUID p,UUID w){
        workspaces.workspace(p,w);
        var versions=db.queryForList("select version from workspace_scope where workspace_id=?",Long.class,w);
        return Map.of("version",versions.isEmpty()?0L:versions.get(0),"items",db.queryForList("select m.id,m.name,m.limitation,m.text_version from material m join workspace_material_reference r on m.id=r.material_id where r.workspace_id=? and r.profile_id=? order by m.name,m.id",w,p));
    }
    public Map<String,Object> reference(UUID p,UUID w,UUID m,UUID key,Reference input,boolean add){
        if(input.expected_scope_version==null)throw new ApiFailure(400,"VALIDATION_ERROR","缺少范围版本。");
        return workspaces.write("REFERENCE/"+add+"/"+p+"/"+w+"/"+m,key,input,()->{workspaces.workspace(p,w);get(p,m);},()->{
            db.queryForObject("select id from workspace where id=? and profile_id=? for update",UUID.class,w,p);
            long current=((Number)scope(p,w).get("version")).longValue();
            if(current!=input.expected_scope_version)throw new ApiFailure(409,"VERSION_CONFLICT","参考范围已变化，请刷新后重试。");
            int changed=add?db.update("insert into workspace_material_reference(workspace_id,profile_id,material_id) values (?,?,?) on conflict do nothing",w,p,m):db.update("delete from workspace_material_reference where workspace_id=? and material_id=?",w,m);
            if(changed>0)db.update("insert into workspace_scope(workspace_id,version) values (?,1) on conflict(workspace_id) do update set version=workspace_scope.version+1",w);
            return scope(p,w);
        });
    }
    public void finish(UUID p,UUID m){
        get(p,m);var ids=db.queryForList("select file_id from material where id=? and file_id is not null",UUID.class,m);
        if(!ids.isEmpty()){boolean ready=files.finish(ids.get(0));db.update("update material set file_status=? where id=?",ready?"READY":"PENDING",m);}
    }
    public WorkspaceService.Original original(UUID p,UUID m){
        var value=get(p,m);var ids=db.queryForList("select file_id from material where id=? and file_id is not null",UUID.class,m);
        if(ids.isEmpty())throw new ApiFailure(404,"NO_ORIGINAL","由粘贴文本创建，没有原件。");
        return new WorkspaceService.Original((String)value.get("original_name"),files.read(ids.get(0)));
    }
}