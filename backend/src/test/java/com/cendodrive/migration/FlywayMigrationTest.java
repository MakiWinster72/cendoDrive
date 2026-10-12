package com.cendodrive.migration;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlywayMigrationTest {
  @Test void freshDatabaseInitializesCompleteSchemaAndCanRestart() throws Exception {
    String url = "jdbc:h2:mem:migrations-" + UUID.randomUUID()
        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    Flyway flyway = Flyway.configure().dataSource(url, "sa", "")
        .locations("classpath:db/migration").load();
    assertEquals(1, flyway.migrate().migrationsExecuted);
    assertEquals(MigrationVersion.fromVersion("1"), flyway.info().current().getVersion());
    assertTrue(flyway.validateWithResult().validationSuccessful);
    try (var connection = DriverManager.getConnection(url, "sa", "");
         var statement = connection.createStatement()) {
      for (String query : new String[] {
          "SELECT auth_version, deleted_at FROM users",
          "SELECT storage_backend, deleted_at, favorite, hidden, index_revision FROM drive_files",
          "SELECT extraction_code, extraction_code_hash FROM share_links",
          "SELECT file_id FROM transfer_records",
          "SELECT file_id, file_name, file_size FROM chat_messages",
          "SELECT generation FROM claw_documents",
          "SELECT lease_token FROM claw_tasks",
          "SELECT image_data FROM user_avatars",
          "SELECT total_chunks FROM upload_sessions",
          "SELECT revision FROM ai_index_tasks",
          "SELECT storage_key FROM storage_cleanup_tasks",
          "SELECT room_id FROM chat_members",
          "SELECT direct_key FROM chat_rooms"}) {
        try (var rows = statement.executeQuery(query)) { assertFalse(rows.next(), query); }
      }
    }
    assertEquals(0, flyway.migrate().migrationsExecuted);
    assertDoesNotThrow(flyway::validate);
  }
}
