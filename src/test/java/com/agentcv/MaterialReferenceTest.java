package com.agentcv;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MaterialReferenceTest {
 @Autowired WorkspaceService s;
 @Autowired RecycleService recycle;
 @Autowired MaterialReferenceController refs;
 @Autowired JdbcTemplate db;
 @Autowired MockMvc mvc;
 Map<String,Object> write(UUID p,java.util.function.Supplier<Map<String,Object>> f){return s.write("s03",UUID.randomUUID(),Map.of(),p,f);}
 UUID profile(){return WorkspaceService.id(write(null,()->s.createProfile(Map.of("displayName","文档合成人物"))).get("id"));}
 UUID workspace(UUID p){return WorkspaceService.id(write(p,()->s.createWorkspace(p,Map.of("name","引用区","direction","Java"))).get("id"));}
 @Test void documentOriginalChecksConfirmationAndNoImplicitUse() throws Exception {
  UUID p=profile();byte[] bytes=DocumentImportTest.docx();
  var m=write(p,()->s.importText(p,Map.of("name","合成.docx","base64",Base64.getEncoder().encodeToString(bytes))));UUID id=WorkspaceService.id(m.get("id"));
  assertThat(m).containsEntry("checkState","UNCHECKED");assertThat((String)m.get("extractedText")).contains("合成中文");
  write(p,()->s.confirm(p,id,Map.of("text","修正的合成文本","expectedVersion",1)));
  assertThat(db.queryForObject("select text from material_confirmation where material_id=?",String.class,id)).isEqualTo("修正的合成文本");
 }
 @Test void explicitReferenceOwnershipVersionRevokeAndRecovery(){
  UUID p=profile(),q=profile(),w=workspace(p),qw=workspace(q);
  var m=write(p,()->s.importText(p,Map.of("name","项目.txt","text",UUID.randomUUID().toString(),"materialKind","REFERENCE")));UUID id=WorkspaceService.id(m.get("id"));
  var unconfirmed=Map.<String,Object>of("materialId",id.toString(),"expectedVersion",1);
  assertThatThrownBy(()->refs.mutate(p,w,id,UUID.randomUUID().toString(),unconfirmed,true)).hasMessageContaining("检查确认");
  write(p,()->s.confirm(p,id,Map.of("text","辅助合成依据","expectedVersion",1)));
  var b=Map.<String,Object>of("materialId",id.toString(),"expectedVersion",2);
  assertThatThrownBy(()->refs.mutate(q,qw,id,UUID.randomUUID().toString(),b,true)).isInstanceOf(WorkspaceService.Failure.class);
  assertThatThrownBy(()->write(p,()->s.initialize(p,w,Map.of("sourceType","MATERIAL_REVISION","sourceId",id.toString(),"expectedSourceRevision",2)))).hasMessageContaining("辅助材料");
  refs.mutate(p,w,id,UUID.randomUUID().toString(),b,true);
  assertThatThrownBy(()->refs.mutate(p,w,id,UUID.randomUUID().toString(),unconfirmed,true)).hasMessageContaining("材料已变化");
  assertThat(db.queryForObject("select count(*) from workspace_material_reference where material_id=?",Integer.class,id)).isEqualTo(1);
  refs.mutate(p,w,id,UUID.randomUUID().toString(),b,false);
  assertThat(db.queryForObject("select count(*) from workspace_material_reference where material_id=?",Integer.class,id)).isZero();
  refs.mutate(p,w,id,UUID.randomUUID().toString(),b,true);
  var preview=recycle.preview(p,"MATERIAL",id);
  assertThat((List<?>)preview.get("references")).hasSize(1);
  var deletion=Map.<String,Object>of("kind","MATERIAL","id",id.toString(),"impactVersion",preview.get("impactVersion"),"confirmed",true);
  refs.mutate(p,w,id,UUID.randomUUID().toString(),b,false);
  assertThatThrownBy(()->write(null,()->recycle.recycle(p,deletion))).hasMessageContaining("影响已变化");
  refs.mutate(p,w,id,UUID.randomUUID().toString(),b,true);
  var entry=write(null,()->recycle.recycle(p,deletion));
  write(null,()->recycle.restore(p,WorkspaceService.id(entry.get("entryId")),Map.of("confirmed",true)));
  assertThat(db.queryForObject("select count(*) from workspace_material_reference where material_id=?",Integer.class,id)).isZero();
  assertThat(s.resume(p,w)).containsEntry("initialized",false);
 }
 @Test void referenceHttpRequiresAuthenticationCsrfAndOwnership() throws Exception {
  UUID p=profile(),w=workspace(p);String url="/api/v1/profiles/"+p+"/workspaces/"+w+"/material-references";
  mvc.perform(get(url)).andExpect(status().isUnauthorized());
  mvc.perform(post(url).with(user("operator")).contentType("application/json").content("{}")) .andExpect(status().isForbidden());
  mvc.perform(get(url.replace(p.toString(),profile().toString())).with(user("operator"))).andExpect(status().isNotFound());
 }
}