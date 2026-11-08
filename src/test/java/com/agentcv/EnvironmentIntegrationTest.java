package com.agentcv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"agentcv.operator-password-hash=${AGENTCV_TEST_PASSWORD_HASH}","agentcv.test-password=${AGENTCV_TEST_PASSWORD}"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class EnvironmentIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired Flyway flyway;
    @Value("${agentcv.test-password}") String password;

    @Test
    void migrationIsValidAndRepeatableWithoutDuplicateRows() {
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.sql("select marker from environment_checkpoint where id = 1")
                .query(String.class).single()).isEqualTo("环境验证：中文持久化正常");
        assertThat(jdbc.sql("select count(*) from environment_checkpoint").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void anonymousApiIsRejectedAndCsrfIsNotCached() throws Exception {
        mvc.perform(get("/api/v1/environment")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/csrf")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.token").isNotEmpty());
        mvc.perform(post("/login").param("username", "operator").param("password", password))
                .andExpect(status().isForbidden());
    }

    @Test
    void realLoginSessionServesApiAndBuiltVueThenLogoutRequiresCsrf() throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(formLogin().user("operator").password(password))
                .andExpect(status().is3xxRedirection()).andReturn().getRequest().getSession(false);
        assertThat(session).isNotNull();
        mvc.perform(get("/api/v1/environment").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("CONNECTED"))
                .andExpect(jsonPath("$.migrationVersion").value("2"));
        mvc.perform(get("/").session(session)).andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html").session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/assets/")));
        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/logout").session(session).with(csrf())).andExpect(status().is3xxRedirection());
    }
}