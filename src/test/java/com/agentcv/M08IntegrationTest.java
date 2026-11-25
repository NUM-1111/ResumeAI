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
class M08IntegrationTest {
    @Autowired ConversationService service;
    @Autowired ConversationStateService state;
    @Autowired WorkspaceService workspaces;
    @Autowired RequestRecovery recovery;
    @Autowired JdbcTemplate db;
    @MockBean ModelGateway gateway;
    UUID person(){return UUID.fromString(workspaces.createProfile(UUID.randomUUID(),new WorkspaceService.CreateProfile("M08合成")).get("id").toString());}
    UUID workspace(UUID p){return UUID.fromString(workspaces.createWorkspace(p,UUID.randomUUID(),new WorkspaceService.CreateWorkspace("故障恢复","Java")).get("id").toString());}
    ConversationService.Send input(UUID p,UUID w){var s=state.state(p,w);return new ConversationService.Send("合成恢复问题",false,0L,0L,Map.of(),((Number)s.get("version")).longValue(),((Number)s.get("epoch")).longValue());}
    void model(){when(gateway.configured()).thenReturn(true);when(gateway.model()).thenReturn("m08-test");when(gateway.complete(anyString())).thenReturn(new ModelGateway.Result("合成恢复回答","test",Map.of()));}
    @Test void startupRecoveryPreservesUnsentAndNeverRedispatchesPossiblySent() throws Exception {
        model();UUID p=person(),w=workspace(p),other=workspace(p),lostW=workspace(p);
        UUID key=UUID.randomUUID();var body=input(p,w);UUID accepted=(UUID)service.send(p,w,key,body).get("id");
        UUID calling=(UUID)service.send(p,other,UUID.randomUUID(),input(p,other)).get("id");
        UUID lost=(UUID)service.send(p,lostW,UUID.randomUUID(),input(p,lostW)).get("id");
        db.update("update ai_request set execution_status='CALLING' where id=?",calling);
        db.update("update ai_request set execution_status='CALLING',record_status='SAVE_FAILED' where id=?",lost);
        try(var guard=new WorkerGuard(java.nio.file.Files.createTempDirectory("m08-recovery").resolve("worker.lock"))){recovery.recover();}
        assertThat(service.get(p,other,calling)).containsEntry("execution_status","UNKNOWN").containsEntry("record_status","SAVED");
        assertThat(service.get(p,lostW,lost)).containsEntry("error_code","RESULT_LOST_ON_RESTART");
        assertThat(db.queryForObject("select input_json from ai_request where id=?",String.class,calling)).isEmpty();
        service.processPendingForTest(calling);service.processPendingForTest(lost);verify(gateway,never()).complete(anyString());
        assertThat(service.get(p,w,accepted).get("execution_status")).isEqualTo("ACCEPTED");
        assertThat(service.send(p,w,key,body).get("id")).isEqualTo(accepted);
        service.processPendingForTest(accepted);service.processPendingForTest(accepted);verify(gateway,times(1)).complete(anyString());
        assertThat(service.get(p,w,accepted).get("answer")).isEqualTo("合成恢复回答");
    }
    @Test void failureDiagnosticsPersistWithoutReplayingModel(){
        model();UUID p=person(),w=workspace(p);
        var failure=new ModelGateway.Failure("MODEL_RESPONSE_TIMEOUT",true);
        failure.diagnostics=Map.of("stage","KEEPALIVE_ONLY","http_status",200,"bytes_received",12L);
        when(gateway.complete(anyString())).thenThrow(failure);
        UUID id=(UUID)service.send(p,w,UUID.randomUUID(),input(p,w)).get("id");
        service.processPendingForTest(id);service.processPendingForTest(id);
        assertThat(service.get(p,w,id)).containsEntry("execution_status","UNKNOWN").containsEntry("error_code","MODEL_RESPONSE_TIMEOUT");
        assertThat(db.queryForObject("select diagnostics_json from ai_request where id=?",String.class,id)).contains("KEEPALIVE_ONLY","http_status","bytes_received");
        assertThat(db.queryForObject("select input_json from ai_request where id=?",String.class,id)).isEmpty();
        verify(gateway,times(1)).complete(anyString());
    }
    @Test void globalAdmissionIsBoundedAndRejectedDraftIsPreserved(){
        model();UUID p=person();List<UUID> requests=new ArrayList<>(),areas=new ArrayList<>();
        try{
            for(int i=0;i<4;i++){UUID w=workspace(p);areas.add(w);requests.add((UUID)service.send(p,w,UUID.randomUUID(),input(p,w)).get("id"));}
            UUID blocked=workspace(p);state.saveDraft(p,blocked,UUID.randomUUID(),new ConversationStateService.Draft("受限后保留草稿",0L,0L));
            assertThatThrownBy(()->service.send(p,blocked,UUID.randomUUID(),input(p,blocked))).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code).isEqualTo("REQUEST_LIMIT"));
            assertThat(state.draft(p,blocked).get("text")).isEqualTo("受限后保留草稿");
            assertThat(db.queryForObject("select count(*) from ai_request where workspace_id=?",Integer.class,blocked)).isZero();verify(gateway,never()).complete(anyString());
        }finally{for(int i=0;i<requests.size();i++)service.stopWaiting(p,areas.get(i),requests.get(i));}
    }
    @Test void cleanupClearsOnlyExpiredTerminalSnapshotsAndRetainsReplayTombstones(){
        model();UUID p=person(),w=workspace(p);UUID key=UUID.randomUUID();var body=input(p,w);
        UUID done=(UUID)service.send(p,w,key,body).get("id");service.processPendingForTest(done);
        db.update("update ai_request set input_json='expired synthetic snapshot',updated_at=CURRENT_TIMESTAMP-interval '2 days' where id=?",done);
        UUID nextW=workspace(p),active=(UUID)service.send(p,nextW,UUID.randomUUID(),input(p,nextW)).get("id");
        String activeSnapshot=db.queryForObject("select input_json from ai_request where id=?",String.class,active);
        db.update("update ai_request set updated_at=CURRENT_TIMESTAMP-interval '2 days' where id=?",active);
        recovery.cleanup();
        assertThat(db.queryForObject("select input_json from ai_request where id=?",String.class,done)).isEmpty();
        assertThat(db.queryForObject("select input_json from ai_request where id=?",String.class,active)).isEqualTo(activeSnapshot);
        assertThat(service.send(p,w,key,body).get("id")).isEqualTo(done);assertThat(service.get(p,w,done).get("answer")).isEqualTo("合成恢复回答");
        verify(gateway,times(1)).complete(anyString());service.stopWaiting(p,nextW,active);
    }
}