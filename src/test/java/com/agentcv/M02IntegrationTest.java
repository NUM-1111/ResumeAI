package com.agentcv;

import com.agentcv.workspace.WorkspaceService;
import com.agentcv.document.*;
import com.agentcv.api.ApiFailure;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@EnabledIfEnvironmentVariable(named="AGENTCV_M00_DB_PASSWORD",matches=".+")
class M02IntegrationTest {
    @Autowired WorkspaceService service;
    @Autowired ResumeFiles files;
    @Autowired ResumeParser parser;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    UUID person(){return UUID.fromString((String)service.createProfile(UUID.randomUUID(),new WorkspaceService.CreateProfile("导入合成人物")).get("id"));}
    UUID workspace(UUID p){return UUID.fromString((String)service.createWorkspace(p,UUID.randomUUID(),new WorkspaceService.CreateWorkspace("独立工作区","Java")).get("id"));}
    @Test void uploadKeepsOriginalAndReplayDoesNotDuplicate() throws Exception {
        UUID p=person(),w=workspace(p);byte[] bytes="合成原文".getBytes(StandardCharsets.UTF_8);
        var upload=files.stage(p,w,"简历.txt",bytes);
        var body=new WorkspaceService.EstablishInput("修正后的简历",0L,upload.id(),null,null);
        UUID key=UUID.randomUUID();service.establish(p,w,key,body);service.establish(p,w,key,body);
        assertThat(service.original(p,w).bytes()).isEqualTo(bytes);
        service.save(p,w,UUID.randomUUID(),new WorkspaceService.ResumeInput("独立编辑",1L),false);
        assertThat(service.original(p,w).bytes()).isEqualTo(bytes);
        assertThatThrownBy(()->service.original(person(),w)).isInstanceOf(ApiFailure.class);
        mvc.perform(get("/api/v1/profiles/"+p+"/workspaces/"+w+"/resume/origin/file").with(user("operator")))
                .andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(content().bytes(bytes));
    }
    @Test void copyRequiresPreviewVersionAndCreatesIndependentText(){
        UUID p=person(),a=workspace(p),b=workspace(p);
        service.save(p,a,UUID.randomUUID(),new WorkspaceService.ResumeInput("来源 v1",0L),true);
        service.save(p,a,UUID.randomUUID(),new WorkspaceService.ResumeInput("来源 v2",1L),false);
        assertThatThrownBy(()->service.establish(p,b,UUID.randomUUID(),new WorkspaceService.EstablishInput(null,0L,null,a,1L)))
                .isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code).isEqualTo("SOURCE_CHANGED"));
        service.establish(p,b,UUID.randomUUID(),new WorkspaceService.EstablishInput("客户端伪造文本",0L,null,a,2L));
        assertThat(service.resume(p,b).get("text")).isEqualTo("来源 v2");
        service.save(p,b,UUID.randomUUID(),new WorkspaceService.ResumeInput("副本",1L),false);
        assertThat(service.resume(p,a).get("text")).isEqualTo("来源 v2");
        assertThatThrownBy(()->service.establish(p,b,UUID.randomUUID(),new WorkspaceService.EstablishInput(null,0L,null,a,2L))).isInstanceOf(ApiFailure.class);
    }
    @Test void fileFailureCanRetryWithoutRecreatingResume() throws Exception {
        UUID p=person(),w=workspace(p);byte[] data="文件故障测试".getBytes(StandardCharsets.UTF_8);
        var upload=files.stage(p,w,"故障.txt",data);
        var root=java.nio.file.Path.of(".local/m00/resume-files");
        var temporary=root.resolve("tmp-"+upload.id());var hidden=root.resolve("held-"+upload.id());
        java.nio.file.Files.move(temporary,hidden);
        try {
            var result=service.establish(p,w,UUID.randomUUID(),new WorkspaceService.EstablishInput("正文仍保存",0L,upload.id(),null,null));
            assertThat(result.get("text")).isEqualTo("正文仍保存");
            assertThat(db.queryForObject("select file_status from resume_origin where workspace_id=?",String.class,w)).isEqualTo("PENDING");
            java.nio.file.Files.move(hidden,temporary);
            service.retryFile(p,w);
            assertThat(service.original(p,w).bytes()).isEqualTo(data);
            assertThat(db.queryForObject("select count(*) from resume_revision where workspace_id=?",Integer.class,w)).isEqualTo(1);
        } finally { if(java.nio.file.Files.exists(hidden))java.nio.file.Files.move(hidden,temporary); }
    }
    @Test void rollbackDoesNotLeaveInitializedResume() throws Exception {
        UUID p=person(),w=workspace(p);var upload=files.stage(p,w,"回滚.txt","合成".getBytes(StandardCharsets.UTF_8));
        var template=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource()));
        template.execute(status->{
            service.establish(p,w,UUID.randomUUID(),new WorkspaceService.EstablishInput("回滚正文",0L,upload.id(),null,null));
            status.setRollbackOnly();return null;
        });
        assertThat(service.resume(p,w).get("initialized")).isEqualTo(false);
        // The orphan formal file is preserved for reconciliation, not falsely reported as a resume.
        assertThat(db.queryForObject("select count(*) from resume_origin where workspace_id=?",Integer.class,w)).isZero();
    }
    @Test void cleanupRemovesOnlyExpiredUnreferencedFiles() throws Exception {
        UUID p=person(),w=workspace(p);
        var upload=files.stage(p,w,"保留.txt","原件".getBytes(StandardCharsets.UTF_8));
        service.establish(p,w,UUID.randomUUID(),new WorkspaceService.EstablishInput("正文",0L,upload.id(),null,null));
        var root=java.nio.file.Path.of(".local/m00/resume-files");
        var referenced=root.resolve("file-"+upload.id());
        var orphan=root.resolve("file-"+UUID.randomUUID());
        var temporary=root.resolve("tmp-"+UUID.randomUUID());
        java.nio.file.Files.writeString(orphan,"孤儿");java.nio.file.Files.writeString(temporary,"过期临时文件");
        var old=java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(2)));
        java.nio.file.Files.setLastModifiedTime(referenced,old);
        java.nio.file.Files.setLastModifiedTime(orphan,old);
        java.nio.file.Files.setLastModifiedTime(temporary,old);
        files.cleanup();
        assertThat(java.nio.file.Files.exists(referenced)).isTrue();
        assertThat(java.nio.file.Files.exists(orphan)).isFalse();
        assertThat(java.nio.file.Files.exists(temporary)).isFalse();
    }
    @Test void competingInitializationsCannotOverwrite() throws Exception {
        UUID p=person(),a=workspace(p),target=workspace(p);
        service.save(p,a,UUID.randomUUID(),new WorkspaceService.ResumeInput("来源",0L),true);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var first=pool.submit(()->{start.await();try{service.establish(p,target,UUID.randomUUID(),new WorkspaceService.EstablishInput(null,0L,null,a,1L));return true;}catch(ApiFailure e){assertThat(e.status).isEqualTo(409);return false;}});
            var second=pool.submit(()->{start.await();try{service.save(p,target,UUID.randomUUID(),new WorkspaceService.ResumeInput("粘贴",0L),true);return true;}catch(ApiFailure e){assertThat(e.status).isEqualTo(409);return false;}});
            start.countDown();
            assertThat(first.get(10,java.util.concurrent.TimeUnit.SECONDS)^second.get(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(db.queryForObject("select count(*) from resume_revision where workspace_id=?",Integer.class,target)).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void parserSupportsFormatsAndRejectsBadContent() throws Exception {
        assertThat(parser.parse("a.txt","中文".getBytes(StandardCharsets.UTF_8)).text()).isEqualTo("中文");
        try(XWPFDocument doc=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            doc.createParagraph().createRun().setText("中文段落");doc.createTable().getRow(0).getCell(0).setText("表格职责");doc.write(out);
            assertThat(parser.parse("a.docx",out.toByteArray()).text()).contains("中文段落","表格职责");
        }
        try(PDDocument doc=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            PDPage page=new PDPage();doc.addPage(page);
            try(PDPageContentStream stream=new PDPageContentStream(doc,page)){
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);stream.newLineAtOffset(30,700);stream.showText("Synthetic resume");stream.endText();
            }
            doc.save(out);assertThat(parser.parse("a.pdf",out.toByteArray()).text()).contains("Synthetic resume");
        }
        for(String name:List.of("a.doc","a.pdf","a.docx"))assertThatThrownBy(()->parser.parse(name,new byte[]{1,2,3})).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(()->parser.parse("a.txt",new byte[]{(byte)0xff})).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(()->parser.parse("a.txt",new byte[262145])).isInstanceOf(ApiFailure.class);
        try(PDDocument doc=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            doc.addPage(new PDPage());doc.save(out);
            assertThatThrownBy(()->parser.parse("scan.pdf",out.toByteArray())).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.code).isEqualTo("NO_TEXT"));
        }
    }
}