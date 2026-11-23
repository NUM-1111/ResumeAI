package com.agentcv;

import com.agentcv.conversation.ConversationService;
import com.agentcv.model.ModelGateway;
import com.agentcv.material.MaterialService;
import com.agentcv.workspace.WorkspaceService;
import com.agentcv.api.ApiFailure;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@EnabledIfEnvironmentVariable(named="AGENTCV_M00_DB_PASSWORD",matches=".+")
class M04IntegrationTest {
    @Autowired ConversationService service;
    @Autowired WorkspaceService workspaces;
    @Autowired MaterialService materials;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @MockBean ModelGateway gateway;
    @Test void stoppedMaterialIsExcludedButOldAnswerRemains(){
        when(gateway.configured()).thenReturn(true);when(gateway.model()).thenReturn("synthetic-model");
        when(gateway.complete(anyString())).thenReturn(new ModelGateway.Result("旧回答保留其当时依据","test",Map.of()));
        UUID p=UUID.fromString(workspaces.createProfile(UUID.randomUUID(),new WorkspaceService.CreateProfile("停止参考合成测试")).get("id").toString());
        UUID w=UUID.fromString(workspaces.createWorkspace(p,UUID.randomUUID(),new WorkspaceService.CreateWorkspace("停止参考","Java")).get("id").toString());
        UUID m=(UUID)materials.create(p,UUID.randomUUID(),new MaterialService.Create("独立材料","独特材料标记-STOP-REFERENCE",null,false)).get("id");
        materials.reference(p,w,m,UUID.randomUUID(),new MaterialService.Reference(0L),true);
        UUID first=(UUID)service.send(p,w,UUID.randomUUID(),new ConversationService.Send("首个合成问题",false,0L,1L,Map.of(m.toString(),1L))).get("id");
        service.processPendingForTest(first);
        materials.reference(p,w,m,UUID.randomUUID(),new MaterialService.Reference(1L),false);
        UUID second=(UUID)service.send(p,w,UUID.randomUUID(),new ConversationService.Send("新的一般问题",false,0L,2L,Map.of(),2L,0L)).get("id");
        String snapshot=db.queryForObject("select input_json from ai_request where id=?",String.class,second);
        assertThat(snapshot).doesNotContain("独特材料标记-STOP-REFERENCE").contains("首个合成问题","旧回答保留其当时依据","当前有效资料白名单");
        assertThat(service.get(p,w,first)).containsEntry("answer","旧回答保留其当时依据").containsEntry("basis_changed",true);
        service.processPendingForTest(second);
    }
    @Test void generatedAnswerSaveRetryDoesNotCallModelTwice(){
        when(gateway.configured()).thenReturn(true);when(gateway.model()).thenReturn("synthetic-model");
        when(gateway.complete(anyString())).thenReturn(new ModelGateway.Result("保留的合成回答","test",Map.of()));
        UUID p=UUID.fromString(workspaces.createProfile(UUID.randomUUID(),new WorkspaceService.CreateProfile("回答保存故障")).get("id").toString());
        UUID w=UUID.fromString(workspaces.createWorkspace(p,UUID.randomUUID(),new WorkspaceService.CreateWorkspace("保存故障","Java")).get("id").toString());
        var input=new ConversationService.Send("合成问题",false,0L,0L,Map.of());
        UUID id=(UUID)service.send(p,w,UUID.randomUUID(),input).get("id");
        String constraint="test_save_"+UUID.randomUUID().toString().replace("-","");
        db.execute("alter table conversation_message add constraint "+constraint+" check (request_id <> '"+id+"'::uuid or role <> 'assistant') not valid");
        try{
            service.processPendingForTest(id);
            assertThat(service.get(p,w,id)).containsEntry("answer","保留的合成回答").containsEntry("record_status","SAVE_FAILED");
            assertThatThrownBy(()->service.send(p,w,UUID.randomUUID(),input)).isInstanceOf(ApiFailure.class);
        }finally{db.execute("alter table conversation_message drop constraint "+constraint);}
        assertThat(service.retryPersist(p,w,id)).containsEntry("record_status","SAVED").containsEntry("execution_status","SUCCEEDED");
        verify(gateway,times(1)).complete(anyString());
    }
    @Test void unknownRequiresAcknowledgementAndNeverAutomaticallyRetries(){
        when(gateway.configured()).thenReturn(true);when(gateway.model()).thenReturn("synthetic-model");
        when(gateway.complete(anyString())).thenThrow(new ModelGateway.Failure("MODEL_RESULT_UNKNOWN",true));
        UUID p=UUID.fromString(workspaces.createProfile(UUID.randomUUID(),new WorkspaceService.CreateProfile("未知结果测试")).get("id").toString());
        UUID w=UUID.fromString(workspaces.createWorkspace(p,UUID.randomUUID(),new WorkspaceService.CreateWorkspace("未知结果","Java")).get("id").toString());
        var input=new ConversationService.Send("合成问题",false,0L,0L,Map.of());
        UUID id=(UUID)service.send(p,w,UUID.randomUUID(),input).get("id");
        service.processPendingForTest(id);service.processPendingForTest(id);
        assertThat(service.get(p,w,id).get("execution_status")).isEqualTo("UNKNOWN");
        verify(gateway,times(1)).complete(anyString());
        assertThatThrownBy(()->service.send(p,w,UUID.randomUUID(),new ConversationService.Send("合成问题",false,0L,0L,Map.of(),1L,0L))).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code).isEqualTo("UNKNOWN_REQUIRES_ACK"));
        service.acknowledgeUnknown(p,w,id);
        UUID second=(UUID)service.send(p,w,UUID.randomUUID(),new ConversationService.Send("合成问题",false,0L,0L,Map.of(),1L,0L)).get("id");
        service.processPendingForTest(second);
        assertThat(second).isNotEqualTo(id);
    }
    @Test void capacityFailureDoesNotCreateTaskOrInvokeModel(){
        when(gateway.configured()).thenReturn(true);when(gateway.model()).thenReturn("synthetic-model");
        UUID p=UUID.fromString(workspaces.createProfile(UUID.randomUUID(),new WorkspaceService.CreateProfile("容量测试")).get("id").toString());
        UUID w=UUID.fromString(workspaces.createWorkspace(p,UUID.randomUUID(),new WorkspaceService.CreateWorkspace("容量","Java")).get("id").toString());
        workspaces.save(p,w,UUID.randomUUID(),new WorkspaceService.ResumeInput("a".repeat(200000),0L),true);
        assertThatThrownBy(()->service.send(p,w,UUID.randomUUID(),new ConversationService.Send("问题",true,1L,0L,Map.of())))
                .isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code).isEqualTo("CAPACITY_EXCEEDED"));
        assertThat(db.queryForObject("select count(*) from ai_request where workspace_id=?",Integer.class,w)).isZero();
        verify(gateway,never()).complete(anyString());
    }
    @Test void snapshotReplayAndSingleRequestAreProtected() throws Exception {
        when(gateway.configured()).thenReturn(true);when(gateway.model()).thenReturn("synthetic-model");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> sent=new java.util.concurrent.atomic.AtomicReference<>();
        when(gateway.complete(anyString())).thenAnswer(invocation->{sent.set(invocation.getArgument(0));entered.countDown();release.await(10,TimeUnit.SECONDS);return new ModelGateway.Result("合成回答","test-id",Map.of());});
        UUID p=UUID.fromString(workspaces.createProfile(UUID.randomUUID(),new WorkspaceService.CreateProfile("模型合成测试")).get("id").toString());
        UUID w=UUID.fromString(workspaces.createWorkspace(p,UUID.randomUUID(),new WorkspaceService.CreateWorkspace("问答","Java")).get("id").toString());
        workspaces.save(p,w,UUID.randomUUID(),new WorkspaceService.ResumeInput("固定简历原文",0L),true);
        UUID m=(UUID)materials.create(p,UUID.randomUUID(),new MaterialService.Create("选定材料","选定原文",null,false)).get("id");
        materials.create(p,UUID.randomUUID(),new MaterialService.Create("未选材料","不应外发的文字",null,false));
        materials.reference(p,w,m,UUID.randomUUID(),new MaterialService.Reference(0L),true);
        var body=new ConversationService.Send("问题",true,1L,1L,Map.of(m.toString(),1L));UUID key=UUID.randomUUID();
        UUID id=(UUID)service.send(p,w,key,body).get("id");
        var processing=CompletableFuture.runAsync(()->service.processPendingForTest(id));
        try{
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(service.send(p,w,key,body).get("id")).isEqualTo(id);
            assertThatThrownBy(()->service.send(p,w,UUID.randomUUID(),body)).isInstanceOf(ApiFailure.class);
            materials.edit(p,m,UUID.randomUUID(),new MaterialService.Edit(null,"后续修改",1L));
            assertThat(sent.get()).contains("固定简历原文","选定原文").doesNotContain("不应外发的文字","后续修改");
        }finally{release.countDown();}
        processing.get(10,TimeUnit.SECONDS);
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end&&!"SUCCEEDED".equals(service.get(p,w,id).get("execution_status")))Thread.sleep(50);
        assertThat(service.get(p,w,id)).containsEntry("execution_status","SUCCEEDED").containsEntry("answer","合成回答");
        verify(gateway,times(1)).complete(anyString());
        assertThatThrownBy(()->service.send(p,w,UUID.randomUUID(),new ConversationService.Send("问题",true,1L,1L,Map.of(m.toString(),1L),2L,0L))).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code).isEqualTo("VERSION_CONFLICT"));
    }
}