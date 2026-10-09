package com.cendodrive.share;

import com.cendodrive.user.User;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:share_codes;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ShareCodePersistenceTest {
  @Autowired TestEntityManager entities;
  @Autowired ShareLinkRepository links;

  @Test void ownerCodeSurvivesDatabaseReloadAlongsideLegacyAndUnprotectedShares() {
    User owner = entities.persistAndFlush(new User("share_codes_owner", "hash", "Owner"));
    User other = entities.persistAndFlush(new User("share_codes_other", "hash", "Other"));
    String hash = new BCryptPasswordEncoder().encode("A1b2");
    ShareLink saved = ShareLink.create(owner.getId(), 42L, "a".repeat(32), "code.txt", 5, Instant.now(), 60);
    saved.protect(hash, "A1b2"); links.saveAndFlush(saved);
    ShareLink legacy = ShareLink.create(owner.getId(), 43L, "b".repeat(32), "legacy.txt", 5, Instant.now(), 60);
    legacy.protect(hash); links.saveAndFlush(legacy);
    ShareLink open = ShareLink.create(owner.getId(), 44L, "c".repeat(32), "open.txt", 5, Instant.now(), 60);
    links.saveAndFlush(open); entities.clear();
    ShareLink reloaded = links.findById(saved.getId()).orElseThrow();
    assertEquals("A1b2", ShareDtos.ShareResponse.from(reloaded).extractionCode());
    assertEquals(hash, reloaded.getExtractionCodeHash());
    assertEquals(3, links.findAllByOwnerIdOrderByCreatedAtDesc(owner.getId()).size());
    var old = ShareDtos.ShareResponse.from(links.findById(legacy.getId()).orElseThrow());
    assertTrue(old.hasExtractionCode()); assertNull(old.extractionCode());
    var unprotected = ShareDtos.ShareResponse.from(links.findById(open.getId()).orElseThrow());
    assertFalse(unprotected.hasExtractionCode()); assertNull(unprotected.extractionCode());
    assertTrue(links.findAllByOwnerIdOrderByCreatedAtDesc(other.getId()).isEmpty());
  }
}
