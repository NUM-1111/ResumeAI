package com.agentcv.conversation;

import com.agentcv.api.ApiFailure;
import com.agentcv.workspace.WorkspaceService;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Profile("m00")
public class ConversationStateService {
    private final JdbcTemplate db; private final WorkspaceService workspaces;
    public ConversationStateService(JdbcTemplate db,WorkspaceService workspaces){this.db=db;this.workspaces=workspaces;}
    public Map<String,Object> state(UUID p,UUID w){
        workspaces.workspace(p,w);var rows=db.queryForList("select version,epoch,boundary_at::text from conversation_state where workspace_id=?",w);
        return rows.isEmpty()?Map.of("version",0L,"epoch",0L,"boundary_at","-infinity"):rows.get(0);
    }
    public Map<String,Object> draft(UUID p,UUID w){
        workspaces.workspace(p,w);var rows=db.queryForList("select text,version from conversation_draft where workspace_id=?",w);
        return rows.isEmpty()?Map.of("text","","version",0L):rows.get(0);
    }
    public record Draft(String text,Long expected_version,Long expected_epoch) {}
    public record Reset(Long expected_version,Long expected_epoch) {}
    public Map<String,Object> saveDraft(UUID p,UUID w,UUID key,Draft input){
        if(input.text==null||input.text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768||input.expected_version==null||input.expected_epoch==null)
            throw new ApiFailure(400,"VALIDATION_ERROR","草稿或版本条件无效，最多 32 KiB。");
        return workspaces.write("DRAFT/"+p+"/"+w,key,input,()->workspaces.workspace(p,w),()->{
            lock(w);
            if(input.expected_epoch.longValue()!=((Number)state(p,w).get("epoch")).longValue())throw conflict();
            long version=((Number)draft(p,w).get("version")).longValue();
            if(version!=input.expected_version)throw conflict();
            db.update("insert into conversation_draft(workspace_id,text,version) values (?,?,?) on conflict(workspace_id) do update set text=excluded.text,version=excluded.version",w,input.text,version+1);
            return draft(p,w);
        });
    }
    public Map<String,Object> reset(UUID p,UUID w,UUID key,Reset input,boolean clear){
        if(input.expected_version==null||input.expected_epoch==null)throw conflict();
        return workspaces.write("CONTEXT/"+clear+"/"+p+"/"+w,key,input,()->workspaces.workspace(p,w),()->{
            lock(w);var current=state(p,w);
            if(input.expected_version.longValue()!=((Number)current.get("version")).longValue()||input.expected_epoch.longValue()!=((Number)current.get("epoch")).longValue())throw conflict();
            if(db.queryForObject("select count(*) from ai_request where workspace_id=? and (execution_status in ('ACCEPTED','CALLING') or record_status='SAVE_FAILED')",Integer.class,w)>0)
                throw new ApiFailure(409,"REQUEST_IN_PROGRESS","请等待本次回答结束并保存后再操作。");
            db.update("insert into conversation_state(workspace_id,version,epoch,boundary_at) values (?,1,1,clock_timestamp()) on conflict(workspace_id) do update set version=conversation_state.version+1,epoch=conversation_state.epoch+1,boundary_at=clock_timestamp()",w);
            if(clear){
                // Keep operation tombstones, but no old message or request-body archive.
                db.update("delete from conversation_message where workspace_id=?",w);
                db.update("delete from operation where scope=?","DRAFT/"+p+"/"+w);
                db.update("update ai_request set visible=false,message='',answer=null,input_json='',basis_json='{}' where workspace_id=?",w);
                db.update("insert into conversation_draft(workspace_id,text,version) values (?,'',1) on conflict(workspace_id) do update set text='',version=conversation_draft.version+1",w);
            }
            return state(p,w);
        });
    }
    public void lock(UUID w){db.queryForObject("select id from workspace where id=? for update",UUID.class,w);}
    private ApiFailure conflict(){return new ApiFailure(409,"VERSION_CONFLICT","对话或草稿已在别处变化，输入仍保留，请核对最新状态后再操作。");}
}