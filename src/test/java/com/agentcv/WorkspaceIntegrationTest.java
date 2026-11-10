package com.agentcv;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import java.util.*;
import java.util.concurrent.*;
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
class WorkspaceIntegrationTest {
 @Autowired WorkspaceService s;
 @Autowired JdbcTemplate db;
 @Autowired MockMvc mvc;
 @Autowired PlatformTransactionManager manager;
 Map<String,Object> write(UUID p,String scope,Map<String,Object>b,java.util.function.Supplier<Map<String,Object>> f){return s.write(scope,UUID.randomUUID(),b,p,f);}
 UUID profile(){return WorkspaceService.id(write(null,"test-profile",Map.of(),()->s.createProfile(Map.of("displayName","合成测试人物"))).get("id"));}
 UUID workspace(UUID p){return WorkspaceService.id(write(p,"test-workspace",Map.of(),()->s.createWorkspace(p,Map.of("name","后端测试","direction","Java"))).get("id"));}
 UUID material(UUID p){return WorkspaceService.id(write(p,"test-import",Map.of(),()->s.importText(p,Map.of("name",UUID.randomUUID()+".txt","text","合成简历：参与接口设计"))).get("id"));}
 void confirm(UUID p,UUID m){write(p,"test-confirm",Map.of(),()->s.confirm(p,m,Map.of("text","合成简历：负责接口设计","expectedVersion",1)));}
 Map<String,Object> init(UUID p,UUID w,UUID m){return write(p,"test-init",Map.of(),()->s.initialize(p,w,Map.of("sourceType","MATERIAL_REVISION","sourceId",m.toString(),"expectedSourceRevision",2)));}
  @Test void renameAndConfirmNameArePersistentAndVersioned() throws Exception {
   UUID p=profile(),q=profile(),m=material(p);
   var body=Map.<String,Object>of("name","检查时改名.txt","text","确认后的正文","expectedVersion",1);
   write(p,"confirm-name",body,()->s.confirm(p,m,body));
   assertThat(s.getImport(p,m)).containsEntry("name","检查时改名.txt").containsEntry("confirmedText","确认后的正文");
   String url="/api/v1/profiles/"+p+"/material-imports/"+m;
   String key=UUID.randomUUID().toString();
   for(int i=0;i<2;i++) mvc.perform(patch(url).with(user("operator")).with(csrf()).header("Idempotency-Key",key).contentType("application/json").content("{\"name\":\"确认后改名.txt\",\"expectedVersion\":2}"))
     .andExpect(status().isOk()).andExpect(jsonPath("$.rowVersion").value(3));
   assertThat(s.getImport(p,m)).containsEntry("name","确认后改名.txt").containsEntry("confirmedText","确认后的正文");
   mvc.perform(patch(url).with(user("operator")).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"name\":\"过期名\",\"expectedVersion\":2}"))
     .andExpect(status().isConflict());
   mvc.perform(patch(url.replace(p.toString(),q.toString())).with(user("operator")).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"name\":\"跨档案\",\"expectedVersion\":3}"))
     .andExpect(status().isNotFound());
  }
  @Test void replacementIsAtomicVersionedOwnedAndIdempotent() throws Exception {
   UUID p=profile(),q=profile(),w=workspace(p),m=material(p),foreign=material(q);
   UUID n=WorkspaceService.id(write(p,"second-source",Map.of(),()->s.importText(p,Map.of("name","第二份.txt","text","第二份合成简历"))).get("id"));
   confirm(p,m);init(p,w,m);
   var b=new HashMap<String,Object>(Map.of("sourceId",n.toString(),"expectedSourceRevision",1,"expectedRevision",1));
   assertThatThrownBy(()->write(p,"replace-unchecked",b,()->s.replace(p,w,b))).hasMessageContaining("检查确认");
   confirm(p,n);
   b.put("expectedSourceRevision",2);b.put("sourceId",foreign.toString());
   assertThatThrownBy(()->write(p,"replace-foreign",b,()->s.replace(p,w,b))).isInstanceOf(WorkspaceService.Failure.class);
   b.put("sourceId",n.toString());b.put("expectedSourceRevision",1);
   assertThatThrownBy(()->write(p,"replace-source-stale",b,()->s.replace(p,w,b))).hasMessageContaining("来源材料已变化");
   b.put("expectedSourceRevision",2);b.put("expectedRevision",0);
   assertThatThrownBy(()->write(p,"replace-current-stale",b,()->s.replace(p,w,b))).hasMessageContaining("当前简历已变化");
   b.put("expectedRevision",1);
   String url="/api/v1/profiles/"+p+"/workspaces/"+w+"/resume/replace",key=UUID.randomUUID().toString();
   String payload="{\"sourceId\":\""+n+"\",\"expectedSourceRevision\":2,\"expectedRevision\":1}";
   for(int i=0;i<2;i++)mvc.perform(post(url).with(user("operator")).with(csrf()).header("Idempotency-Key",key).contentType("application/json").content(payload))
     .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
   assertThat(db.queryForList("select revision from resume_revision where workspace_id=? order by revision",w)).hasSize(2);
   assertThat(db.queryForObject("select reason from resume_revision where workspace_id=? and revision=2",String.class,w)).isEqualTo("REPLACE");
   assertThat(db.queryForObject("select material_id from resume_source_binding where workspace_id=?",UUID.class,w)).isEqualTo(n);
   assertThat(db.queryForObject("select source_id from resume_revision where workspace_id=? and revision=1",UUID.class,w)).isEqualTo(m);
   assertThatThrownBy(()->write(p,"old-tab-save",Map.of(),()->s.save(p,w,Map.of("text","旧标签稿","expectedRevision",1,"clientEditSequence",1)))).hasMessageContaining("未覆盖");
  }
  @Test void confirmationIsolationAndIndependentCopy(){
  UUID p=profile(),q=profile(),w=workspace(p),m=material(p);
  assertThatThrownBy(()->init(p,w,m)).isInstanceOf(WorkspaceService.Failure.class).hasMessageContaining("检查确认");
  assertThatThrownBy(()->s.resume(q,w)).isInstanceOf(WorkspaceService.Failure.class);
  assertThatThrownBy(()->s.getImport(q,m)).isInstanceOf(WorkspaceService.Failure.class);
  confirm(p,m);assertThat(init(p,w,m).get("text")).isEqualTo("合成简历：负责接口设计");
  UUID copied=workspace(p);
  write(p,"copy",Map.of(),()->s.initialize(p,copied,Map.of("sourceType","WORKSPACE_CURRENT","sourceId",w.toString(),"expectedSourceRevision",1)));
  write(p,"save",Map.of(),()->s.save(p,copied,Map.of("text","新区独立编辑","expectedRevision",1,"clientEditSequence",1)));
  assertThat(s.resume(p,w).get("text")).isEqualTo("合成简历：负责接口设计");
  assertThat(db.queryForObject("select count(*) from resume_source_binding where workspace_id=? and material_id=?",Integer.class,copied,m)).isEqualTo(1);
  UUID foreign=workspace(q);
  assertThatThrownBy(()->write(q,"cross-copy",Map.of(),()->s.initialize(q,foreign,Map.of("sourceType","WORKSPACE_CURRENT","sourceId",w.toString(),"expectedSourceRevision",1)))).isInstanceOf(WorkspaceService.Failure.class);
 }
 @Test void idempotencyConflictAndAtomicHistory(){
  UUID p=profile(),w=workspace(p),m=material(p);confirm(p,m);init(p,w,m);
  UUID key=UUID.randomUUID();String scope="test-save/"+w;
  Map<String,Object>b=Map.of("text","保存后的中文稿","expectedRevision",1,"clientEditSequence",7);
  var a=s.write(scope,key,b,p,()->s.save(p,w,b));var retry=s.write(scope,key,b,p,()->{throw new AssertionError("must not execute twice");});
  assertThat(a.get("revision")).isEqualTo(retry.get("revision"));
  assertThatThrownBy(()->s.write(scope,key,Map.of("text","changed"),p,()->s.save(p,w,b))).isInstanceOf(WorkspaceService.Failure.class);
  assertThatThrownBy(()->write(p,"stale",b,()->s.save(p,w,b))).isInstanceOf(WorkspaceService.Failure.class);
  assertThat(db.queryForObject("select count(*) from resume_revision where workspace_id=?",Integer.class,w)).isEqualTo(2);
  new TransactionTemplate(manager).execute(status->{
   write(p,"rollback",Map.of(),()->s.save(p,w,Map.of("text","应回滚","expectedRevision",2,"clientEditSequence",8)));
   status.setRollbackOnly();return null;
  });
  assertThat(s.resume(p,w).get("text")).isEqualTo("保存后的中文稿");
  write(p,"same-text",Map.of(),()->s.save(p,w,Map.of("text","保存后的中文稿","expectedRevision",2,"clientEditSequence",9)));
  assertThat(db.queryForObject("select count(*) from resume_revision where workspace_id=?",Integer.class,w)).isEqualTo(2);
 }
 @Test void concurrentSavesOnlyOneWins() throws Exception {
  UUID p=profile(),w=workspace(p),m=material(p);confirm(p,m);init(p,w,m);
  ExecutorService pool=Executors.newFixedThreadPool(2);
  try {
   List<Callable<Boolean>> calls=new ArrayList<>();
   for(String t:List.of("并发甲","并发乙")) calls.add(()->{try{write(p,"concurrent/"+w,Map.of(),()->s.save(p,w,Map.of("text",t,"expectedRevision",1,"clientEditSequence",1)));return true;}catch(WorkspaceService.Failure e){return false;}});
   int successes=0;for(Future<Boolean> result:pool.invokeAll(calls)) if(result.get())successes++;
   assertThat(successes).isEqualTo(1);assertThat(s.resume(p,w).get("revision")).isEqualTo(2);
  } finally {pool.shutdownNow();}
 }
 @Test void strictTextUploadDuplicatesAndBounds(){
  UUID p=profile();UUID m=material(p);
  assertThatThrownBy(()->write(p,"dup",Map.of(),()->s.importText(p,Map.of("name","另一名.txt","text","合成简历：参与接口设计")))).isInstanceOf(WorkspaceService.Failure.class).hasMessageContaining("同名");
  write(p,"keep",Map.of(),()->s.importText(p,Map.of("name","另一名.txt","text","合成简历：参与接口设计","keepDuplicate",true)));
  assertThatThrownBy(()->write(p,"encoding",Map.of(),()->s.importText(p,Map.of("name","bad.txt","base64","//4=")))).isInstanceOf(WorkspaceService.Failure.class).hasMessageContaining("UTF-8");
  assertThatThrownBy(()->write(p,"limit",Map.of(),()->s.importText(p,Map.of("name","big.txt","text","中".repeat(90000))))).isInstanceOf(WorkspaceService.Failure.class);
  assertThatThrownBy(()->s.list(p,"workspaces",101,null)).isInstanceOf(WorkspaceService.Failure.class);
  assertThat(s.getImport(p,m).get("checkState")).isEqualTo("UNCHECKED");
 }
 @Test void httpSecurityErrorContractAndPagination() throws Exception {
  mvc.perform(get("/api/v1/profiles")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/v1/profiles").with(user("operator")).contentType("application/json").content("{\"displayName\":\"合成\"}" )).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/profiles").with(user("operator")).with(csrf()).contentType("application/json").content("{}"))
   .andExpect(status().isBadRequest()).andExpect(jsonPath("$.requestId").isNotEmpty());
  UUID p=profile(),w=workspace(p),q=profile();
  mvc.perform(get("/api/v1/profiles/"+q+"/workspaces/"+w+"/resume").with(user("operator"))).andExpect(status().isNotFound());
  mvc.perform(get("/api/v1/profiles?limit=1").with(user("operator"))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.nextCursor").isNotEmpty());
 }
 @Test void searchableNameOrderedPagesKeepOwnershipAndSameNameRows() {
  new TransactionTemplate(manager).execute(status->{
   UUID p=profile(),other=profile();
   for(String name:List.of("C区","A区","A区","B区")) {
    write(p,"page-test",Map.of(),()->s.createWorkspace(p,Map.of("name",name,"direction","Java")));
   }
   workspace(other);
   @SuppressWarnings("unchecked")
   var first=(List<Map<String,Object>>)s.list(p,"workspaces",2,null,"").get("items");
   assertThat(first).extracting(x->x.get("name")).containsExactly("A区","A区");
   var page=s.list(p,"workspaces",2,WorkspaceService.id(first.get(1).get("id")),"");
   @SuppressWarnings("unchecked")
   var second=(List<Map<String,Object>>)page.get("items");
   assertThat(second).extracting(x->x.get("name")).containsExactly("B区","C区");
   assertThat(page.get("nextCursor")).isNull();
   @SuppressWarnings("unchecked")
   var found=(List<Map<String,Object>>)s.list(p,"workspaces",10,null,"B区").get("items");
   assertThat(found).hasSize(1);
   assertThat(s.list(other,"workspaces",10,null,"B区").get("items")).isEqualTo(List.of());
   status.setRollbackOnly();return null;
  });
 }
}