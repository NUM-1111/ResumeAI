package com.agentcv;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SkeletonIntegrationTest {
    @Autowired MockMvc mvc;
    @Test void exposesOnlyDataFreeStatus() throws Exception {
        mvc.perform(get("/api/v1/system/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("skeleton"));
        mvc.perform(get("/api/v1/profiles")).andExpect(status().isUnauthorized());
    }
    @Test void retainsCsrfProtection() throws Exception {
        mvc.perform(post("/api/v1/profiles")).andExpect(status().isForbidden());
    }
}
