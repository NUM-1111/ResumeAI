package com.agentcv;

import com.agentcv.workspace.WorkspaceService;
import com.agentcv.api.ApiFailure;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@EnabledIfEnvironmentVariable(named="AGENTCV_M00_DB_PASSWORD",matches=".+")
class M01IntegrationTest {
    @Autowired WorkspaceService service;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    UUID person() { return UUID.fromString((String)service.createProfile(UUID.randomUUID(),new WorkspaceService.CreateProfile("合成测试人物")).get("id")); }
    UUID workspace(UUID p) { return UUID.fromString((String)service.createWorkspace(p,UUID.randomUUID(),new WorkspaceService.CreateWorkspace("合成工作区","Java")).get("id")); }
    @Test void initializationIsIndependentOwnedAndIdempotent() {
        UUID p=person(),other=person(),w=workspace(p),key=UUID.randomUUID();
        var input=new WorkspaceService.ResumeInput("合成简历：协助部署",0L);
        var first=service.save(p,w,key,input,true);
        var replay=service.save(p,w,key,input,true);
        assertThat(replay.get("text")).isEqualTo(first.get("text"));
        assertThat(((Number)replay.get("version")).longValue()).isEqualTo(((Number)first.get("version")).longValue());
        assertThat(db.queryForObject("select count(*) from resume_revision where workspace_id=?",Integer.class,w)).isEqualTo(1);
        assertThatThrownBy(()->service.resume(other,w)).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(()->service.save(p,w,key,new WorkspaceService.ResumeInput("不同载荷",0L),true))
                .isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(service.resume(p,w)).containsEntry("text",input.text());
        assertThat(db.queryForObject("select count(*) from material where profile_id=?",Integer.class,p)).isZero();
    }
    @Test void concurrentUpdatesOnlyOneWins() throws Exception {
        UUID p=person(),w=workspace(p);
        service.save(p,w,UUID.randomUUID(),new WorkspaceService.ResumeInput("起点",0L),true);
        ExecutorService executor=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(String value:List.of("A","B"))results.add(executor.submit(()->{
                start.await();try{service.save(p,w,UUID.randomUUID(),new WorkspaceService.ResumeInput(value,1L),false);return true;}
                catch(ApiFailure e){assertThat(e.code).isEqualTo("VERSION_CONFLICT");return false;}
            }));
            start.countDown();int wins=0;for(var result:results)if(result.get(10,TimeUnit.SECONDS))wins++;
            assertThat(wins).isEqualTo(1);
            assertThat(((Number)service.resume(p,w).get("version")).longValue()).isEqualTo(2);
        } finally {executor.shutdownNow();}
    }
    @Test void concurrentSameKeyCreatesOnlyOnce() throws Exception {
        UUID key=UUID.randomUUID();var body=new WorkspaceService.CreateProfile("并发同键");
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->service.createProfile(key,body));var b=pool.submit(()->service.createProfile(key,body));
            assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
    }
    @Test void validationAndHttpSecurity() throws Exception {
        assertThatThrownBy(()->service.createProfile(UUID.randomUUID(),new WorkspaceService.CreateProfile("  "))).isInstanceOf(ApiFailure.class);
        mvc.perform(get("/api/v1/profiles")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mvc.perform(post("/api/v1/profiles").with(user("operator")).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/profiles").with(user("operator")).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/login").with(csrf()).param("username","operator").param("password",System.getenv("AGENTCV_TEST_PASSWORD")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true));
    }
    @Test void paginationAndSameNameDoNotLoseObjects() {
        UUID p=person();
        for(int i=0;i<27;i++)workspace(p);
        var first=service.workspaces(p,null,"");
        var items=(List<?>)first.get("items");
        assertThat(items).hasSize(25);
        UUID cursor=UUID.fromString((String)first.get("next_cursor"));
        assertThat((List<?>)service.workspaces(p,cursor,"").get("items")).hasSize(2);
        assertThat((List<?>)service.workspaces(person(),null,"").get("items")).isEmpty();
        assertThat((List<?>)service.workspaces(p,null,"无匹配名称").get("items")).isEmpty();
    }
}