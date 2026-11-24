package com.agentcv.background;

import com.agentcv.api.ApiFailure;
import com.agentcv.workspace.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Profile("m00")
public class BackgroundChangeService {
    private final JdbcTemplate db;private final WorkspaceService workspaces;private final ObjectMapper json;
    public BackgroundChangeService(JdbcTemplate db,WorkspaceService workspaces,ObjectMapper json){this.db=db;this.workspaces=workspaces;this.json=json;}
    public record Prepare(UUID workspace_id,Long expected_version,String old_text,String new_text,String kind) {}
    public record Confirm(UUID workspace_id,Long expected_version,String old_text,String new_text,String kind) {}
    public Map<String,Object> prepare(UUID p,UUID b,UUID key,Prepare input){
        validate(input);
        return workspaces.write("BACKGROUND_CHANGE/"+p+"/"+b,key,input,()->workspaces.workspace(p,input.workspace_id),()->{
            lock(p,input.workspace_id);var target=target(p,b);
            if(!pending(p,input.workspace_id).isEmpty())
                throw new ApiFailure(409,"VERSION_CONFLICT","此工作区已有待确认背景变更，请先确认或取消，未覆盖原提议。");
            if(!Boolean.TRUE.equals(target.get("active"))||input.expected_version.longValue()!=((Number)target.get("version")).longValue()||!input.old_text.equals(target.get("text")))throw stale();
            return create(p,b,input,null);
        });
    }
    private void validate(Prepare input){
        if(input.workspace_id==null||input.expected_version==null||input.old_text==null||!Set.of("CORRECT","WITHDRAW").contains(input.kind==null?"":input.kind))throw invalid();
        if("CORRECT".equals(input.kind)&&(input.new_text==null||input.new_text.isBlank()||input.new_text.length()>1000||input.new_text.equals(input.old_text)))throw invalid();
        if("WITHDRAW".equals(input.kind)&&input.new_text!=null)throw invalid();
    }
    private Map<String,Object> create(UUID p,UUID b,Prepare input,UUID source){
        db.update("update background_change set status='EXPIRED' where workspace_id=? and status='PENDING'",input.workspace_id);
        UUID id=UUID.randomUUID();Long epoch=db.queryForObject("select coalesce((select epoch from conversation_state where workspace_id=?),0)",Long.class,input.workspace_id);
        db.update("insert into background_change(id,profile_id,workspace_id,background_id,expected_version,old_text,new_text,kind,context_epoch,source_request_id) values (?,?,?,?,?,?,?,?,?,?)",id,p,input.workspace_id,b,input.expected_version,input.old_text,input.new_text,input.kind,epoch,source);
        return get(p,id);
    }
    public Map<String,Object> get(UUID p,UUID c){
        workspaces.profile(p);var rows=db.queryForList("select * from background_change where id=? and profile_id=?",c,p);
        if(rows.isEmpty())throw new ApiFailure(404,"NOT_FOUND","背景操作不可用。");return rows.get(0);
    }
    public Map<String,Object> pending(UUID p,UUID w){
        workspaces.workspace(p,w);var rows=db.queryForList("select c.* from background_change c left join conversation_state s on s.workspace_id=c.workspace_id where c.profile_id=? and c.workspace_id=? and c.status='PENDING' and c.context_epoch=coalesce(s.epoch,0) order by c.created_at desc limit 1",p,w);
        if(rows.isEmpty())return Map.of();var proposal=rows.get(0);var current=target(p,(UUID)proposal.get("background_id"));
        if(!Boolean.TRUE.equals(current.get("active"))||!Objects.equals(current.get("version"),proposal.get("expected_version")))return Map.of();
        return proposal;
    }
    public Map<String,Object> confirm(UUID p,UUID c,UUID key,Confirm input){
        if(input.workspace_id==null)throw invalid();
        return workspaces.write("BACKGROUND_APPLY/"+p+"/"+c,key,input,()->workspaces.workspace(p,input.workspace_id),()->{
            lock(p,input.workspace_id);var change=get(p,c);
            if(!input.workspace_id.equals(change.get("workspace_id"))||input.expected_version==null||input.expected_version.longValue()!=((Number)change.get("expected_version")).longValue()||!Objects.equals(input.old_text,change.get("old_text"))||!Objects.equals(input.new_text,change.get("new_text"))||!Objects.equals(input.kind,change.get("kind")))throw stale();
            var target=target(p,(UUID)change.get("background_id"));
            if("APPLIED".equals(change.get("status")))return Map.of("applied",true,"kind",change.get("kind"));
            if(!"PENDING".equals(change.get("status"))||!c.equals(pending(p,input.workspace_id).get("id"))||!Boolean.TRUE.equals(target.get("active"))||((Number)target.get("version")).longValue()!=input.expected_version||!target.get("text").equals(input.old_text))throw stale();
            db.update("update background set text=?,active=?,version=version+1 where id=? and profile_id=?", "CORRECT".equals(input.kind)?input.new_text:input.old_text,"CORRECT".equals(input.kind),change.get("background_id"),p);
            db.update("update background_change set status='APPLIED' where id=?",c);
            return Map.of("applied",true,"kind",input.kind);
        });
    }
    public Map<String,Object> dismiss(UUID p,UUID c,UUID key,UUID w){
        return workspaces.write("BACKGROUND_DISMISS/"+p+"/"+c,key,Map.of("workspace_id",w),()->workspaces.workspace(p,w),()->{
            lock(p,w);var change=get(p,c);if(!w.equals(change.get("workspace_id")))throw stale();
            db.update("update background_change set status='EXPIRED' where id=? and status='PENDING'",c);return Map.of("dismissed",true);
        });
    }
    public void stage(UUID p,UUID w,UUID request,BackgroundService.Action action){
        if(action==null)return;
        try{
            var basis=json.readTree(db.queryForObject("select basis_json from ai_request where id=?",String.class,request));
            var version=basis.path("background_versions").path(action.id().toString());
            if(!version.isIntegralNumber()||version.asLong()!=action.version())return;
            var input=new Prepare(w,action.version(),action.old_text(),action.new_text(),action.kind());validate(input);
            var target=target(p,action.id());
            if(!Boolean.TRUE.equals(target.get("active"))||!Objects.equals(target.get("text"),action.old_text())||((Number)target.get("version")).longValue()!=action.version())return;
            create(p,action.id(),input,request);
        }catch(ApiFailure|com.fasterxml.jackson.core.JsonProcessingException ignored){/* Invalid model proposal has no write authority. */}
    }
    public List<Map<String,Object>> events(UUID p){return db.queryForList("select background_id,kind,old_text,new_text from background_change where profile_id=? and status='APPLIED' order by created_at,id",p);}
    private Map<String,Object> target(UUID p,UUID b){var rows=db.queryForList("select * from background where id=? and profile_id=?",b,p);if(rows.isEmpty())throw new ApiFailure(404,"NOT_FOUND","背景不可用。");return rows.get(0);}
    private void lock(UUID p,UUID w){db.queryForObject("select id from workspace where id=? and profile_id=? for update",UUID.class,w,p);db.queryForObject("select id from profile where id=? for update",UUID.class,p);}
    private ApiFailure stale(){return new ApiFailure(409,"VERSION_CONFLICT","背景或待确认内容已变化，未执行旧操作；请保留输入并核对最新背景。");}
    private ApiFailure invalid(){return new ApiFailure(400,"VALIDATION_ERROR","请填写有效的纠正内容或选择确切的撤回记录。");}
}