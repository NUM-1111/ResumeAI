package com.agentcv;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.encryption.*;

class DocumentImportTest {
 DocumentParser parser=new DocumentParser();
 static byte[] docx() throws Exception {
  try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()){
   doc.createParagraph().createRun().setText("合成中文简历：接口开发");doc.createTable(1,1).getRow(0).getCell(0).setText("合成表格经历");doc.write(out);return out.toByteArray();
  }
 }
 static byte[] pdf(boolean blank,boolean encrypted,int pages) throws Exception {
  try(var doc=new PDDocument();var out=new ByteArrayOutputStream()){
   for(int i=0;i<pages;i++)doc.addPage(new PDPage());
   if(!blank)try(var content=new PDPageContentStream(doc,doc.getPage(0))){content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);content.newLineAtOffset(40,700);content.showText("Synthetic resume text layer");content.endText();}
   if(encrypted)doc.protect(new StandardProtectionPolicy("owner-secret","user-secret",new AccessPermission()));
   doc.save(out);return out.toByteArray();
  }
 }
 @Test void extractsDocxParagraphTablesAndPdfText() throws Exception {
  assertThat(parser.extract("合成.docx",docx())).contains("合成中文简历","合成表格经历");
  assertThat(parser.extract("合成.pdf",pdf(false,false,1))).contains("Synthetic resume");
  java.nio.file.Path fixtures=java.nio.file.Path.of(".local/document-fixtures");java.nio.file.Files.createDirectories(fixtures);
  java.nio.file.Files.write(fixtures.resolve("synthetic.docx"),docx());java.nio.file.Files.write(fixtures.resolve("synthetic.pdf"),pdf(false,false,1));
 }
 @Test void rejectsBlankEncryptedDamagedUnsupportedAndPageLimit() throws Exception {
  assertThatThrownBy(()->parser.extract("scan.pdf",pdf(true,false,1))).hasMessageContaining("OCR");
  assertThatThrownBy(()->parser.extract("secret.pdf",pdf(false,true,1))).hasMessageContaining("加密");
  assertThatThrownBy(()->parser.extract("large.pdf",pdf(true,false,41))).hasMessageContaining("40页");
  assertThatThrownBy(()->parser.extract("bad.docx",new byte[]{1,2})).hasMessageContaining("损坏");
  assertThatThrownBy(()->parser.extract("old.doc",new byte[]{1,2})).hasMessageContaining("仅支持");
 }
 @Test void rejectsZipExpansionAndFileSize() throws Exception {
  assertThatThrownBy(()->parser.extract("big.pdf",new byte[262145])).hasMessageContaining("256");
  var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)){zip.putNextEntry(new ZipEntry("huge"));zip.write(new byte[8388609]);zip.closeEntry();}
  assertThatThrownBy(()->parser.extract("bomb.docx",out.toByteArray())).hasMessageContaining("8 MiB");
 }
}