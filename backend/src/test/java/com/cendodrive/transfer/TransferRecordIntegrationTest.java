package com.cendodrive.transfer;

import com.cendodrive.auth.AuthService;
import com.cendodrive.index.AiIndexClient;
import com.cendodrive.storage.FileStorage;
import com.cendodrive.user.User;
import com.cendodrive.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:transfers;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true", "cendo.upload.cleanup-delay-ms=86400000",
    "cendo.storage.cleanup-delay-ms=86400000", "cendo.ai.worker-delay-ms=86400000" })
@AutoConfigureMockMvc
class TransferRecordIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired UserRepository users;
  @Autowired TransferRecordRepository records;
  @MockBean AuthService auth;
  @MockBean FileStorage storage;
  @MockBean AiIndexClient indexClient;

  @BeforeEach void setup() {
    records.deleteAllInBatch(); users.deleteAllInBatch();
    User first = users.saveAndFlush(new User("first", "hash", "First"));
    User second = users.saveAndFlush(new User("second", "hash", "Second"));
    when(auth.authenticate("first")).thenReturn(first);
    when(auth.authenticate("second")).thenReturn(second);
  }

  @Test void historyIsScopedToAccountAndSupportsUpsertAndClear() throws Exception {
    var body = """
        {"id":"client-1","direction":"upload","name":"report.pdf","size":42,
         "status":"success","progress":100,"createdAt":1700000000000}
        """;
    mvc.perform(post("/api/transfers").header("Authorization", "Bearer first")
        .contentType("application/json").content(body)).andExpect(status().isOk());
    mvc.perform(post("/api/transfers").header("Authorization", "Bearer second")
        .contentType("application/json").content(body.replace("report.pdf", "other.pdf"))).andExpect(status().isOk());
    mvc.perform(post("/api/transfers").header("Authorization", "Bearer first")
        .contentType("application/json").content(body.replace("report.pdf", "updated.pdf"))).andExpect(status().isOk());
    mvc.perform(get("/api/transfers").header("Authorization", "Bearer first"))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("updated.pdf"))
        .andExpect(jsonPath("$.length()").value(1));
    mvc.perform(delete("/api/transfers").header("Authorization", "Bearer first")
        .param("direction", "upload")).andExpect(status().isNoContent());
    mvc.perform(get("/api/transfers").header("Authorization", "Bearer first"))
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/transfers").header("Authorization", "Bearer second"))
        .andExpect(jsonPath("$[0].name").value("other.pdf"));
    mvc.perform(get("/api/transfers")).andExpect(status().isUnauthorized());
  }

  @Test void rejectsActiveOrInvalidRecords() throws Exception {
    mvc.perform(post("/api/transfers").header("Authorization", "Bearer first")
        .contentType("application/json").content("""
          {"id":"x","direction":"transfer","name":"copy.txt","size":1,
           "status":"uploading","progress":50,"createdAt":1700000000000}
          """)).andExpect(status().isBadRequest());
  }
}
