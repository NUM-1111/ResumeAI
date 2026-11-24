package com.agentcv;

import com.agentcv.background.*;
import com.agentcv.conversation.*;
import com.agentcv.workspace.WorkspaceService;
import com.agentcv.model.ModelGateway;
import com.agentcv.api.ApiFailure;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@EnabledIfEnvironmentVariable(named="AGENTCV_M00_DB_PASSWORD",matches=".+")
class M07IntegrationTest {
    @Autowired BackgroundService backgrounds;
    @Autowired BackgroundChangeService changes;
    @Autowired ConversationService conversation;
    @Autowired ConversationStateService state;
    @Autowired WorkspaceService workspaces;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @MockBean ModelGateway gateway;
    UUID person(){return UUID.fromString(workspaces.createProfile(UUID.randomUUID(),new WorkspaceService.CreateProfile("M07合成")).get("id").toString());}
    UUID workspace(UUID p){return UUID.fromString(workspaces.createWorkspace(p,UUID.randomUUID(),new WorkspaceService.CreateWorkspace("背景管理","Java")).get("id").toString());}
    UUID send(UUID p,UUID w,String text){var s=state.state(p,w);return (UUID)conversation.send(p,w,UUID.randomUUID(),new ConversationService.Send(text,false,0L,0L,Map.of(),((Number)s.get("version")).longValue(),((Number)s.get("epoch")).longValue())).get("id");}
    Map<String,Object> seed(UUID p,UUID w){
        when(gateway.configured()).thenReturn(true);when(gateway.model()).thenReturn("test-m07");
        when(gateway.complete(anyString())).thenReturn(new ModelGateway.Result("{\"answer\":\"请核对\",\"background_candidates\":[\"项目A负责部署\"]}","test",Map.of()));
        UUID r=send(p,w,"合成经历");conversation.processPendingForTest(r);var c=backgrounds.pending(p,w);
        backgrounds.confirm(p,(UUID)c.get("id"),UUID.randomUUID(),new BackgroundService.Confirm(w,1L,(String)c.get("display_text"),"确认保存全部"));return backgrounds.rows(p).get(0);
    }
    BackgroundChangeService.Confirm confirmation(UUID w,Map<String,Object> c){return new BackgroundChangeService.Confirm(w,((Number)c.get("expected_version")).longValue(),(String)c.get("old_text"),(String)c.get("new_text"),(String)c.get("kind"));}
    Map<String,Object> prepare(UUID p,UUID w,Map<String,Object> b,String kind,String text){return changes.prepare(p,(UUID)b.get("id"),UUID.randomUUID(),new BackgroundChangeService.Prepare(w,((Number)b.get("version")).longValue(),(String)b.get("text"),text,kind));}
    @Test void correctWithdrawAndOldRetryCannotRestoreBackground(){
        UUID p=person(),w=workspace(p);var b=seed(p,w);var correction=prepare(p,w,b,"CORRECT","项目A仅协助部署");
        assertThat(backgrounds.rows(p).get(0).get("text")).isEqualTo("项目A负责部署");
        UUID key=UUID.randomUUID();changes.confirm(p,(UUID)correction.get("id"),key,confirmation(w,correction));
        changes.confirm(p,(UUID)correction.get("id"),key,confirmation(w,correction));
        var latest=backgrounds.rows(p).get(0);assertThat(latest.get("text")).isEqualTo("项目A仅协助部署");assertThat(((Number)latest.get("version")).longValue()).isEqualTo(2);
        var withdrawal=prepare(p,w,latest,"WITHDRAW",null);changes.confirm(p,(UUID)withdrawal.get("id"),UUID.randomUUID(),confirmation(w,withdrawal));
        changes.confirm(p,(UUID)correction.get("id"),UUID.randomUUID(),confirmation(w,correction));
        assertThat(backgrounds.rows(p)).isEmpty();assertThat(changes.events(p)).hasSize(2);
        assertThatThrownBy(()->prepare(p,w,b,"CORRECT","旧页面覆盖")).isInstanceOf(ApiFailure.class);
    }
    @Test void concurrentPagesAndWrongPersonAreRejectedAndCancelDoesNotWrite(){
        UUID p=person(),w=workspace(p),otherW=workspace(p);var b=seed(p,w);
        var first=prepare(p,w,b,"CORRECT","协助部署");var second=prepare(p,otherW,b,"WITHDRAW",null);
        changes.confirm(p,(UUID)second.get("id"),UUID.randomUUID(),confirmation(otherW,second));
        assertThatThrownBy(()->changes.confirm(p,(UUID)first.get("id"),UUID.randomUUID(),confirmation(w,first))).isInstanceOf(ApiFailure.class);
        UUID other=person(),foreignW=workspace(other);
        assertThatThrownBy(()->prepare(other,foreignW,b,"WITHDRAW",null)).isInstanceOf(ApiFailure.class);
        var next=seed(p,w);var cancelled=prepare(p,w,next,"WITHDRAW",null);changes.dismiss(p,(UUID)cancelled.get("id"),UUID.randomUUID(),w);
        assertThatThrownBy(()->changes.confirm(p,(UUID)cancelled.get("id"),UUID.randomUUID(),confirmation(w,cancelled))).isInstanceOf(ApiFailure.class);
        assertThat(backgrounds.rows(p)).hasSize(1);
    }
    @Test void inFlightSnapshotRemainsOldNextRequestIncludesChanges() throws Exception {
        UUID p=person(),w=workspace(p);var b=seed(p,w);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(gateway.complete(anyString())).thenAnswer(invocation->{entered.countDown();release.await(5,TimeUnit.SECONDS);return new ModelGateway.Result("旧依据回答","test",Map.of());});
        UUID r=send(p,w,"给建议");String original=db.queryForObject("select input_json from ai_request where id=?",String.class,r);
        var run=CompletableFuture.runAsync(()->conversation.processPendingForTest(r));
        try{assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();var c=prepare(p,w,b,"CORRECT","项目A仅协助部署");changes.confirm(p,(UUID)c.get("id"),UUID.randomUUID(),confirmation(w,c));
            assertThat(db.queryForObject("select input_json from ai_request where id=?",String.class,r)).isEqualTo(original);
            assertThat(conversation.get(p,w,r).get("basis_changed")).isEqualTo(true);
        }finally{release.countDown();run.get(5,TimeUnit.SECONDS);}
        UUID next=send(p,w,"使用最新背景");String snapshot=db.queryForObject("select input_json from ai_request where id=?",String.class,next);
        assertThat(snapshot).contains("项目A仅协助部署","背景已生效变更","CORRECT","旧记录不得继续当作当前事实");conversation.processPendingForTest(next);
        verify(gateway,times(3)).complete(anyString());
    }
    @Test void dialogueProposalNeedsExactVersionAndIndependentConfirmation() throws Exception {
        UUID p=person(),w=workspace(p);var b=seed(p,w);
        var action=new BackgroundService.Action((UUID)b.get("id"),1L,(String)b.get("text"),null,"WITHDRAW");
        String response=json.writeValueAsString(Map.of("answer","撤回提议","background_candidates",List.of(),"background_action",action));
        when(gateway.complete(anyString())).thenReturn(new ModelGateway.Result(response,"test",Map.of()));
        UUID r=send(p,w,"删除部署这条背景");conversation.processPendingForTest(r);
        assertThat(backgrounds.rows(p)).hasSize(1);var proposal=changes.pending(p,w);assertThat(proposal).isNotEmpty();
        changes.confirm(p,(UUID)proposal.get("id"),UUID.randomUUID(),confirmation(w,proposal));assertThat(backgrounds.rows(p)).isEmpty();
    }
    @Test void saveFailureRetriesDatabaseOnly() {
        UUID p=person(),w=workspace(p);var b=seed(p,w);var c=prepare(p,w,b,"WITHDRAW",null);UUID key=UUID.randomUUID();
        String constraint="m07_fail_"+UUID.randomUUID().toString().replace("-","");
        db.execute("alter table background add constraint "+constraint+" check(active) not valid");
        try{assertThatThrownBy(()->changes.confirm(p,(UUID)c.get("id"),key,confirmation(w,c))).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(backgrounds.rows(p)).hasSize(1);}
        finally{db.execute("alter table background drop constraint "+constraint);}
        changes.confirm(p,(UUID)c.get("id"),key,confirmation(w,c));assertThat(backgrounds.rows(p)).isEmpty();verify(gateway,times(1)).complete(anyString());
    }
}