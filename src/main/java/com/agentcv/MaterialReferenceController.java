package com.agentcv;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/profiles/{p}/workspaces/{w}/material-references")
public class MaterialReferenceController {
    private final WorkspaceService s;
    private final JdbcTemplate db;
    public MaterialReferenceController(WorkspaceService s,JdbcTemplate db){this.s=s;this.db=db;}
    @GetMapping Object list(@PathVariable UUID p,@PathVariable UUID w){
        s.workspace(p,w);
        return Map.of("items",db.queryForList("select m.id,m.name,m.row_version as \"rowVersion\",m.confirmed_text as \"confirmedText\" from workspace_material_reference r join material_import m on m.id=r.material_id and m.profile_id=r.profile_id where r.workspace_id=? and r.profile_id=? and m.recycle_batch is null and m.check_state='CONFIRMED' order by m.name,m.id",w,p));
    }
    @PostMapping Object add(@PathVariable UUID p,@PathVariable UUID w,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object>b){return mutate(p,w,WorkspaceService.id(b.get("materialId")),key,b,true);}
    @DeleteMapping("/{m}") Object remove(@PathVariable UUID p,@PathVariable UUID w,@PathVariable UUID m,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object>b){return mutate(p,w,m,key,b,false);}
    Object mutate(UUID p,UUID w,UUID m,String key,Map<String,Object>b,boolean add){
        return s.write("REFERENCE/"+p+"/"+w+"/"+m+"/"+add,WorkspaceService.id(key),b,p,()->{
            s.workspace(p,w);var material=s.getImport(p,m);
            if(((Number)material.get("rowVersion")).intValue()!=WorkspaceService.number(b,"expectedVersion"))throw WorkspaceService.conflict("VERSION_CONFLICT","材料已变化，请重新打开后操作");
            if(add){
                if(!"REFERENCE".equals(material.get("materialKind")))throw WorkspaceService.bad("请添加辅助材料，当前简历来源与辅助引用分开管理");
                if(!"CONFIRMED".equals(material.get("checkState")))throw WorkspaceService.conflict("UNCHECKED","先检查确认文本，再引用");
                db.update("insert into workspace_material_reference(workspace_id,profile_id,material_id) values(?,?,?) on conflict do nothing",w,p,m);
            }else db.update("delete from workspace_material_reference where workspace_id=? and profile_id=? and material_id=?",w,p,m);
            return Map.of("materialId",m,"referenced",add);
        });
    }
}