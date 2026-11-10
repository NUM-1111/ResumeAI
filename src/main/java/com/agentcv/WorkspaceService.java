package com.agentcv;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class WorkspaceService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Path materials;
    public WorkspaceService(JdbcTemplate db, PlatformTransactionManager manager, ObjectMapper json,
            @Value("${agentcv.material-directory}") String directory) {
        this.db=db; this.tx=new TransactionTemplate(manager); this.json=json; this.materials=Path.of(directory);
    }
    public static class Failure extends RuntimeException {
        final int status; final String code;
        Failure(int status,String code,String message) { super(message);this.status=status;this.code=code; }
    }
    static Failure bad(String message) { return new Failure(400,"INVALID_INPUT",message); }
    static Failure conflict(String code,String message) { return new Failure(409,code,message); }
    static String string(Map<String,Object> body,String key,int max) {
        Object value=body.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.length()>max) throw bad("字段无效："+key);
        return text;
    }
    static int number(Map<String,Object> body,String key) {
        Object value=body.get(key);
        if (!(value instanceof Number n) || n.longValue()<0 || n.longValue()>Integer.MAX_VALUE || n.doubleValue()!=n.longValue()) throw bad("版本或序号无效："+key);
        return n.intValue();
    }
    static UUID id(Object value) {
        try { return UUID.fromString(String.valueOf(value)); } catch(Exception e) { throw bad("对象ID无效"); }
    }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    static String text(String text) {
        if(text==null || text.isBlank()) throw bad("文本不能为空");
        if(text.getBytes(StandardCharsets.UTF_8).length>262144) throw new Failure(413,"TEXT_TOO_LARGE","文本超过256 KiB，请缩小内容");
        if(text.chars().anyMatch(c->(c<32 && c!=9 && c!=10 && c!=13) || c==127)) throw new Failure(422,"INVALID_TEXT","文本含不支持的控制字符，请检查编码");
        return text;
    }
    Map<String,Object> one(String sql,Object...args) {
        var rows=db.queryForList(sql,args);
        if(rows.isEmpty()) throw new Failure(404,"NOT_FOUND","对象在当前档案中不可用");
        return rows.get(0);
    }
    void profile(UUID p) { one("select id from profile where id=? and recycle_batch is null for update",p); }
    Map<String,Object> workspace(UUID p,UUID w) { return one("select w.id,w.profile_id,w.name,w.direction,w.row_version from workspace w join profile p on p.id=w.profile_id where w.id=? and w.profile_id=? and w.recycle_batch is null and p.recycle_batch is null",w,p); }
    public Map<String,Object> write(String scope,UUID key,Map<String,Object> body,UUID p,Supplier<Map<String,Object>> action) {
        return tx.execute(status->{
            // Serialize retries even for profile creation, without holding locks during remote calls.
            db.queryForObject("select pg_advisory_xact_lock(hashtext(?))",Object.class,scope+key);
            String fingerprint;
            try { fingerprint=hash(json.writeValueAsBytes(new TreeMap<>(body))); } catch(Exception e) { throw bad("请求内容无效"); }
            var prior=db.queryForList("select payload_hash,response_json from operation where scope=? and request_key=?",scope,key);
            if(p!=null) profile(p);
            if(!prior.isEmpty()) {
                if(!fingerprint.equals(prior.get(0).get("payload_hash"))) throw conflict("IDEMPOTENCY_CONFLICT","相同请求键不能用于不同内容");
                try { return json.readValue((String)prior.get(0).get("response_json"),new TypeReference<Map<String,Object>>(){}); }
                catch(Exception e) { throw new IllegalStateException(e); }
            }
            Map<String,Object> result=action.get();
            try { db.update("insert into operation(scope,request_key,payload_hash,response_json) values(?,?,?,?)",scope,key,fingerprint,json.writeValueAsString(result)); }
            catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
            return result;
        });
    }
    public Map<String,Object> list(UUID p,String kind,int limit,UUID after) {
        return list(p,kind,limit,after,"");
    }
    public Map<String,Object> list(UUID p,String kind,int limit,UUID after,String query) {
        if(limit<1 || limit>100) throw bad("分页大小须为1至100");
        String table=switch(kind) { case "profiles"->"profile";case "workspaces"->"workspace";case "material-imports"->"material_import";default->throw bad("列表类型无效"); };
        String fields=switch(kind) { case "profiles"->"id,display_name as \"displayName\"";case "workspaces"->"id,name,direction,row_version as \"rowVersion\"";default->"id,name,check_state as \"checkState\",row_version as \"rowVersion\""; };
        List<Object> params=new ArrayList<>(); String where=" where recycle_batch is null";
        if(p!=null) { one("select id from profile where id=? and recycle_batch is null",p);where+=" and profile_id=?";params.add(p); }
        String label=kind.equals("profiles")?"display_name":"name";
        if(query!=null && !query.isBlank()) {
            if(query.length()>200) throw bad("搜索词不能超过200字符");
            where+=" and position(lower(?) in lower("+label+"))>0";params.add(query.strip());
        }
        if(after!=null) {
            where+=" and ("+label+",id)>(select "+label+",id from "+table+" where id=?"+(p!=null?" and profile_id=?":"")+")";
            params.add(after);if(p!=null) params.add(p);
        }
        params.add(limit+1);
        var rows=db.queryForList("select "+fields+" from "+table+where+" order by "+label+",id limit ?",params.toArray());
        boolean more=rows.size()>limit; if(more) rows.remove(rows.size()-1);
        Map<String,Object> result=new LinkedHashMap<>();result.put("items",rows);
        result.put("nextCursor",more?rows.get(rows.size()-1).get("id"):null);return result;
    }
    public Map<String,Object> createProfile(Map<String,Object> b) {
        UUID id=UUID.randomUUID();String name=string(b,"displayName",100).strip();
        db.update("insert into profile(id,display_name) values(?,?)",id,name);return Map.of("id",id,"displayName",name);
    }
    public Map<String,Object> createWorkspace(UUID p,Map<String,Object> b) {
        UUID id=UUID.randomUUID();String name=string(b,"name",100).strip(),direction=string(b,"direction",200).strip();
        db.update("insert into workspace(id,profile_id,name,direction) values(?,?,?,?)",id,p,name,direction);
        return Map.of("id",id,"profileId",p,"name",name,"direction",direction,"rowVersion",1);
    }
    public Map<String,Object> changeWorkspace(UUID p,UUID w,Map<String,Object>b) {
        workspace(p,w);String name=string(b,"name",100),direction=string(b,"direction",200);int version=number(b,"expectedVersion");
        if(db.update("update workspace set name=?,direction=?,row_version=row_version+1,target_version=target_version+1 where id=? and profile_id=? and row_version=?",name,direction,w,p,version)!=1) throw conflict("VERSION_CONFLICT","工作区已变化，请重新打开");
        return Map.of("id",w,"name",name,"direction",direction,"rowVersion",version+1);
    }
    public Map<String,Object> getImport(UUID p,UUID m) {
        one("select id from profile where id=? and recycle_batch is null",p);
        return one("select id,name,extracted_text as \"extractedText\",confirmed_text as \"confirmedText\",check_state as \"checkState\",row_version as \"rowVersion\" from material_import where id=? and profile_id=? and recycle_batch is null",m,p);
    }
    public Map<String,Object> importText(UUID p,Map<String,Object>b) {
        String name=string(b,"name",200);byte[] bytes;String content;
        if(b.containsKey("text")==b.containsKey("base64")) throw bad("文本与文件必须二选一");
        if(b.containsKey("base64")) {
            if(!name.toLowerCase(Locale.ROOT).endsWith(".txt")) throw new Failure(422,"UNSUPPORTED_FORMAT","本切片只支持.txt，请粘贴文本");
            try { bytes=Base64.getDecoder().decode(string(b,"base64",350000)); }
            catch(IllegalArgumentException e) { throw bad("文件编码无效"); }
            if(bytes.length>262144) throw new Failure(413,"TEXT_TOO_LARGE","文件超过256 KiB");
            try {content=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
            catch(CharacterCodingException e) {throw new Failure(422,"INVALID_ENCODING","文件不是有效UTF-8，请转为UTF-8或粘贴文本");}
            if(content.startsWith("\uFEFF")) content=content.substring(1);
        } else {content=string(b,"text",262144);bytes=content.getBytes(StandardCharsets.UTF_8);}
        text(content);String digest=hash(bytes);
        boolean duplicate=!db.queryForList("select id from material_import where profile_id=? and recycle_batch is null and (name=? or original_hash=?) limit 1",p,name,digest).isEmpty();
        if(duplicate && !Boolean.TRUE.equals(b.get("keepDuplicate"))) throw conflict("DUPLICATE","同名或相同材料已存在。可明确保留两份或取消；本切片不覆盖旧材料");
        UUID id=UUID.randomUUID();
        try {
            Files.createDirectories(materials,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path file=materials.resolve(id.toString());
            Files.createFile(file,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(file,bytes);
        } catch(java.io.IOException e) {throw new Failure(503,"FILE_WRITE_FAILED","原件保存失败，未完成导入，请重试或复制文本");}
        db.update("insert into material_import(id,profile_id,name,original_hash,storage_key,extracted_text) values(?,?,?,?,?,?)",id,p,name,digest,id,content);
        return getImport(p,id);
    }
    public Map<String,Object> confirm(UUID p,UUID m,Map<String,Object>b) {
        var prior=getImport(p,m);String content=text(string(b,"text",262144));
        String name=b.containsKey("name")?string(b,"name",200).strip():(String)prior.get("name");
        if(db.update("update material_import set name=?,confirmed_text=?,check_state='CONFIRMED',row_version=row_version+1 where id=? and profile_id=? and row_version=? and check_state='UNCHECKED'",name,content,m,p,number(b,"expectedVersion"))!=1) throw conflict("VERSION_CONFLICT","检查状态已变化，请重新打开材料");
        return getImport(p,m);
    }
    public Map<String,Object> rename(UUID p,UUID m,Map<String,Object>b) {
        getImport(p,m);String name=string(b,"name",200).strip();
        if(db.update("update material_import set name=?,row_version=row_version+1 where id=? and profile_id=? and row_version=?",name,m,p,number(b,"expectedVersion"))!=1)
            throw conflict("VERSION_CONFLICT","材料已变化，名称未覆盖。请重新打开材料后重试");
        return getImport(p,m);
    }
    public Map<String,Object> replace(UUID p,UUID w,Map<String,Object>b) {
        var current=resume(p,w);
        if(!Boolean.TRUE.equals(current.get("initialized"))) throw conflict("NOT_INITIALIZED","尚无当前稿，请先建立当前稿");
        int version=number(b,"expectedRevision");
        if(((Number)current.get("revision")).intValue()!=version) throw conflict("VERSION_CONFLICT","当前简历已变化，未替换。请重新预览");
        UUID source=id(b.get("sourceId"));var material=getImport(p,source);
        if(!"CONFIRMED".equals(material.get("checkState"))) throw conflict("UNCHECKED","须先检查确认文本");
        int sourceVersion=number(b,"expectedSourceRevision");
        if(((Number)material.get("rowVersion")).intValue()!=sourceVersion) throw conflict("VERSION_CONFLICT","来源材料已变化，未替换。请重新预览");
        db.update("insert into resume_revision(workspace_id,profile_id,revision,text,reason,source_kind,source_id,source_revision) values(?,?,?,?,'REPLACE','MATERIAL_REVISION',?,?)",w,p,version+1,material.get("confirmedText"),source,sourceVersion);
        db.update("update current_resume set revision=?,client_edit_sequence=0 where workspace_id=? and profile_id=?",version+1,w,p);
        db.update("delete from resume_source_binding where workspace_id=? and profile_id=?",w,p);
        db.update("insert into resume_source_binding(workspace_id,profile_id,material_id) values(?,?,?)",w,p,source);
        return resume(p,w);
    }
    public Map<String,Object> resume(UUID p,UUID w) {
        workspace(p,w);
        var rows=db.queryForList("select r.text,r.revision,r.source_kind as \"sourceKind\",c.client_edit_sequence as \"clientEditSequence\" from current_resume c join resume_revision r using(workspace_id,profile_id,revision) where c.workspace_id=? and c.profile_id=?",w,p);
        if(rows.isEmpty()) return Map.of("initialized",false);
        Map<String,Object> result=new LinkedHashMap<>(rows.get(0));result.put("initialized",true);return result;
    }
    public Map<String,Object> initialize(UUID p,UUID w,Map<String,Object>b) {
        if(Boolean.TRUE.equals(resume(p,w).get("initialized"))) throw conflict("ALREADY_INITIALIZED","已有当前稿，不能覆盖；请另建工作区");
        String type=string(b,"sourceType",30);UUID source=id(b.get("sourceId"));int expected=number(b,"expectedSourceRevision");String content;
        if(type.equals("WORKSPACE_CURRENT")) {
            var r=resume(p,source);if(!Boolean.TRUE.equals(r.get("initialized"))) throw conflict("NO_SOURCE","来源区尚无当前稿");
            if(((Number)r.get("revision")).intValue()!=expected) throw conflict("VERSION_CONFLICT","来源稿已变化");content=(String)r.get("text");
        } else if(type.equals("MATERIAL_REVISION")||type.equals("IMPORTED_REVISION")) {
            var m=getImport(p,source);if(!m.get("checkState").equals("CONFIRMED")) throw conflict("UNCHECKED","须先检查确认文本");
            if(((Number)m.get("rowVersion")).intValue()!=expected) throw conflict("VERSION_CONFLICT","来源材料已变化");content=(String)m.get("confirmedText");
        } else throw bad("不支持的来源类型");
        db.update("insert into resume_revision(workspace_id,profile_id,revision,text,reason,source_kind,source_id,source_revision) values(?,?,1,?,'INITIALIZE',?,?,?)",w,p,content,type,source,expected);
        db.update("insert into current_resume(workspace_id,profile_id,revision) values(?,?,1)",w,p);
        if(type.equals("WORKSPACE_CURRENT")) db.update("insert into resume_source_binding(workspace_id,profile_id,material_id) select ?,profile_id,material_id from resume_source_binding where workspace_id=? and profile_id=?",w,source,p);
        else db.update("insert into resume_source_binding(workspace_id,profile_id,material_id) values(?,?,?)",w,p,source);
        return resume(p,w);
    }
    public Map<String,Object> save(UUID p,UUID w,Map<String,Object>b) {
        var current=resume(p,w);if(!Boolean.TRUE.equals(current.get("initialized"))) throw conflict("NOT_INITIALIZED","请先确认并选择起始稿");
        int version=number(b,"expectedRevision"),seq=number(b,"clientEditSequence");String content=text(string(b,"text",262144));
        if(((Number)current.get("revision")).intValue()!=version) throw conflict("VERSION_CONFLICT","当前稿已有新版本，未覆盖。请复制页面文本后重新打开比较");
        int next=version;
        if(!content.equals(current.get("text"))) {
            next++;
            db.update("insert into resume_revision(workspace_id,profile_id,revision,text,reason,source_kind,source_id,source_revision) select workspace_id,profile_id,?,?,'EDIT',source_kind,source_id,source_revision from resume_revision where workspace_id=? and profile_id=? and revision=?",next,content,w,p,version);
        }
        db.update("update current_resume set revision=?,client_edit_sequence=? where workspace_id=? and profile_id=? and revision=?",next,seq,w,p,version);
        return resume(p,w);
    }
}