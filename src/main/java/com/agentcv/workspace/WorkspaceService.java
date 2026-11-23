package com.agentcv.workspace;

import com.agentcv.api.ApiFailure;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.agentcv.document.ResumeFiles;

@Service
@Profile("m00")
public class WorkspaceService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ResumeFiles files;
    public WorkspaceService(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json, ResumeFiles files) {
        this.db = db; this.tx = tx; this.json = json; this.files=files;
    }
    private ApiFailure missing() { return new ApiFailure(404, "NOT_FOUND", "当前位置不可用，请返回上一级。"); }
    private ApiFailure conflict() { return new ApiFailure(409, "VERSION_CONFLICT", "内容已在其他页面更新，请核对最新内容；本页输入仍保留。"); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Cannot encode operation", error); }
    }
    private String fingerprint(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException("Cannot fingerprint operation", error); }
    }
    public Map<String,Object> write(String scope, UUID key, Object payload, Runnable access, Supplier<Map<String,Object>> action) {
        return tx.execute(status -> {
            // Serializes only this operation key, including concurrent first submission.
            db.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, scope + key);
            access.run(); // Replay must still verify ownership.
            String hash = fingerprint(payload);
            var previous = db.queryForList("select payload_hash,response_json from operation where scope=? and request_key=?", scope,key);
            if (!previous.isEmpty()) {
                if (!hash.equals(previous.get(0).get("payload_hash")))
                    throw new ApiFailure(409,"IDEMPOTENCY_KEY_REUSED","此操作凭证对应不同内容，请核对后重新操作。");
                try { return json.readValue((String)previous.get(0).get("response_json"), new TypeReference<Map<String,Object>>() {}); }
                catch (Exception error) { throw new IllegalStateException("Cannot decode operation", error); }
            }
            Map<String,Object> result = action.get();
            db.update("insert into operation(scope,request_key,payload_hash,response_json) values (?,?,?,?)",scope,key,hash,encode(result));
            return result;
        });
    }
    private String name(String value, int limit, String label) {
        if (value == null || value.isBlank() || value.strip().length() > limit || value.chars().anyMatch(c -> Character.isISOControl(c)))
            throw new ApiFailure(400,"VALIDATION_ERROR",label + "不能为空，且不能超过" + limit + "个字符。");
        return value.strip();
    }
    private String text(String value, boolean initial) {
        if (value == null || (initial && value.isBlank()) || value.getBytes(StandardCharsets.UTF_8).length > 262144
                || value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
            throw new ApiFailure(400,"VALIDATION_ERROR","请填写有效文字，UTF-8 内容最多 256 KiB。");
        return value;
    }
    public void profile(UUID p) {
        if (db.queryForObject("select count(*) from profile where id=?",Integer.class,p) != 1) throw missing();
    }
    public void workspace(UUID p, UUID w) {
        if (db.queryForObject("select count(*) from workspace where profile_id=? and id=?",Integer.class,p,w) != 1) throw missing();
    }
    private Map<String,Object> profileRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return Map.of("id",rs.getString("id"),"display_name",rs.getString("display_name"),"created_at",rs.getString("created_at"));
    }
    private Map<String,Object> workspaceRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String,Object> result = new HashMap<>();
        for (String field : List.of("id","profile_id","name","direction","created_at")) result.put(field,rs.getString(field));
        result.put("initialized",rs.getObject("revision") != null);
        result.put("last_edited_at",rs.getString("last_edited_at"));
        return result;
    }
    private static final String WORKSPACE_SELECT = "select w.*,c.revision,r.created_at as last_edited_at from workspace w left join workspace_resume c on c.workspace_id=w.id left join resume_revision r on r.workspace_id=c.workspace_id and r.revision=c.revision ";
    public Map<String,Object> profiles(UUID after, String q) {
        String query = q == null ? "" : q.strip();
        if (query.length() > 200) throw new ApiFailure(400,"VALIDATION_ERROR","搜索内容过长。");
        var rows = db.query("select * from profile where (?::uuid is null or id>?::uuid) and position(lower(?) in lower(display_name))>0 order by id limit 26",
                (rs,n)->profileRow(rs),after,after,query);
        return page(rows);
    }
    public Map<String,Object> workspaces(UUID p, UUID after, String q) {
        profile(p);
        String query = q == null ? "" : q.strip();
        if (query.length() > 200) throw new ApiFailure(400,"VALIDATION_ERROR","搜索内容过长。");
        return page(db.query(WORKSPACE_SELECT + "where w.profile_id=? and (?::uuid is null or w.id>?::uuid) and position(lower(?) in lower(w.name))>0 order by w.id limit 26",
                (rs,n)->workspaceRow(rs),p,after,after,query));
    }
    private Map<String,Object> page(List<Map<String,Object>> rows) {
        boolean more = rows.size() > 25;
        var items = rows.subList(0, Math.min(25,rows.size()));
        Map<String,Object> result = new HashMap<>();
        result.put("items",items); result.put("next_cursor",more ? items.get(items.size()-1).get("id") : null);
        return result;
    }
    public Map<String,Object> getWorkspace(UUID p, UUID w) {
        workspace(p,w);
        return db.queryForObject(WORKSPACE_SELECT + "where w.profile_id=? and w.id=?",(rs,n)->workspaceRow(rs),p,w);
    }
    public record CreateProfile(String display_name) {}
    public record CreateWorkspace(String name, String direction) {}
    public record ResumeInput(String text, Long expected_version) {}
    public Map<String,Object> createProfile(UUID key, CreateProfile input) {
        String value = name(input.display_name,100,"档案名称");
        return write("POST/profiles",key,input,()->{},()->{
            UUID id=UUID.randomUUID();
            db.update("insert into profile(id,display_name) values (?,?)",id,value);
            return db.queryForObject("select * from profile where id=?",(rs,n)->profileRow(rs),id);
        });
    }
    public Map<String,Object> createWorkspace(UUID p, UUID key, CreateWorkspace input) {
        String value=name(input.name,100,"工作区名称"), direction=name(input.direction,200,"岗位方向");
        return write("POST/"+p+"/workspaces",key,input,()->profile(p),()->{
            UUID id=UUID.randomUUID();
            db.update("insert into workspace(id,profile_id,name,direction) values (?,?,?,?)",id,p,value,direction);
            return getWorkspace(p,id);
        });
    }
    public Map<String,Object> resume(UUID p, UUID w) {
        workspace(p,w);
        var rows=db.queryForList("select c.revision,c.source_kind,r.text,r.created_at from workspace_resume c join resume_revision r on r.workspace_id=c.workspace_id and r.revision=c.revision where c.workspace_id=? and c.profile_id=?",w,p);
        if(rows.isEmpty()) return Map.of("initialized",false,"version",0);
        var row=rows.get(0);
        Map<String,Object> result=new HashMap<>(Map.of("initialized",true,"version",row.get("revision"),"text",row.get("text"),"source_kind",row.get("source_kind"),"last_edited_at",row.get("created_at").toString()));
        var origins=db.queryForList("select o.original_name,o.file_id,o.file_status,o.source_workspace_id,o.source_revision,w.name as source_workspace_name from resume_origin o left join workspace w on w.id=o.source_workspace_id where o.workspace_id=?",w);
        if(!origins.isEmpty())result.put("origin",origins.get(0));
        return result;
    }
    public record EstablishInput(String text,Long expected_version,UUID upload_id,UUID source_workspace_id,Long expected_source_version) {}
    public Map<String,Object> establish(UUID p,UUID w,UUID key,EstablishInput input){
        if(input.upload_id==null&&input.source_workspace_id==null)
            return save(p,w,key,new ResumeInput(input.text,input.expected_version),true);
        if(input.upload_id!=null&&input.source_workspace_id!=null)throw new ApiFailure(400,"VALIDATION_ERROR","建立方式只能选择一种。");
        if(input.expected_version==null||input.expected_version!=0)throw conflict();
        Map<String,Object> result=write("ESTABLISH/"+p+"/"+w,key,input,()->{
            workspace(p,w);if(input.source_workspace_id!=null)workspace(p,input.source_workspace_id);
        },()->{
            // Deterministic lock order prevents cross-copy deadlocks.
            List<UUID> ids=new ArrayList<>(List.of(w));if(input.source_workspace_id!=null)ids.add(input.source_workspace_id);
            ids.stream().distinct().sorted().forEach(id->db.queryForObject("select id from workspace where id=? and profile_id=? for update",UUID.class,id,p));
            if((Boolean)resume(p,w).get("initialized"))throw new ApiFailure(409,"ALREADY_INITIALIZED","目标工作区已建立简历，请重新打开。");
            String value,kind;
            if(input.upload_id!=null){
                var upload=files.require(input.upload_id,p,w);value=text(input.text,true);kind="UPLOAD";
                db.update("insert into resume_origin(workspace_id,file_id,original_name,file_status,file_hash,byte_size) values (?,?,?,'PENDING',?,?)",w,upload.id(),upload.name(),upload.hash(),upload.size());
            }else{
                if(w.equals(input.source_workspace_id))throw new ApiFailure(400,"VALIDATION_ERROR","不能复制到来源工作区自身。");
                var source=resume(p,input.source_workspace_id);
                if(!(Boolean)source.get("initialized"))throw new ApiFailure(409,"SOURCE_UNAVAILABLE","来源尚未建立简历，请选择其他工作区。");
                if(input.expected_source_version==null||input.expected_source_version.longValue()!=((Number)source.get("version")).longValue())
                    throw new ApiFailure(409,"SOURCE_CHANGED","来源简历已变化，请刷新预览后再确认复制。");
                value=(String)source.get("text");kind="COPY";
                db.update("insert into resume_origin(workspace_id,source_workspace_id,source_revision) values (?,?,?)",w,input.source_workspace_id,input.expected_source_version);
            }
            db.update("insert into resume_revision(workspace_id,profile_id,revision,text) values (?,?,1,?)",w,p,value);
            db.update("insert into workspace_resume(workspace_id,profile_id,revision,source_kind) values (?,?,1,?)",w,p,kind);
            return resume(p,w);
        });
        // File movement is outside the business transaction. Replay only retries this stage.
        if(input.upload_id!=null){
            try{
                boolean ready=files.finish(input.upload_id);
                db.update("update resume_origin set file_status=? where workspace_id=? and file_id=?",ready?"READY":"PENDING",w,input.upload_id);
                result=resume(p,w);
            }catch(org.springframework.dao.DataAccessException ignored){
                result=new HashMap<>(result);result.put("file_warning","简历文本已保存，来源文件状态未确认。请重试来源保存或复制页面文字。");
            }
        }
        return result;
    }
    public Map<String,Object> retryFile(UUID p,UUID w){
        workspace(p,w);var origin=db.queryForList("select file_id from resume_origin where workspace_id=? and file_id is not null",w);
        if(origin.isEmpty())throw missing();UUID id=(UUID)origin.get(0).get("file_id");
        boolean ready=files.finish(id);db.update("update resume_origin set file_status=? where workspace_id=?",ready?"READY":"PENDING",w);
        return resume(p,w);
    }
    public record Original(String name,byte[] bytes) {}
    public Original original(UUID p,UUID w){
        workspace(p,w);var origin=db.queryForList("select file_id,original_name from resume_origin where workspace_id=? and file_id is not null",w);
        if(origin.isEmpty())throw missing();var row=origin.get(0);return new Original((String)row.get("original_name"),files.read((UUID)row.get("file_id")));
    }
    public Map<String,Object> save(UUID p, UUID w, UUID key, ResumeInput input, boolean initialize) {
        String value=text(input.text,initialize);
        if (input.expected_version == null || input.expected_version < 0)
            throw new ApiFailure(400,"VALIDATION_ERROR","缺少有效版本条件。");
        return write((initialize?"INIT/":"SAVE/")+p+"/"+w,key,input,()->workspace(p,w),()->{
            // Lock the workspace row, including the absence of an initial resume.
            db.queryForObject("select id from workspace where id=? and profile_id=? for update",UUID.class,w,p);
            var current=resume(p,w);
            long version=((Number)current.get("version")).longValue();
            if(input.expected_version != version) throw conflict();
            if(initialize && version != 0) throw new ApiFailure(409,"ALREADY_INITIALIZED","此工作区已建立简历，请重新打开。");
            if(!initialize && version == 0) throw missing();
            if(!initialize && value.equals(current.get("text"))) return current;
            long next=version+1;
            db.update("insert into resume_revision(workspace_id,profile_id,revision,text) values (?,?,?,?)",w,p,next,value);
            if(initialize) db.update("insert into workspace_resume(workspace_id,profile_id,revision,source_kind) values (?,?,?,'PASTE')",w,p,next);
            else db.update("update workspace_resume set revision=? where workspace_id=? and revision=?",next,w,version);
            return resume(p,w);
        });
    }
}