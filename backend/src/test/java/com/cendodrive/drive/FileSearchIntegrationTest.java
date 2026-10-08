package com.cendodrive.drive;

import com.cendodrive.auth.AuthService;
import com.cendodrive.storage.FileStorage;
import com.cendodrive.user.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:filesearch;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true", "cendo.upload.cleanup-delay-ms=86400000"})
@AutoConfigureMockMvc
class FileSearchIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
  @Autowired DriveFileRepository files;
  @MockBean AuthService auth;
  @MockBean FileStorage storage;
  User owner, other;
  @BeforeEach void setup() {
    files.deleteAllInBatch(); users.deleteAllInBatch();
    owner = users.saveAndFlush(new User("search-owner", "hash", "搜索用户"));
    other = users.saveAndFlush(new User("search-other", "hash", "其他用户"));
    when(auth.authenticate("owner")).thenReturn(owner);
  }
  DriveFile folder(String name, Long parent) { return files.saveAndFlush(DriveFile.folder(owner.getId(), parent, name)); }
  DriveFile file(String name, Long parent) { return files.saveAndFlush(DriveFile.uploaded(owner.getId(), parent, name, 10, "unused")); }
  JsonNode search(String q, String... params) throws Exception {
    var request = get("/api/files/search").header("Authorization", "Bearer owner").param("q", q);
    for (int i = 0; i < params.length; i += 2) request.param(params[i], params[i + 1]);
    return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
  }
  @Test void matchesNamesOnlyAcrossUnloadedFoldersAndReturnsAncestorIds() throws Exception {
    var a = folder("工作", null); var b = folder("资料", a.getId());
    var target = file("扫描合同.PDF", b.getId()); file("notes-without-keyword.md", null);
    var result = search(" 合同 ");
    assertEquals(1, result.path("total").asInt());
    assertEquals(target.getId().toString(), result.at("/items/0/file/id").asText());
    assertEquals("/工作/资料", result.at("/items/0/path").asText());
    assertEquals(b.getId().toString(), result.at("/items/0/ancestors/1/id").asText());
    verifyNoInteractions(storage); // Includes scanned documents without downloading or extracting content.
  }
  @Test void escapesWildcardsEscapeCharacterAndSqlFragments() throws Exception {
    file("budget100%_!.txt", null); file("budget100xx!.txt", null); file("ordinary.txt", null);
    assertEquals(1, search("100%_!").path("total").asInt());
    assertEquals(0, search("' OR 1=1 --").path("total").asInt());
    assertEquals(1, search("BUDGET100%_").path("total").asInt());
  }
  @Test void excludesOtherOwnersAndHiddenOrDeletedAncestorsBeforePagination() throws Exception {
    var hidden = folder("秘密", null); hidden.setHidden(true); files.saveAndFlush(hidden);
    file("合同隐藏.pdf", hidden.getId());
    var deleted = folder("废弃", null); deleted.moveToTrash(); files.saveAndFlush(deleted);
    file("合同删除.pdf", deleted.getId());
    var self = file("合同自身隐藏.pdf", null); self.setHidden(true); files.saveAndFlush(self);
    files.saveAndFlush(DriveFile.uploaded(other.getId(), null, "合同其他用户.pdf", 10, "unused"));
    var invalid = folder("坏树", null); invalid.moveTo(invalid.getId()); files.saveAndFlush(invalid); file("合同循环.pdf", invalid.getId());
    file("合同公开.pdf", null);
    var result = search("合同", "size", "1");
    assertEquals(1, result.path("total").asInt()); assertEquals("合同公开.pdf", result.at("/items/0/file/name").asText());
  }
  @Test void scopesToDescendantsNotSiblingsOrTheScopeFolderItself() throws Exception {
    var a = folder("合同工作", null); var b = folder("子目录", a.getId());
    file("合同一.txt", a.getId()); file("合同二.txt", b.getId()); file("合同外.txt", null);
    var result = search("合同", "scope", "folder", "parentId", a.getId().toString());
    assertEquals(2, result.path("total").asInt());
    assertEquals(4, search("合同", "scope", "folder").path("total").asInt());
  }
  @Test void filtersTypesCaseInsensitivelyAndPaginatesWithStableOrder() throws Exception {
    folder("报告目录", null); file("报告1.PDF", null); file("报告2.md", null); file("报告3.JPG", null); file("报告4.zip", null);
    assertEquals(2, search("报告", "type", "doc").path("total").asInt());
    assertEquals(1, search("报告", "type", "folder").path("total").asInt());
    assertEquals(1, search("报告", "type", "image").path("total").asInt());
    assertEquals(1, search("报告", "type", "other").path("total").asInt());
    var first = search("报告", "type", "doc", "sort", "name", "size", "1");
    var second = search("报告", "type", "doc", "sort", "name", "size", "1", "page", "1");
    assertEquals(2, first.path("total").asInt()); assertNotEquals(first.at("/items/0/file/id"), second.at("/items/0/file/id"));
    assertEquals(0, search("报告", "page", "99").path("items").size());
  }
  @Test void rejectsUnauthenticatedInvalidOrInaccessibleScopes() throws Exception {
    mvc.perform(get("/api/files/search").param("q", "合同")).andExpect(status().isUnauthorized());
    for (String[] invalid : new String[][] {{"q", " "}, {"q", "x".repeat(101)}, {"type", "ocr"}, {"scope", "invalid"}, {"sort", "DROP TABLE"}, {"page", "-1"}, {"size", "101"}}) {
      var request = get("/api/files/search").header("Authorization", "Bearer owner").param("q", "合同");
      request.param(invalid[0], invalid[1]);
      // param appends values: use only the supplied query value for invalid q.
      if (invalid[0].equals("q")) request = get("/api/files/search").header("Authorization", "Bearer owner").param("q", invalid[1]);
      mvc.perform(request).andExpect(status().isBadRequest());
    }
    var foreign = files.saveAndFlush(DriveFile.folder(other.getId(), null, "别人的目录"));
    var hidden = folder("隐藏目录", null); hidden.setHidden(true); files.saveAndFlush(hidden);
    for (Long id : java.util.List.of(foreign.getId(), hidden.getId(), 999999L))
      mvc.perform(get("/api/files/search").header("Authorization", "Bearer owner").param("q", "合同")
          .param("scope", "folder").param("parentId", id.toString())).andExpect(status().isNotFound());
  }
}
