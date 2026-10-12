package com.cendodrive.ai.claw;

import com.cendodrive.drive.DriveFile;
import com.cendodrive.storage.FileStorage;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ClawDocumentReader {
  public static final int MAX_BYTES=10*1024*1024, MAX_TEXT=80000;
  private static final Set<String> EXTENSIONS=Set.of("txt","md","csv","pdf","docx");
  private final FileStorage storage;
  private final Path root;
  public ClawDocumentReader(FileStorage storage,@Value("${cendo.storage.root:./storage}") String root) {
    this.storage=storage; this.root=Path.of(root).toAbsolutePath().normalize();
  }
  public static boolean supports(String name) { return EXTENSIONS.contains(extension(name)); }
  private static String extension(String name) { int dot=name.lastIndexOf('.');return dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT); }
  public List<String> chunks(DriveFile file) {
    if(!supports(file.getName())) throw new ClawFailure("CLAW_UNSUPPORTED");
    if(file.getSize()>MAX_BYTES) throw new ClawFailure("CLAW_TOO_LARGE");
    try {
      var output=new LimitedOutput(MAX_BYTES);
      if("fastdfs".equals(file.getStorageBackend())) storage.download(file.getStorageKey(),output);
      else if("local".equals(file.getStorageBackend())) {
        Path path=root.resolve(file.getStorageKey()).normalize();
        if(!path.startsWith(root) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().startsWith(root.toRealPath())) throw new IOException("Invalid storage path");
        try(var input=Files.newInputStream(path)) { input.transferTo(output); }
      } else throw new IOException("Unsupported storage backend");
      String text=extract(file.getName(),output.bytes()).replace("\u0000","").strip();
      if(text.isBlank()) throw new ClawFailure("CLAW_NO_TEXT");
      if(text.length()>MAX_TEXT) throw new ClawFailure("CLAW_TOO_LARGE");
      var result=new ArrayList<String>();
      for(int start=0;start<text.length();start+=800) {
        // Keep the existing UTF-16 payload budget, but never cut a supplementary character in half.
        int safeStart=start;
        if(safeStart>0 && Character.isLowSurrogate(text.charAt(safeStart))) safeStart--;
        int end=Math.min(start+1000,text.length());
        if(end<text.length() && Character.isHighSurrogate(text.charAt(end-1))) end--;
        result.add("文件名："+file.getName()+"\n"+text.substring(safeStart,end));
        if(end==text.length()) break;
      }
      return List.copyOf(result);
    } catch(ClawFailure ex) { throw ex; }
    catch(IOException | RuntimeException ex) { throw new ClawFailure("CLAW_EXTRACTION_FAILED"); }
  }
  String extract(String name,byte[] bytes) throws IOException {
    if(bytes.length>MAX_BYTES) throw new ClawFailure("CLAW_TOO_LARGE");
    if(extension(name).equals("pdf")) {
      try(var document=Loader.loadPDF(bytes)) {
        if(document.isEncrypted()) throw new ClawFailure("CLAW_UNSUPPORTED");
        if(document.getNumberOfPages()>100) throw new ClawFailure("CLAW_TOO_LARGE");
        // Reject excessive decompressed page content before the text parser sees it.
        long contentBytes=0;
        for(var page:document.getPages()) try(var content=page.getContents()) {
          int pageBytes=content.readNBytes(2*1024*1024+1).length;
          if(pageBytes>2*1024*1024) throw new ClawFailure("CLAW_TOO_LARGE");
          contentBytes+=pageBytes;
          if(contentBytes>16*1024*1024) throw new ClawFailure("CLAW_TOO_LARGE");
        }
        var writer=new LimitedWriter(); new BudgetTextStripper().writeText(document,writer); return writer.text();
      }
    }
    if(extension(name).equals("docx")) {
      long expanded=0; int entries=0;
      try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
        while(zip.getNextEntry()!=null) {
          if(++entries>500) throw new ClawFailure("CLAW_TOO_LARGE");
          byte[] buffer=new byte[8192]; int length;
          while((length=zip.read(buffer))!=-1) { expanded+=length;if(expanded>32*1024*1024) throw new ClawFailure("CLAW_TOO_LARGE"); }
        }
      }
      ZipSecureFile.setMaxEntrySize(16*1024*1024);
      try(var document=new XWPFDocument(new ByteArrayInputStream(bytes));var extractor=new XWPFWordExtractor(document)) {
        String text=extractor.getText();if(text.length()>MAX_TEXT) throw new ClawFailure("CLAW_TOO_LARGE");return text;
      }
    }
    if(!supports(name)) throw new ClawFailure("CLAW_UNSUPPORTED");
    var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
    String text=decoder.decode(ByteBuffer.wrap(bytes)).toString();return text.startsWith("\ufeff")?text.substring(1):text;
  }
  private static final class BudgetTextStripper extends PDFTextStripper {
    private int operators, positions;
    BudgetTextStripper() throws IOException {}
    @Override protected void processOperator(org.apache.pdfbox.contentstream.operator.Operator operator,List<org.apache.pdfbox.cos.COSBase> operands) throws IOException {
      if(++operators>200000) throw new ClawFailure("CLAW_TOO_LARGE");
      super.processOperator(operator,operands);
    }
    @Override protected void processTextPosition(org.apache.pdfbox.text.TextPosition position) {
      if(++positions>MAX_TEXT*2) throw new ClawFailure("CLAW_TOO_LARGE");
      super.processTextPosition(position);
    }
  }
  private static final class LimitedOutput extends OutputStream {
    private final ByteArrayOutputStream output=new ByteArrayOutputStream(); private final int limit;
    LimitedOutput(int limit) { this.limit=limit; }
    @Override public void write(int value) throws IOException { if(output.size()>=limit) throw new ClawFailure("CLAW_TOO_LARGE");output.write(value); }
    @Override public void write(byte[] bytes,int offset,int length) throws IOException { if(length>limit-output.size()) throw new ClawFailure("CLAW_TOO_LARGE");output.write(bytes,offset,length); }
    byte[] bytes() { return output.toByteArray(); }
  }
  private static final class LimitedWriter extends Writer {
    private final StringBuilder output=new StringBuilder();
    @Override public void write(char[] chars,int offset,int length) { if(length>MAX_TEXT-output.length()) throw new ClawFailure("CLAW_TOO_LARGE");output.append(chars,offset,length); }
    @Override public void flush() {} @Override public void close() {}
    String text() { return output.toString(); }
  }
}
