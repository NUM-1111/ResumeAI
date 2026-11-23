package com.agentcv;

import com.agentcv.conversation.*;
import com.agentcv.workspace.WorkspaceService;
import com.agentcv.model.ModelGateway;
import com.agentcv.api.ApiFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@EnabledIfEnvironmentVariable(named="AGENTCV_M00_DB_PASSWORD",matches=".+")
class M05IntegrationTest {
    @Autowired ConversationService service;
    @Autowired ConversationStateService state;
    @Autowired WorkspaceService workspaces;
    @Autowired JdbcTemplate db;
    @MockBean ModelGateway gateway;
    UUID person(){return UUID.fromString(workspaces.createProfile(UUID.randomUUID(),new WorkspaceService.CreateProfile("M05合成")).get("id").toString());}
    UUID workspace(UUID p){return UUID.fromString(workspaces.createWorkspace(p,UUID.randomUUID(),new WorkspaceService.CreateWorkspace("持续对话","Java")).get("id").toString());}
    ConversationService.Send input(UUID p,UUID w,String message){var current=state.state(p,w);return new ConversationService.Send(message,false,0L,0L,Map.of(),((Number)current.get("version")).longValue(),((Number)current.get("epoch")).longValue());}
    void model(){when(gateway.configured()).thenReturn(true);when(gateway.model()).thenReturn("test");when(gateway.complete(anyString())).thenReturn(new ModelGateway.Result("前轮合成回答","test",Map.of()));}
    @Test void historyBoundaryAndClearHaveDifferentEffects(){
        model();UUID p=person(),w=workspace(p);
        UUID first=(UUID)service.send(p,w,UUID.randomUUID(),input(p,w,"前轮合成问题")).get("id");service.processPendingForTest(first);
        UUID second=(UUID)service.send(p,w,UUID.randomUUID(),input(p,w,"后续问题")).get("id");
        assertThat(db.queryForObject("select input_json from ai_request where id=?",String.class,second)).contains("前轮合成问题","前轮合成回答");
        assertThatThrownBy(()->state.reset(p,w,UUID.randomUUID(),new ConversationStateService.Reset(3L,0L),false)).isInstanceOf(ApiFailure.class);
        service.processPendingForTest(second);
        state.saveDraft(p,w,UUID.randomUUID(),new ConversationStateService.Draft("保留的草稿",0L,0L));
        var current=state.state(p,w);
        state.reset(p,w,UUID.randomUUID(),new ConversationStateService.Reset(((Number)current.get("version")).longValue(),0L),false);
        assertThat(state.draft(p,w).get("text")).isEqualTo("保留的草稿");
        UUID third=(UUID)service.send(p,w,UUID.randomUUID(),input(p,w,"分隔后的问题")).get("id");
        assertThat(db.queryForObject("select input_json from ai_request where id=?",String.class,third)).doesNotContain("前轮合成问题","前轮合成回答","后续问题");
        service.processPendingForTest(third);
        current=state.state(p,w);
        state.reset(p,w,UUID.randomUUID(),new ConversationStateService.Reset(((Number)current.get("version")).longValue(),1L),true);
        assertThat((List<?>)service.conversation(p,w).get("requests")).isEmpty();
        assertThat(state.draft(p,w).get("text")).isEqualTo("");
        assertThatThrownBy(()->service.get(p,w,first)).isInstanceOf(ApiFailure.class);
        assertThat(db.queryForObject("select count(*) from conversation_message where workspace_id=?",Integer.class,w)).isZero();
        assertThat(db.queryForObject("select count(*) from ai_request where workspace_id=? and (message<>'' or answer is not null or input_json<>'')",Integer.class,w)).isZero();
    }
    @Test void draftsAreIsolatedAndStaleWritesCannotReviveClearedText(){
        UUID p=person(),w=workspace(p),other=workspace(p),key=UUID.randomUUID();
        var body=new ConversationStateService.Draft("独立输入",0L,0L);
        state.saveDraft(p,w,key,body);state.saveDraft(p,w,key,body);
        assertThat(state.draft(p,other).get("text")).isEqualTo("");
        assertThatThrownBy(()->state.saveDraft(p,w,UUID.randomUUID(),new ConversationStateService.Draft("过期",0L,0L))).isInstanceOf(ApiFailure.class);
        state.reset(p,w,UUID.randomUUID(),new ConversationStateService.Reset(0L,0L),true);
        assertThatThrownBy(()->state.saveDraft(p,w,UUID.randomUUID(),new ConversationStateService.Draft("旧意图",2L,0L))).isInstanceOf(ApiFailure.class);
        assertThat(state.draft(p,w).get("text")).isEqualTo("");
    }
    @Test void staleConversationVersionRejectsWithoutAnotherModelCall(){
        model();UUID p=person(),w=workspace(p);var old=input(p,w,"初次问题");
        UUID first=(UUID)service.send(p,w,UUID.randomUUID(),old).get("id");service.processPendingForTest(first);
        assertThatThrownBy(()->service.send(p,w,UUID.randomUUID(),old)).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code).isEqualTo("CONVERSATION_CHANGED"));
        verify(gateway,times(1)).complete(anyString());
    }
    @Test void historyBudgetKeepsRecentCompletePairsAndDoesNotTruncateOlderTurn(){
        model();UUID p=person(),w=workspace(p);
        UUID first=(UUID)service.send(p,w,UUID.randomUUID(),input(p,w,"旧轮问题")).get("id");
        service.processPendingForTest(first);
        db.update("update ai_request set answer=? where id=?","旧轮超长回答"+"x".repeat(196608),first);
        UUID second=(UUID)service.send(p,w,UUID.randomUUID(),input(p,w,"近期完整问题")).get("id");
        service.processPendingForTest(second);
        UUID third=(UUID)service.send(p,w,UUID.randomUUID(),input(p,w,"当前问题")).get("id");
        String snapshot=db.queryForObject("select input_json from ai_request where id=?",String.class,third);
        assertThat(snapshot).contains("近期完整问题","前轮合成回答","当前问题").doesNotContain("旧轮问题","旧轮超长回答");
        service.processPendingForTest(third);
    }
}