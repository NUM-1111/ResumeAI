package com.agentcv.document;

import com.agentcv.api.ApiFailure;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import java.util.zip.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class ResumeParser {
    public static final int MAX_BYTES=262144;
    private final AtomicBoolean occupied=new AtomicBoolean();
    public ResumeParser(){ZipSecureFile.setMaxEntrySize(8*1024*1024);ZipSecureFile.setMaxTextSize(MAX_BYTES);}
    public record Parsed(String text,String limitation) {}
    public Parsed parse(String name,byte[] bytes) {
        if(bytes.length==0||bytes.length>MAX_BYTES)throw new ApiFailure(413,"FILE_TOO_LARGE","文件为空或超过 256 KiB，请缩小文件或粘贴文本。");
        if(name==null||name.length()>200||name.chars().anyMatch(Character::isISOControl))throw new ApiFailure(400,"VALIDATION_ERROR","文件名无效或过长。");
        String ext=name.toLowerCase(Locale.ROOT);
        if(!ext.endsWith(".txt")&&!ext.endsWith(".docx")&&!ext.endsWith(".pdf"))throw new ApiFailure(422,"UNSUPPORTED_FILE","仅支持 UTF-8 TXT、DOCX 和文本层 PDF。");
        if(!occupied.compareAndSet(false,true))throw new ApiFailure(503,"PARSER_BUSY","正在解析另一份文件，请稍后重试。");
        FutureTask<Parsed> task=new FutureTask<>(()->{try{return extract(ext,bytes);}finally{occupied.set(false);}});
        Thread worker=new Thread(task,"resume-parser");worker.setDaemon(true);worker.start();
        try{return task.get(5,TimeUnit.SECONDS);}
        catch(TimeoutException e){throw new ApiFailure(503,"PARSE_TIMEOUT","解析超时，请稍后重试或粘贴文本。");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiFailure(503,"PARSE_INTERRUPTED","解析中断，请重试。");}
        catch(ExecutionException e){if(e.getCause() instanceof ApiFailure f)throw f;throw new ApiFailure(422,"PARSE_FAILED","文件损坏、加密或无法读取，请使用其他文件或粘贴文本。");}
        // A timeout does not free occupied: only the actual parser completion does.
    }
    private Parsed extract(String ext,byte[] bytes) throws Exception {
        String text,limitation="";
        if(ext.endsWith(".txt")){
            try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
            catch(CharacterCodingException e){throw new ApiFailure(422,"INVALID_ENCODING","TXT 必须采用 UTF-8 编码，请转换编码或粘贴文字。");}
            if(text.startsWith("\ufeff"))text=text.substring(1);
        }else if(ext.endsWith(".docx")){
            long total=0;int count=0;
            try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){
                byte[] buffer=new byte[8192];
                while(zip.getNextEntry()!=null){if(++count>256)throw limit();int n;while((n=zip.read(buffer))!=-1){total+=n;if(total>8*1024*1024)throw limit();}}
            }
            try(XWPFDocument doc=new XWPFDocument(new ByteArrayInputStream(bytes));XWPFWordExtractor extractor=new XWPFWordExtractor(doc)){text=extractor.getText();}
            limitation="已提取可读取的段落和表格文字；图片、文本框或布局内容可能遗漏，请对照原件核对。";
        }else{
            try(var pdf=Loader.loadPDF(bytes)){
                if(pdf.isEncrypted())throw new ApiFailure(422,"ENCRYPTED_PDF","不支持加密 PDF，请使用未加密文件或粘贴文字。");
                if(pdf.getNumberOfPages()>40)throw limit();
                text=new PDFTextStripper().getText(pdf);
            }
            limitation="仅读取 PDF 文本层，不识别图片；顺序和内容可能遗漏，请对照原件核对。";
        }
        if(text.isBlank())throw new ApiFailure(422,"NO_TEXT","未读取到可用文字，不支持扫描件/OCR，请粘贴文本。");
        if(text.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw limit();
        return new Parsed(text,limitation);
    }
    private ApiFailure limit(){return new ApiFailure(413,"PARSE_LIMIT","文档页数、解压量或提取文字超限，请拆分文件或粘贴文字。");}
}