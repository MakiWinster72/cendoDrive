package com.cendodrive.ai.claw;

import com.cendodrive.drive.DriveFile;
import com.cendodrive.storage.FileStorage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClawDocumentReaderTest {
  @Test void readsUtf8BomMarkdownCsvAndOverlappingChunks() throws Exception {
    var storage=mock(FileStorage.class);var reader=new ClawDocumentReader(storage,"/tmp/claw-reader");
    assertEquals("中文内容",reader.extract("文档.TXT","\ufeff中文内容".getBytes(StandardCharsets.UTF_8)));
    assertEquals("标题\n内容",reader.extract("a.md","标题\n内容".getBytes(StandardCharsets.UTF_8)));
    doAnswer(call->{((OutputStream)call.getArgument(1)).write("甲".repeat(1800).getBytes(StandardCharsets.UTF_8));return null;}).when(storage).download(eq("test"),any());
    var chunks=reader.chunks(DriveFile.uploaded(1L,null,"document.txt",5400,"test"));
    assertEquals(2,chunks.size());assertTrue(chunks.get(0).startsWith("文件名：document.txt\n"));
  }
  @Test void keepsUnicodeCodePointsIntactAtChunkAndOverlapBoundaries() throws Exception {
    var storage=mock(FileStorage.class);var reader=new ClawDocumentReader(storage,"/tmp/claw-reader");
    String text="甲".repeat(799)+"😀"+"乙".repeat(198)+"🌙"+"丙".repeat(850);
    doAnswer(call->{((OutputStream)call.getArgument(1)).write(text.getBytes(StandardCharsets.UTF_8));return null;}).when(storage).download(eq("unicode"),any());
    var chunks=reader.chunks(DriveFile.uploaded(1L,null,"unicode.txt",text.getBytes(StandardCharsets.UTF_8).length,"unicode"));
    assertTrue(chunks.stream().allMatch(chunk->StandardCharsets.UTF_8.newEncoder().canEncode(chunk)),"Chunk and overlap boundaries must not split surrogate pairs");
    assertTrue(chunks.stream().anyMatch(chunk->chunk.contains("😀")));
    assertTrue(chunks.stream().anyMatch(chunk->chunk.contains("🌙")));
  }
  @Test void extractsRealPdfAndDocxIncludingTables() throws Exception {
    var reader=new ClawDocumentReader(mock(FileStorage.class),"/tmp/claw-reader");var bytes=new ByteArrayOutputStream();
    try(var pdf=new PDDocument()) {
      var page=new PDPage();pdf.addPage(page);
      try(var content=new PDPageContentStream(pdf,page)) { content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);content.newLineAtOffset(50,700);content.showText("Synthetic budget is 30000");content.endText(); }
      pdf.save(bytes);
    }
    assertTrue(reader.extract("sample.pdf",bytes.toByteArray()).contains("30000"));bytes.reset();
    try(var doc=new XWPFDocument()) { doc.createParagraph().createRun().setText("合成文档预算三万元");doc.createTable(1,1).getRow(0).getCell(0).setText("交付五月一日");doc.write(bytes); }
    String extracted=reader.extract("sample.docx",bytes.toByteArray());assertTrue(extracted.contains("三万元"));assertTrue(extracted.contains("五月一日"));
  }
  @Test void rejectsCompressedPdfPageThatExceedsDecompressedBudget() throws Exception {
    var reader=new ClawDocumentReader(mock(FileStorage.class),"/tmp/claw-reader");var bytes=new ByteArrayOutputStream();
    try(var pdf=new PDDocument()) {
      var page=new PDPage();pdf.addPage(page);var content=new org.apache.pdfbox.pdmodel.common.PDStream(pdf);
      try(var output=content.createOutputStream(org.apache.pdfbox.cos.COSName.FLATE_DECODE)) { output.write("% padding\n".repeat(240000).getBytes(StandardCharsets.UTF_8)); }
      page.setContents(content);pdf.save(bytes);
    }
    assertEquals("CLAW_TOO_LARGE",assertThrows(ClawFailure.class,()->reader.extract("compressed.pdf",bytes.toByteArray())).code());
  }
  @Test void rejectsUnsupportedInvalidUtf8OversizedAndEmptyDocumentsWithoutModelCalls() throws Exception {
    var storage=mock(FileStorage.class);var reader=new ClawDocumentReader(storage,"/tmp/claw-reader");
    assertEquals("CLAW_UNSUPPORTED",assertThrows(ClawFailure.class,()->reader.chunks(DriveFile.uploaded(1L,null,"image.png",1,"test"))).code());
    assertThrows(IOException.class,()->reader.extract("bad.txt",new byte[]{(byte)0xff}));
    assertEquals("CLAW_TOO_LARGE",assertThrows(ClawFailure.class,()->reader.chunks(DriveFile.uploaded(1L,null,"large.pdf",ClawDocumentReader.MAX_BYTES+1L,"test"))).code());
    verifyNoInteractions(storage);
    doAnswer(call->{((OutputStream)call.getArgument(1)).write("  ".getBytes());return null;}).when(storage).download(eq("empty"),any());
    assertEquals("CLAW_NO_TEXT",assertThrows(ClawFailure.class,()->reader.chunks(DriveFile.uploaded(1L,null,"empty.txt",2,"empty"))).code());
  }
}
