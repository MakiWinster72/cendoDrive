package com.cendodrive.file;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FileNamePolicyTest {
    private final FileNamePolicy names = new FileNamePolicy();
    @Test void normalizesWhitespaceCaseAndUnicode() {
        var result = names.normalize("  Cafe\u0301.PDF  ");
        assertEquals("Café.PDF", result.display());
        assertEquals("café.pdf", result.key());
        assertEquals(names.normalize("CAFÉ.pdf").key(), result.key());
    }
    @Test void rejectsInvalidNames() {
        for (String value : new String[]{null, "", "  ", ".", "..", "a/b", "a\\b", "a\u0000b", "a\nb", "a\n", "\ta", "x".repeat(256)}) {
            assertEquals("INVALID_NAME", assertThrows(FileBusinessException.class, () -> names.normalize(value)).code());
        }
    }
}
