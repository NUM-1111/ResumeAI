package com.agentcv;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class RecycleIntegrationTest {
 @Autowired WorkspaceService s;
 @Autowired RecycleService r;
 @Autowired JdbcTemplate db;
 @Autowired MockMvc mvc;
 @Autowired PlatformTransactionManager manager;
 Map<String,Object> write(UUID p,java.util.function.Supplier<Map<String,Object>> f){return s.write("s02",UUID.randomUUID(),Map.of(),p,f);}
 UUID profile(){return WorkspaceService.id(write(null,()->s.createProfile(Map.of("displayName","回收合成档案"))).get("id"));}
 UUID workspace(UUID p){return WorkspaceService.id(write(p,()->s.createWorkspace(p,Map.of("name","回收合成区","direction","Java"))).get("id"));}
 UUID material(UUID p){return WorkspaceService.id(write(p,()->s.importText(p,Map.of("name",UUID.randomUUID()+".txt","text",UUID.randomUUID().toString()))).get("id"));}
 Map<String,Object> body(UUID p,String kind,UUID id){return Map.of("kind",kind,"id",id.toString(),"impactVersion",r.preview(p,kind,id).get("impactVersion"),"confirmed",true);}
 UUID recycle(UUID p,String kind,UUID id){var b=body(p,kind,id);return WorkspaceService.id(write(null,()->r.recycle(p,b)).get("entryId"));}
 void restore(UUID p,UUID e){write(null,()->r.restore(p,e,Map.of("confirmed",true)));}
 @Test void batchesRestoreWithoutRevivingEarlierDeletesOrOverwritingNames(){
  UUID p=profile(),q=profile(),w=workspace(p),old=workspace(p),m=material(p);
  UUID oldEntry=recycle(p,"WORKSPACE",old);
  UUID batch=recycle(p,"PROFILE",p);
  assertThatThrownBy(()->s.resume(p,w)).isInstanceOf(WorkspaceService.Failure.class);
  assertThatThrownBy(()->s.getImport(p,m)).isInstanceOf(WorkspaceService.Failure.class);
  assertThatThrownBy(()->restore(p,oldEntry)).isInstanceOf(WorkspaceService.Failure.class);
  assertThatThrownBy(()->restore(q,batch)).isInstanceOf(WorkspaceService.Failure.class);
  restore(p,batch);
  assertThat(s.resume(p,w)).containsEntry("initialized",false);
  assertThat(s.getImport(p,m)).containsEntry("checkState","UNCHECKED");
  assertThatThrownBy(()->s.resume(p,old)).isInstanceOf(WorkspaceService.Failure.class);
  UUID sameName=workspace(p);restore(p,oldEntry);
  assertThat(s.resume(p,old)).containsEntry("initialized",false);
  assertThat(s.resume(p,sameName)).containsEntry("initialized",false);
 }
 @Test void sourceBlockingHistoryRetentionAndSharedMaterials(){
  UUID p=profile(),w=workspace(p),m=material(p);
  write(p,()->s.confirm(p,m,Map.of("expectedVersion",1,"text","合成确认稿")));
  write(p,()->s.initialize(p,w,Map.of("sourceType","MATERIAL_REVISION","sourceId",m.toString(),"expectedSourceRevision",2)));
  assertThat(r.preview(p,"MATERIAL",m)).containsEntry("blocked",true);
  assertThatThrownBy(()->recycle(p,"MATERIAL",m)).hasMessageContaining("当前简历来源");
  UUID e=recycle(p,"WORKSPACE",w);
  assertThat(s.getImport(p,m)).containsEntry("checkState","CONFIRMED");
  assertThat(r.preview(p,"MATERIAL",m)).containsEntry("blocked",true);
  restore(p,e);assertThat(s.resume(p,w)).containsEntry("text","合成确认稿");
  UUID n=material(p);write(p,()->s.confirm(p,n,Map.of("expectedVersion",1,"text","新合成稿")));
  write(p,()->s.replace(p,w,Map.of("sourceId",n.toString(),"expectedSourceRevision",2,"expectedRevision",1)));
  UUID materialEntry=recycle(p,"MATERIAL",m);
  assertThatThrownBy(()->s.getImport(p,m)).isInstanceOf(WorkspaceService.Failure.class);
  assertThat(db.queryForObject("select source_id from resume_revision where workspace_id=? and revision=1",UUID.class,w)).isEqualTo(m);
  restore(p,materialEntry);assertThat(s.resume(p,w)).containsEntry("text","新合成稿");
 }
 @Test void stalePreviewRollbackAndIdempotentDeletedProfileRetry(){
  UUID p=profile(),w=workspace(p);var b=body(p,"PROFILE",p);
  workspace(p);
  assertThatThrownBy(()->write(null,()->r.recycle(p,b))).hasMessageContaining("影响已变化");
  var fresh=body(p,"PROFILE",p);
  assertThatThrownBy(()->new TransactionTemplate(manager).execute(status->{write(null,()->r.recycle(p,fresh));throw new IllegalStateException("forced rollback");})).isInstanceOf(IllegalStateException.class);
  assertThat(s.resume(p,w)).containsEntry("initialized",false);
  UUID key=UUID.randomUUID();var first=s.write("retry-delete",key,fresh,null,()->r.recycle(p,fresh));
  var retry=s.write("retry-delete",key,fresh,null,()->{throw new AssertionError("must replay result");});assertThat(retry).containsEntry("state",first.get("state"));
  UUID entry=WorkspaceService.id(first.get("entryId"));UUID restoreKey=UUID.randomUUID();
  var restoreBody=Map.<String,Object>of("confirmed",true);
  s.write("retry-restore",restoreKey,restoreBody,null,()->r.restore(p,entry,restoreBody));
  s.write("retry-restore",restoreKey,restoreBody,null,()->{throw new AssertionError("must replay");});
 }
 @Test void httpAuthorizationOwnershipAndConfirmation() throws Exception {
  UUID p=profile(),q=profile(),w=workspace(p);
  mvc.perform(get("/api/v1/recycle-entries")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/v1/profiles/"+p+"/recycle-operations").with(user("operator")).contentType("application/json").content("{}")) .andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/profiles/"+q+"/deletion-preview?kind=WORKSPACE&id="+w).with(user("operator"))).andExpect(status().isNotFound());
  mvc.perform(post("/api/v1/profiles/"+p+"/recycle-operations").with(user("operator")).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{}")) .andExpect(status().isBadRequest());
  UUID e=recycle(p,"WORKSPACE",w);
  mvc.perform(get("/api/v1/profiles/"+p+"/workspaces/"+w+"/resume").with(user("operator"))).andExpect(status().isNotFound());
  mvc.perform(post("/api/v1/profiles/"+q+"/recycle-entries/"+e+"/restore").with(user("operator")).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"confirmed\":true}")) .andExpect(status().isNotFound());
  assertThat(r.entries(1,null).get("items")).isInstanceOf(List.class);
 }
}