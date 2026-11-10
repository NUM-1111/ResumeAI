package com.agentcv;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class RecycleService {
    private final JdbcTemplate db;
    private final WorkspaceService workspace;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public RecycleService(JdbcTemplate db,WorkspaceService workspace,ObjectMapper json,PlatformTransactionManager manager) {
        this.db=db;this.workspace=workspace;this.json=json;this.tx=new TransactionTemplate(manager);
    }
    void lock(UUID p) { workspace.one("select id from profile where id=? for update",p); }
    String table(String kind) {
        return switch(kind) {case "PROFILE"->"profile";case "WORKSPACE"->"workspace";case "MATERIAL"->"material_import";default->throw WorkspaceService.bad("回收对象类型无效");};
    }
    Map<String,Object> object(UUID p,String kind,UUID id) {
        workspace.one("select id from profile where id=? and recycle_batch is null",p);
        if(kind.equals("PROFILE")&&!p.equals(id))throw new WorkspaceService.Failure(404,"NOT_FOUND","档案不匹配");
        return workspace.one("select id,"+(kind.equals("PROFILE")?"display_name":"name")+" as name from "+table(kind)+" where id=?"+(kind.equals("PROFILE")?"":" and profile_id=?")+" and recycle_batch is null",kind.equals("PROFILE")?new Object[]{id}:new Object[]{id,p});
    }
    String stamp(UUID p) {
        // Full dependency state, but no resume content is returned to clients or logs.
        var state=List.of(
            db.queryForList("select id,recycle_batch from profile where id=?",p),
            db.queryForList("select id,name,direction,row_version,recycle_batch from workspace where profile_id=? order by id",p),
            db.queryForList("select id,name,row_version,recycle_batch from material_import where profile_id=? order by id",p),
            db.queryForList("select workspace_id,revision from current_resume where profile_id=? order by workspace_id",p),
            db.queryForList("select workspace_id,material_id from resume_source_binding where profile_id=? order by workspace_id,material_id",p));
        try {return WorkspaceService.hash(json.writeValueAsBytes(state));}catch(Exception e){throw new IllegalStateException(e);}
    }
    public Map<String,Object> preview(UUID p,String kind,UUID id) {
        return tx.execute(status->{lock(p);return previewLocked(p,kind,id);});
    }
    Map<String,Object> previewLocked(UUID p,String kind,UUID id) {
        var root=object(p,kind,id);
        var dependents=kind.equals("MATERIAL")?db.queryForList("select w.name, (w.recycle_batch is not null) as recycled from resume_source_binding b join workspace w on w.id=b.workspace_id and w.profile_id=b.profile_id where b.profile_id=? and b.material_id=? order by w.name,w.id",p,id):List.<Map<String,Object>>of();
        int workspaces=kind.equals("PROFILE")?db.queryForObject("select count(*) from workspace where profile_id=? and recycle_batch is null",Integer.class,p):kind.equals("WORKSPACE")?1:0;
        int materials=kind.equals("PROFILE")?db.queryForObject("select count(*) from material_import where profile_id=? and recycle_batch is null",Integer.class,p):kind.equals("MATERIAL")?1:0;
        String impact=switch(kind){
            case "PROFILE"->"该档案及仍有效的工作区、材料一并进入回收站；恢复只恢复本次批次，不复活此前单独删除的内容。";
            case "WORKSPACE"->"工作区及其当前稿、历史在正常入口不可访问；档案共享材料及其他工作区不受影响。";
            default->"材料进入回收站，原件和历史出处保留；不会删除已保存简历正文，恢复不自动添加引用。";};
        return Map.of("kind",kind,"id",id,"name",root.get("name"),"workspaceCount",workspaces,"materialCount",materials,"impact",impact,"bindings",dependents,"blocked",!dependents.isEmpty(),"impactVersion",WorkspaceService.hash((kind+id+stamp(p)).getBytes(StandardCharsets.UTF_8)));
    }
    public Map<String,Object> recycle(UUID p,Map<String,Object>b) {
        lock(p);
        if(!Boolean.TRUE.equals(b.get("confirmed")))throw WorkspaceService.bad("请明确确认删除影响");
        String kind=WorkspaceService.string(b,"kind",20);UUID id=WorkspaceService.id(b.get("id"));
        var preview=previewLocked(p,kind,id);
        if(!preview.get("impactVersion").equals(b.get("impactVersion")))throw WorkspaceService.conflict("IMPACT_CHANGED","删除影响已变化，未删除。请重新预览");
        if(Boolean.TRUE.equals(preview.get("blocked")))throw WorkspaceService.conflict("SOURCE_IN_USE","材料仍作为当前简历来源，请先在列出的工作区更换简历；回收中的区须先恢复");
        UUID batch=UUID.randomUUID();
        db.update("insert into recycle_entry(id,profile_id,kind,object_id,name) values(?,?,?,?,?)",batch,p,kind,id,preview.get("name"));
        db.update("update "+table(kind)+" set recycle_batch=? where id=?",batch,id);
        if(kind.equals("PROFILE")) {
            db.update("update workspace set recycle_batch=? where profile_id=? and recycle_batch is null",batch,p);
            db.update("update material_import set recycle_batch=? where profile_id=? and recycle_batch is null",batch,p);
        }
        return Map.of("entryId",batch,"name",preview.get("name"),"state","RECYCLED");
    }
    public Map<String,Object> entries(int limit,UUID after) {
        if(limit<1||limit>100)throw WorkspaceService.bad("分页大小须为1至100");
        var rows=after==null?db.queryForList("select e.id,e.profile_id as \"profileId\",e.kind,e.name,e.created_at as \"createdAt\",p.display_name as \"profileName\" from recycle_entry e join profile p on p.id=e.profile_id where e.restored_at is null order by e.id limit ?",limit+1):
            db.queryForList("select e.id,e.profile_id as \"profileId\",e.kind,e.name,e.created_at as \"createdAt\",p.display_name as \"profileName\" from recycle_entry e join profile p on p.id=e.profile_id where e.restored_at is null and e.id>? order by e.id limit ?",after,limit+1);
        boolean more=rows.size()>limit;if(more)rows.remove(rows.size()-1);
        Map<String,Object> result=new LinkedHashMap<>();result.put("items",rows);result.put("nextCursor",more?rows.get(rows.size()-1).get("id"):null);return result;
    }
    public Map<String,Object> restore(UUID p,UUID entry,Map<String,Object>b) {
        lock(p);
        if(!Boolean.TRUE.equals(b.get("confirmed")))throw WorkspaceService.bad("请明确确认恢复");
        var row=workspace.one("select kind,object_id,name,restored_at from recycle_entry where id=? and profile_id=? for update",entry,p);
        if(row.get("restored_at")!=null)throw WorkspaceService.conflict("ALREADY_RESTORED","该条目已恢复，请刷新回收站");
        String kind=(String)row.get("kind");UUID id=(UUID)row.get("object_id");
        if(!kind.equals("PROFILE"))workspace.one("select id from profile where id=? and recycle_batch is null",p);
        if(db.update("update "+table(kind)+" set recycle_batch=null where id=? and recycle_batch=?",id,entry)!=1)throw WorkspaceService.conflict("RECYCLE_CHANGED","回收状态已变化，未恢复");
        if(kind.equals("PROFILE")) {
            db.update("update workspace set recycle_batch=null where profile_id=? and recycle_batch=?",p,entry);
            db.update("update material_import set recycle_batch=null where profile_id=? and recycle_batch=?",p,entry);
        }
        db.update("update recycle_entry set restored_at=CURRENT_TIMESTAMP where id=?",entry);
        return Map.of("entryId",entry,"name",row.get("name"),"state","RESTORED");
    }
}