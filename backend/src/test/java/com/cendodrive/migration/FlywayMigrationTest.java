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

  @Test void freshDatabaseAppliesAllMigrations() {
    Flyway flyway = current(databaseUrl());
    assertEquals(17, flyway.migrate().migrationsExecuted);
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
    assertEquals(9, flyway.migrate().migrationsExecuted);
    assertDoesNotThrow(flyway::validate);
    assertEquals(MigrationVersion.fromVersion("17"), flyway.info().current().getVersion());
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

  @Test void existingTransferAndShareMigrationsUpgradeWithoutRewritingHistory() throws Exception {
    // Frozen fixtures reproduce the database shared by the development worktrees.
    for (int version = 1; version <= 16; version++) {
      final int v = version;
      String name;
      if (version <= 13) {
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
          name = files.map(path -> path.getFileName().toString())
              .filter(file -> file.startsWith("V" + v + "__")).findFirst().orElseThrow();
        }
      } else {
        name = switch (version) {
          case 14 -> "V14__create_transfer_records.sql";
          case 15 -> "V15__transfer_file_reference.sql";
          default -> "V16__persist_share_extraction_code.sql";
        };
      }
      String folder = version >= 14 ? "db/migration-history/" : "db/migration/";
      try (InputStream input = getClass().getClassLoader().getResourceAsStream(folder + name)) {
        assertNotNull(input, name);
        Files.copy(input, history.resolve(name));
      }
    }
    String url = databaseUrl();
    Flyway.configure().dataSource(url, "sa", "").locations("filesystem:" + history).load().migrate();
    Flyway upgrade = current(url);
    assertEquals(1, assertDoesNotThrow(upgrade::migrate, "Existing V14-V16 must keep their checksums").migrationsExecuted);
    assertTrue(upgrade.validateWithResult().validationSuccessful);
    assertEquals(MigrationVersion.fromVersion("17"), upgrade.info().current().getVersion());
    try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
      assertDoesNotThrow(() -> statement.executeQuery("SELECT file_id FROM transfer_records"));
      assertDoesNotThrow(() -> statement.executeQuery("SELECT extraction_code FROM share_links"));
      assertDoesNotThrow(() -> statement.executeQuery("SELECT generation FROM claw_documents"));
      assertDoesNotThrow(() -> statement.executeQuery("SELECT lease_token FROM claw_tasks"));
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
