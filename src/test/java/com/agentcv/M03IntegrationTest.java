package com.agentcv;

import com.agentcv.material.MaterialService;
import com.agentcv.workspace.WorkspaceService;
import com.agentcv.document.ResumeFiles;
import com.agentcv.api.ApiFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@EnabledIfEnvironmentVariable(named="AGENTCV_M00_DB_PASSWORD",matches=".+")
class M03IntegrationTest {
    @Autowired MaterialService service;
    @Autowired WorkspaceService workspaces;
    @Autowired ResumeFiles files;
    UUID person(){return UUID.fromString(workspaces.createProfile(UUID.randomUUID(),new WorkspaceService.CreateProfile("材料合成人物")).get("id").toString());}
    UUID workspace(UUID p){return UUID.fromString(workspaces.createWorkspace(p,UUID.randomUUID(),new WorkspaceService.CreateWorkspace("参考区","Java")).get("id").toString());}
    @Test void independentMaterialDoesNotRequireWorkspaceAndReplayIsSafe(){
        UUID p=person(),key=UUID.randomUUID();var body=new MaterialService.Create("项目说明","协助部署",null,false);
        var first=service.create(p,key,body);var replay=service.create(p,key,body);
        assertThat(replay.get("id")).isEqualTo(first.get("id"));assertThat((List<?>)first.get("references")).isEmpty();
        assertThat((List<?>)workspaces.workspaces(p,null,"").get("items")).isEmpty();
        assertThatThrownBy(()->service.create(p,UUID.randomUUID(),body)).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code).isEqualTo("DUPLICATE"));
    }
    @Test void renameAndTextHaveSeparateMeaningAndConflictsPreserveData(){
        UUID p=person();var initial=service.create(p,UUID.randomUUID(),new MaterialService.Create("最初名称","原文",null,false));UUID m=(UUID)initial.get("id");
        var renamed=service.edit(p,m,UUID.randomUUID(),new MaterialService.Edit("新名称",null,1L));
        assertThat(renamed.get("content_updated_at")).isEqualTo(initial.get("content_updated_at"));
        assertThat(renamed.get("text_version")).isEqualTo(initial.get("text_version"));
        assertThatThrownBy(()->service.edit(p,m,UUID.randomUUID(),new MaterialService.Edit(null,"过期修改",1L))).isInstanceOf(ApiFailure.class);
        assertThat(service.get(p,m).get("text")).isEqualTo("原文");
        assertThat(service.edit(p,m,UUID.randomUUID(),new MaterialService.Edit(null,"已修正",2L)).get("text")).isEqualTo("已修正");
        assertThatThrownBy(()->service.get(person(),m)).isInstanceOf(ApiFailure.class);
    }
    @Test void multiWorkspaceReferencesAreExplicitAndVersioned(){
        UUID p=person(),w1=workspace(p),w2=workspace(p),foreign=workspace(person());
        UUID m=(UUID)service.create(p,UUID.randomUUID(),new MaterialService.Create("多区材料","合成",null,false)).get("id");
        service.reference(p,w1,m,UUID.randomUUID(),new MaterialService.Reference(0L),true);
        service.reference(p,w2,m,UUID.randomUUID(),new MaterialService.Reference(0L),true);
        assertThat((List<?>)service.get(p,m).get("references")).hasSize(2);
        assertThatThrownBy(()->service.reference(p,w1,m,UUID.randomUUID(),new MaterialService.Reference(0L),false)).isInstanceOf(ApiFailure.class);
        service.reference(p,w1,m,UUID.randomUUID(),new MaterialService.Reference(1L),false);
        assertThat((List<?>)service.get(p,m).get("references")).hasSize(1);
        assertThatThrownBy(()->service.reference(p,foreign,m,UUID.randomUUID(),new MaterialService.Reference(0L),true)).isInstanceOf(ApiFailure.class);
        assertThat(workspaces.resume(p,w1).get("initialized")).isEqualTo(false);
    }
    @Test void originalSurvivesTextEditAndCleanup(){
        UUID p=person();byte[] bytes="上传原文".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var original=service.create(p,UUID.randomUUID(),new MaterialService.Create("原件.txt",null,Base64.getEncoder().encodeToString(bytes),false));UUID m=(UUID)original.get("id");
        service.edit(p,m,UUID.randomUUID(),new MaterialService.Edit(null,"手工修改材料文本",1L));files.cleanup();
        assertThat(service.original(p,m).bytes()).isEqualTo(bytes);
        assertThat(service.get(p,m).get("text")).isEqualTo("手工修改材料文本");
    }
    @Test void uploadedCreateReplayKeepsSingleMaterialAndOriginal(){
        UUID p=person(),key=UUID.randomUUID();
        var body=new MaterialService.Create("重复请求.txt",null,Base64.getEncoder().encodeToString("原件".getBytes(java.nio.charset.StandardCharsets.UTF_8)),false);
        var first=service.create(p,key,body);var replay=service.create(p,key,body);
        assertThat(replay.get("id")).isEqualTo(first.get("id"));
        assertThat((List<?>)service.list(p,"",0).get("items")).hasSize(1);
        assertThat(service.original(p,(UUID)first.get("id")).bytes()).isEqualTo("原件".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}