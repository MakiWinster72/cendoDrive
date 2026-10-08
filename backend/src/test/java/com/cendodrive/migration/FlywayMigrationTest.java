package com.cendodrive.migration;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FlywayMigrationTest {
  private static final List<String> MIGRATIONS = List.of(
      "V1__create_users.sql", "V2__create_drive_files.sql", "V3__add_storage_backend.sql",
      "V4__add_drive_trash.sql", "V5__create_share_links.sql", "V6__add_file_features.sql",
      "V7__create_upload_sessions.sql", "V8__add_account_lifecycle.sql");
  @TempDir Path history;

  @Test void freshDatabaseAppliesAllTenMigrations() {
    Flyway flyway = current(databaseUrl());
    assertEquals(10, flyway.migrate().migrationsExecuted);
    assertTrue(flyway.validateWithResult().validationSuccessful);
    assertEquals(Integer.valueOf(-1727441758), java.util.Arrays.stream(flyway.info().all())
        .filter(migration -> MigrationVersion.fromVersion("5").equals(migration.getVersion()))
        .findFirst().orElseThrow().getChecksum());
  }

  @Test void databaseWithPreviouslyAppliedV5ValidatesAndPreservesData() throws Exception {
    // V5 is a frozen historical fixture, not read from the production directory:
    // removing or rewriting the production migration must break this test.
    for (String name : MIGRATIONS) {
      String resource = (name.startsWith("V5__") || name.startsWith("V8__")) ? "db/migration-history/" : "db/migration/";
      try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource + name)) {
        assertNotNull(input, name);
        Files.copy(input, history.resolve(name));
      }
    }
    String url = databaseUrl();
    Flyway.configure().dataSource(url, "sa", "").locations("filesystem:" + history).load().migrate();
    try (var connection = DriverManager.getConnection(url, "sa", "");
         var statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO users (id, username, password_hash, nickname, created_at, updated_at) "
          + "VALUES (1, 'migration-owner', 'test-hash', '迁移用户', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
      statement.executeUpdate("INSERT INTO share_links (owner_id, file_id, token, file_name, size_bytes, created_at, expires_at) "
          + "VALUES (1, 42, 'historical-share', '保留文件.txt', 123, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }
    Flyway flyway = current(url);
    assertEquals(2, flyway.migrate().migrationsExecuted);
    assertDoesNotThrow(flyway::validate);
    assertEquals(MigrationVersion.fromVersion("10"), flyway.info().current().getVersion());
    assertEquals(Integer.valueOf(-267370802), java.util.Arrays.stream(flyway.info().all())
        .filter(migration -> MigrationVersion.fromVersion("8").equals(migration.getVersion()))
        .findFirst().orElseThrow().getChecksum());
    try (var connection = DriverManager.getConnection(url, "sa", "");
         var statement = connection.createStatement();
         var rows = statement.executeQuery("SELECT file_name, size_bytes FROM share_links WHERE token = 'historical-share'")) {
      assertTrue(rows.next());
      assertEquals("保留文件.txt", rows.getString(1));
      assertEquals(123, rows.getLong(2));
    }
  }

  private static Flyway current(String url) {
    return Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load();
  }

  private static String databaseUrl() {
    return "jdbc:h2:mem:migrations-" + UUID.randomUUID()
        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
  }
}
