package com.agentcv;

import com.agentcv.document.ResumeFiles;
import com.agentcv.workspace.WorkspaceService;
import java.util.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@EnabledIfEnvironmentVariable(named="AGENTCV_M00_DB_PASSWORD",matches=".+")
class M08SafetyTest {
    @Autowired WorkspaceService workspaces;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @TempDir Path folder;
    @Test void cleanupKeepsReferencedAndActiveFilesAndFailsClosedWithoutDatabase() throws Exception {
        UUID p=UUID.fromString(workspaces.createProfile(UUID.randomUUID(),new WorkspaceService.CreateProfile("M08文件合成")).get("id").toString());
        UUID w=UUID.fromString(workspaces.createWorkspace(p,UUID.randomUUID(),new WorkspaceService.CreateWorkspace("文件清理","Java")).get("id").toString());
        var files=new ResumeFiles(folder.toString(),db);
        var active=files.stage(p,w,"active.txt",new byte[]{1});
        UUID referenced=UUID.randomUUID(),temporary=UUID.randomUUID(),orphan=UUID.randomUUID();
        Path ref=folder.resolve("tmp-"+referenced),tmp=folder.resolve("tmp-"+temporary),formal=folder.resolve("file-"+orphan);
        for(Path path:List.of(ref,tmp,formal,folder.resolve("tmp-"+active.id()))){Files.write(path,new byte[]{1});Files.setLastModifiedTime(path,FileTime.from(Instant.now().minus(Duration.ofDays(2))));}
        db.update("insert into resume_origin(workspace_id,file_id,original_name,file_status) values (?,?,?,'PENDING')",w,referenced,"synthetic.txt");
        files.cleanup();
        assertThat(Files.exists(ref)).isTrue();assertThat(Files.exists(folder.resolve("tmp-"+active.id()))).isTrue();
        assertThat(Files.exists(tmp)).isFalse();assertThat(Files.exists(formal)).isFalse();
        Path preserve=folder.resolve("tmp-"+UUID.randomUUID());Files.write(preserve,new byte[]{2});Files.setLastModifiedTime(preserve,FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        var unavailable=mock(JdbcTemplate.class);when(unavailable.queryForList(anyString(),eq(UUID.class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic database unavailable"));
        assertThatThrownBy(()->new ResumeFiles(folder.toString(),unavailable).cleanup()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(Files.exists(preserve)).isTrue();
    }
    @Test void protectedMutationsRequireLoginAndCsrfWithoutExposingStorageErrors() throws Exception {
        String route="/api/v1/profiles/"+UUID.randomUUID()+"/background-changes/"+UUID.randomUUID()+"/confirm";
        mvc.perform(get("/api/v1/profiles")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mvc.perform(post(route).with(user("operator")).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(post(route).with(user("operator")).with(csrf()).contentType("application/json").content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}