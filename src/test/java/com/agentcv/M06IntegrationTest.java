package com.agentcv;

import com.agentcv.background.BackgroundService;
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
class M06IntegrationTest {
    @Autowired BackgroundService backgrounds;
    @Autowired ConversationService conversation;
    @Autowired ConversationStateService state;
    @Autowired WorkspaceService workspaces;
    @Autowired JdbcTemplate db;
    @MockBean ModelGateway gateway;
    UUID person(){return UUID.fromString(workspaces.createProfile(UUID.randomUUID(),new WorkspaceService.CreateProfile("M06合成")).get("id").toString());}
    UUID workspace(UUID p){return UUID.fromString(workspaces.createWorkspace(p,UUID.randomUUID(),new WorkspaceService.CreateWorkspace("背景验证","Java")).get("id").toString());}
    UUID send(UUID p,UUID w,String text){var s=state.state(p,w);return (UUID)conversation.send(p,w,UUID.randomUUID(),new ConversationService.Send(text,false,0L,0L,Map.of(),((Number)s.get("version")).longValue(),((Number)s.get("epoch")).longValue())).get("id");}
    Map<String,Object> candidate(UUID p,UUID w){
        when(gateway.configured()).thenReturn(true);when(gateway.model()).thenReturn("test");
        when(gateway.complete(anyString())).thenReturn(new ModelGateway.Result("{\"answer\":\"请核对职责\",\"background_candidates\":[\"项目A中协助部署，非独立负责。\",\"项目A中开发订单接口。\"]}","test",Map.of()));
        UUID r=send(p,w,"请整理经历：协助部署，开发订单接口");conversation.processPendingForTest(r);
        return backgrounds.pending(p,w);
    }
    BackgroundService.Confirm confirm(UUID w,Map<String,Object> c,String text){return new BackgroundService.Confirm(w,((Number)c.get("version")).longValue(),(String)c.get("display_text"),text);}
    @Test void exactPartialConfirmationIsIdempotentAndReusedAcrossSamePersonOnly(){
        UUID p=person(),w=workspace(p);var c=candidate(p,w);UUID id=(UUID)c.get("id"),key=UUID.randomUUID();
        assertThat((List<?>)backgrounds.list(p).get("items")).isEmpty();
        var body=confirm(w,c,"只确认第 1 项并保存");backgrounds.confirm(p,id,key,body);backgrounds.confirm(p,id,key,body);
        backgrounds.confirm(p,id,UUID.randomUUID(),body);
        assertThat(backgrounds.rows(p)).hasSize(1);assertThat(backgrounds.rows(p).get(0).get("text")).isEqualTo("项目A中协助部署，非独立负责。");
        assertThatThrownBy(()->backgrounds.confirm(p,id,UUID.randomUUID(),confirm(w,c,"确认以上全部并保存"))).isInstanceOf(ApiFailure.class);
        UUID next=workspace(p),r=send(p,next,"给我建议");
        assertThat(db.queryForObject("select input_json from ai_request where id=?",String.class,r)).contains("项目A中协助部署，非独立负责。").doesNotContain("项目A中开发订单接口。");
        conversation.processPendingForTest(r);
        UUID other=person(),otherW=workspace(other),otherR=send(other,otherW,"普通问题");
        assertThat(db.queryForObject("select input_json from ai_request where id=?",String.class,otherR)).doesNotContain("项目A中协助部署");conversation.processPendingForTest(otherR);
        assertThatThrownBy(()->backgrounds.confirm(other,id,UUID.randomUUID(),confirm(otherW,c,"确认以上全部并保存"))).isInstanceOf(ApiFailure.class);
    }
    @Test void vagueRefusedHypotheticalAndUnshownContentNeverWrite(){
        UUID p=person(),w=workspace(p);var c=candidate(p,w);UUID id=(UUID)c.get("id");
        for(String text:List.of("可以吧","如果属实就保存","先不保存","确认第一项，但改为独立负责部署","只确认第 3 项并保存","确认以上全部并保存，另外管理五人"))
            assertThatThrownBy(()->backgrounds.confirm(p,id,UUID.randomUUID(),confirm(w,c,text))).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(()->backgrounds.confirm(p,id,UUID.randomUUID(),new BackgroundService.Confirm(w,1L,"独立负责部署", "确认以上全部并保存"))).isInstanceOf(ApiFailure.class);
        assertThat(backgrounds.rows(p)).isEmpty();
        state.reset(p,w,UUID.randomUUID(),new ConversationStateService.Reset(((Number)state.state(p,w).get("version")).longValue(),0L),false);
        assertThatThrownBy(()->backgrounds.confirm(p,id,UUID.randomUUID(),confirm(w,c,"确认以上全部并保存"))).isInstanceOf(ApiFailure.class);
    }
    @Test void databaseFailureRetriesOnlyConfirmationAndOldCandidateExpiresAfterNewDialogue(){
        UUID p=person(),w=workspace(p);var c=candidate(p,w);UUID id=(UUID)c.get("id"),key=UUID.randomUUID();var body=confirm(w,c,"确认以上全部并保存");
        String constraint="m06_fail_"+UUID.randomUUID().toString().replace("-","");
        db.execute("alter table background add constraint "+constraint+" check (profile_id <> '"+p+"'::uuid) not valid");
        try{assertThatThrownBy(()->backgrounds.confirm(p,id,key,body)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(backgrounds.rows(p)).isEmpty();}
        finally{db.execute("alter table background drop constraint "+constraint);}
        backgrounds.confirm(p,id,key,body);assertThat(backgrounds.rows(p)).hasSize(2);verify(gateway,times(1)).complete(anyString());
        var fresh=candidate(p,w);UUID newRequest=send(p,w,"先不保存，继续讨论");conversation.processPendingForTest(newRequest);
        assertThatThrownBy(()->backgrounds.confirm(p,(UUID)fresh.get("id"),UUID.randomUUID(),confirm(w,fresh,"确认以上全部并保存"))).isInstanceOf(ApiFailure.class);
    }
    @Test void malformedOutputNeverCreatesCandidateAndClearKeepsConfirmedBackground(){
        assertThat(backgrounds.decode("{\"answer\":\"普通回答\",\"background_candidates\":[{\"text\":\"不可信格式\"}]}").items()).isEmpty();
        assertThat(backgrounds.decode("{\"answer\":\"截断").items()).isEmpty();
        UUID p=person(),w=workspace(p);var c=candidate(p,w);
        backgrounds.confirm(p,(UUID)c.get("id"),UUID.randomUUID(),confirm(w,c,"确认以上全部并保存"));
        var current=state.state(p,w);
        state.reset(p,w,UUID.randomUUID(),new ConversationStateService.Reset(((Number)current.get("version")).longValue(),0L),true);
        assertThat(backgrounds.rows(p)).hasSize(2);assertThat(backgrounds.pending(p,w)).isEmpty();
        UUID r=send(p,w,"清空后继续");
        assertThat(db.queryForObject("select input_json from ai_request where id=?",String.class,r)).contains("项目A中协助部署，非独立负责。","项目A中开发订单接口。");
        conversation.processPendingForTest(r);
    }
    @Test void naturalConfirmationAndConciseCandidateDisplay(){
        for(String text:List.of("仅保留第一项作为背景","确认仅保留第一项","只确认第 1 项并保存"))
            assertThat(backgrounds.selection(text,2)).containsExactly(1);
        for(String text:List.of("确认","确认保存","确认并保存","确认保存全部","确认保存全部。","确认全部保存","确认全部并保存"))assertThat(backgrounds.selection(text,2)).containsExactly(1,2);
        var output=new BackgroundService.Output("待保存到当前人物的个人背景：重复确认说明",List.of("协助部署"));
        assertThat(backgrounds.display(output)).containsOnlyOnce("协助部署").doesNotContain("重复确认说明");
        UUID p=person(),w=workspace(p);var c=candidate(p,w);
        backgrounds.confirm(p,(UUID)c.get("id"),UUID.randomUUID(),confirm(w,c,"仅保留第一项作为背景"));
        assertThat(backgrounds.rows(p)).hasSize(1);verify(gateway,times(1)).complete(anyString());
    }
    @Test void advertisedSaveAllPhrasePersistsBothItemsWithoutAnotherModelCall(){
        UUID p=person(),w=workspace(p);var c=candidate(p,w);
        UUID key=UUID.randomUUID();var body=confirm(w,c,"确认保存全部");
        var result=backgrounds.confirm(p,(UUID)c.get("id"),key,body);
        backgrounds.confirm(p,(UUID)c.get("id"),key,body);
        assertThat(result.get("saved")).isEqualTo(true);
        assertThat(backgrounds.rows(p)).extracting(row->row.get("text")).containsExactlyInAnyOrder("项目A中协助部署，非独立负责。","项目A中开发订单接口。");
        assertThat(backgrounds.pending(p,w)).isEmpty();verify(gateway,times(1)).complete(anyString());
    }
    @Test void proseWrappedCandidatesCanBeConfirmedWithoutLeakingProtocol() {
        UUID p=person(),w=workspace(p);candidate(p,w);
        when(gateway.complete(anyString())).thenReturn(new ModelGateway.Result("以下重新提交，请核对。\n```json\n{\"answer\":\"重新提交\",\"background_candidates\":[\"计划由本人制定，编码由AI辅助\",\"框架来自自学\"]}\n```\n请确认。","test",Map.of()));
        UUID r=send(p,w,"重新提交");conversation.processPendingForTest(r);
        var shown=conversation.get(p,w,r).get("answer").toString();
        assertThat(shown).contains("共 2 项","计划由本人制定").doesNotContain("background_candidates","\"answer\"");
        var c=backgrounds.pending(p,w);assertThat(c).isNotEmpty();
        backgrounds.confirm(p,(UUID)c.get("id"),UUID.randomUUID(),confirm(w,c,"全部确认"));
        assertThat(backgrounds.rows(p)).hasSize(2);verify(gateway,times(2)).complete(anyString());
    }
    @Test void ambiguousTruncatedAndEscapedProtocolIsHandledSafely(){
        String valid="{\"answer\":\"候选\",\"background_candidates\":[\"项目中使用{a}及\\\"引号\\\"\"]}";
        assertThat(backgrounds.decode("说明\n"+valid).items()).containsExactly("项目中使用{a}及\"引号\"");
        for(String raw:List.of(valid+"\n"+valid,"说明 {\"answer\":\"截断\",\"background_candidates\":[")){
            var output=backgrounds.decode(raw);assertThat(output.items()).isEmpty();
            assertThat(backgrounds.display(output)).contains("尚未保存").doesNotContain("background_candidates","\"answer\"");
        }
        assertThat(backgrounds.decode("普通回答，不包含协议").answer()).isEqualTo("普通回答，不包含协议");
    }
    @Test void clearingUnacknowledgedUnknownAllowsFreshSend(){
        UUID p=person(),w=workspace(p);candidate(p,w);
        when(gateway.complete(anyString())).thenThrow(new ModelGateway.Failure("MODEL_RESULT_UNKNOWN",true));
        UUID old=send(p,w,"你好");conversation.processPendingForTest(old);
        assertThatThrownBy(()->send(p,w,"新问题")).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code).isEqualTo("UNKNOWN_REQUIRES_ACK"));
        var current=state.state(p,w);
        state.reset(p,w,UUID.randomUUID(),new ConversationStateService.Reset(((Number)current.get("version")).longValue(),0L),true);
        UUID next=send(p,w,"清空后新问题");assertThat(next).isNotEqualTo(old);conversation.processPendingForTest(next);
    }
    @Test void stopWaitingFencesLateResultAndDoesNotBlockNewRequest() throws Exception {
        UUID p=person(),w=workspace(p);candidate(p,w);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        when(gateway.complete(anyString())).thenAnswer(invocation->{
            entered.countDown();boolean done=false;
            while(!done)try{done=release.await(2,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ignored){}
            return new ModelGateway.Result("迟到回答不能写入","late",Map.of());
        });
        UUID old=send(p,w,"长时间问题");
        var execution=java.util.concurrent.CompletableFuture.runAsync(()->conversation.processPendingForTest(old));
        try{
            assertThat(entered.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            conversation.stopWaiting(p,w,old);conversation.stopWaiting(p,w,old);
            UUID next=send(p,w,"新的问题");assertThat(next).isNotEqualTo(old);
        }finally{release.countDown();execution.get(5,java.util.concurrent.TimeUnit.SECONDS);}
        var result=conversation.get(p,w,old);
        assertThat(result.get("answer")).isNull();assertThat(result.get("record_status")).isEqualTo("SAVED");
        assertThat(result.get("error_code")).isEqualTo("UNKNOWN_ACKNOWLEDGED");
        assertThat(db.queryForObject("select count(*) from conversation_message where request_id=? and role='assistant'",Integer.class,old)).isZero();
        // Finish the new accepted request so it cannot affect later test capacity.
        UUID next=db.queryForObject("select id from ai_request where workspace_id=? and execution_status='ACCEPTED'",UUID.class,w);
        conversation.processPendingForTest(next);
    }
}