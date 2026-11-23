package com.agentcv;

import com.agentcv.workspace.WorkspaceService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="AGENTCV_M00_DB_PASSWORD",matches=".+")
class M02UploadHttpTest {
    @Autowired WorkspaceService service;
    @Autowired MockMvc mvc;
    @Test void invalidPdfHasParseError() throws Exception {
        String p=(String)service.createProfile(UUID.randomUUID(),new WorkspaceService.CreateProfile("HTTP合成")).get("id");
        String w=(String)service.createWorkspace(UUID.fromString(p),UUID.randomUUID(),new WorkspaceService.CreateWorkspace("导入","Java")).get("id");
        mvc.perform(post("/api/v1/profiles/"+p+"/workspaces/"+w+"/resume/upload-preview").with(user("operator")).with(csrf())
                .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"name\":\"bad.pdf\",\"base64\":\"YmFk\"}"))
                .andExpect(status().isUnprocessableEntity());
    }
}