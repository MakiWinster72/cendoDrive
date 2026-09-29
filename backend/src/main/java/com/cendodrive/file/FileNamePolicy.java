package com.cendodrive.file;

import java.text.Normalizer;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class FileNamePolicy {
    public record Name(String display, String key) {}

    public Name normalize(String input) {
        if (input == null) throw invalid();
        if (input.codePoints().anyMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.SURROGATE)) throw invalid();
        String display = Normalizer.normalize(input.strip(), Normalizer.Form.NFC);
        String key = Normalizer.normalize(display.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
        if (display.isBlank() || display.equals(".") || display.equals("..")
                || display.length() > 255 || key.length() > 255
                || display.codePoints().anyMatch(c -> c == '/' || c == '\\')) throw invalid();
        return new Name(display, key);
    }
    private FileBusinessException invalid() {
        return FileBusinessException.invalid("INVALID_NAME", "Name must contain 1-255 characters without separators or control characters");
    }
}
