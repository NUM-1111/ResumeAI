package com.agentcv;

import java.io.*;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.zip.*;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class DocumentParser {
    private final Semaphore slot=new Semaphore(1);
    public String extract(String name,byte[] bytes) {
        if(!slot.tryAcquire())throw new WorkspaceService.Failure(503,"PARSER_BUSY","解析忙，请稍后重试或粘贴文本");
        FutureTask<String> job=new FutureTask<>(()->{try{return parse(name,bytes);}finally{slot.release();}});
        Thread thread=new Thread(job,"bounded-document-parser");thread.setDaemon(true);thread.start();
        try{return job.get(5,TimeUnit.SECONDS);}
        catch(TimeoutException e){throw new WorkspaceService.Failure(422,"PARSE_TIMEOUT","解析超时，未导入。请缩小文件或粘贴文本");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new WorkspaceService.Failure(503,"PARSE_INTERRUPTED","解析中断，未导入");}
        catch(ExecutionException e){if(e.getCause() instanceof WorkspaceService.Failure f)throw f;throw new WorkspaceService.Failure(422,"DAMAGED_DOCUMENT","文件损坏或格式不匹配，请另存或粘贴文本");}
    }
    String parse(String name,byte[] bytes) throws IOException {
        if(bytes.length>262144)throw new WorkspaceService.Failure(413,"FILE_TOO_LARGE","文件超过256 KiB，请缩小文件");
        String lower=name.toLowerCase(Locale.ROOT),content;
        if(lower.endsWith(".docx")) {
            int entries=0,total=0;
            try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
                byte[] buffer=new byte[8192];
                while(zip.getNextEntry()!=null){if(++entries>256)throw new IOException("ZIP entries");int n;while((n=zip.read(buffer))!=-1){total+=n;if(total>8388608)throw new WorkspaceService.Failure(413,"EXPANSION_LIMIT","文档解压内容超过8 MiB，请缩小文件");}}
            }
            ZipSecureFile.setMaxEntrySize(8388608);ZipSecureFile.setMaxTextSize(262144);
            try(XWPFDocument doc=new XWPFDocument(new ByteArrayInputStream(bytes))) {
                StringBuilder text=new StringBuilder();
                for(IBodyElement element:doc.getBodyElements()) {
                    if(element instanceof XWPFParagraph p)text.append(p.getText()).append('\n');
                    if(element instanceof XWPFTable t)text.append(t.getText()).append('\n');
                    if(text.length()>262144)throw new WorkspaceService.Failure(413,"TEXT_TOO_LARGE","提取文本过长，请缩小文件");
                }
                content=text.toString();
            }
        } else if(lower.endsWith(".pdf")) {
            try(PDDocument doc=Loader.loadPDF(bytes)) {
                if(doc.isEncrypted())throw new WorkspaceService.Failure(422,"ENCRYPTED_DOCUMENT","不支持加密PDF，请解密后重试或粘贴文本");
                if(doc.getNumberOfPages()>40)throw new WorkspaceService.Failure(413,"PAGE_LIMIT","PDF超过40页，请拆分文件");
                StringBuilder text=new StringBuilder();PDFTextStripper stripper=new PDFTextStripper();
                for(int page=1;page<=doc.getNumberOfPages();page++){stripper.setStartPage(page);stripper.setEndPage(page);text.append(stripper.getText(doc));if(text.length()>262144)throw new WorkspaceService.Failure(413,"TEXT_TOO_LARGE","提取文本过长，请缩小文件");}
                content=text.toString();
            }catch(InvalidPasswordException e){throw new WorkspaceService.Failure(422,"ENCRYPTED_DOCUMENT","不支持加密PDF，请解密后重试或粘贴文本");}
        }else throw new WorkspaceService.Failure(422,"UNSUPPORTED_FORMAT","仅支持txt、docx和文本层PDF；不支持doc或OCR");
        if(content.isBlank())throw new WorkspaceService.Failure(422,"NO_TEXT","未提取到文本，不支持扫描件/OCR，请粘贴文本");
        return WorkspaceService.text(content);
    }
}