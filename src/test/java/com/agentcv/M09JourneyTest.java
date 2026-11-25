package com.agentcv;

import com.agentcv.background.*;
import com.agentcv.conversation.*;
import com.agentcv.material.MaterialService;
import com.agentcv.workspace.WorkspaceService;
import com.agentcv.model.ModelGateway;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** One continuous synthetic journey; real database, model double, not paid-model evidence. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="AGENTCV_M00_DB_PASSWORD",matches=".+")
class M09JourneyTest {
    @Autowired WorkspaceService workspaces;
    @Autowired MaterialService materials;
    @Autowired BackgroundService backgrounds;
    @Autowired BackgroundChangeService changes;
    @Autowired ConversationService conversation;
    @Autowired ConversationStateService state;
    @Autowired JdbcTemplate db;
    @MockBean ModelGateway gateway;
    UUID area(UUID p,String name){return UUID.fromString(workspaces.createWorkspace(p,UUID.randomUUID(),new WorkspaceService.CreateWorkspace(name,"Java")).get("id").toString());}
    UUID send(UUID p,UUID w,boolean resume,long version,long scope,Map<String,Long> refs){var s=state.state(p,w);return (UUID)conversation.send(p,w,UUID.randomUUID(),new ConversationService.Send("合成任务：核对职责并建议简历表达",resume,version,scope,refs,((Number)s.get("version")).longValue(),((Number)s.get("epoch")).longValue())).get("id");}
    @Test void resumeMaterialsConfirmedBackgroundReuseAndWithdrawalRemainIndependent(){
        when(gateway.configured()).thenReturn(true);when(gateway.model()).thenReturn("m09-double");
        when(gateway.complete(anyString())).thenReturn(new ModelGateway.Result("{\"answer\":\"请核对\",\"background_candidates\":[\"合成项目A仅协助部署\"]}","test",Map.of()));
        UUID p=UUID.fromString(workspaces.createProfile(UUID.randomUUID(),new WorkspaceService.CreateProfile("最终合成人物")).get("id").toString());
        UUID first=area(p,"通用版"),second=area(p,"目标版");
        workspaces.save(p,first,UUID.randomUUID(),new WorkspaceService.ResumeInput("合成简历：参与接口开发",0L),true);
        workspaces.establish(p,second,UUID.randomUUID(),new WorkspaceService.EstablishInput(null,0L,null,first,1L));
        UUID m=(UUID)materials.create(p,UUID.randomUUID(),new MaterialService.Create("合成补充","部署只是协助",null,false)).get("id");
        materials.reference(p,first,m,UUID.randomUUID(),new MaterialService.Reference(0L),true);
        UUID request=send(p,first,true,1,1,Map.of(m.toString(),1L));conversation.processPendingForTest(request);
        assertThat(backgrounds.rows(p)).isEmpty();var candidate=backgrounds.pending(p,first);
        backgrounds.confirm(p,(UUID)candidate.get("id"),UUID.randomUUID(),new BackgroundService.Confirm(first,1L,(String)candidate.get("display_text"),"确认保存全部"));
        materials.reference(p,first,m,UUID.randomUUID(),new MaterialService.Reference(1L),false);
        assertThat(backgrounds.rows(p)).hasSize(1);
        when(gateway.complete(anyString())).thenReturn(new ModelGateway.Result("建议明确协助范围，不编造数字。","test",Map.of()));
        UUID reused=send(p,second,true,1,0,Map.of());
        assertThat(db.queryForObject("select input_json from ai_request where id=?",String.class,reused)).contains("合成项目A仅协助部署");conversation.processPendingForTest(reused);
        workspaces.save(p,second,UUID.randomUUID(),new WorkspaceService.ResumeInput("人工采纳建议：参与接口开发，协助部署",1L),false);
        assertThat(workspaces.resume(p,first).get("text")).isEqualTo("合成简历：参与接口开发");
        var background=backgrounds.rows(p).get(0);
        var proposal=changes.prepare(p,(UUID)background.get("id"),UUID.randomUUID(),new BackgroundChangeService.Prepare(second,1L,(String)background.get("text"),null,"WITHDRAW"));
        changes.confirm(p,(UUID)proposal.get("id"),UUID.randomUUID(),new BackgroundChangeService.Confirm(second,1L,(String)background.get("text"),null,"WITHDRAW"));
        state.saveDraft(p,second,UUID.randomUUID(),new ConversationStateService.Draft("下一条草稿",0L,0L));
        var current=state.state(p,second);
        state.reset(p,second,UUID.randomUUID(),new ConversationStateService.Reset(((Number)current.get("version")).longValue(),0L),true);
        assertThat(state.draft(p,second).get("text")).isEqualTo("");assertThat(backgrounds.rows(p)).isEmpty();
        UUID after=send(p,second,false,2,0,Map.of());String snapshot=db.queryForObject("select input_json from ai_request where id=?",String.class,after);
        assertThat(snapshot).contains("WITHDRAW","旧记录不得继续当作当前事实").doesNotContain("建议明确协助范围");conversation.processPendingForTest(after);
        assertThat(workspaces.resume(p,second).get("text")).isEqualTo("人工采纳建议：参与接口开发，协助部署");
        assertThat(materials.get(p,m).get("text")).isEqualTo("部署只是协助");verify(gateway,times(3)).complete(anyString());
    }
}