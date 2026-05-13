package com.almato.bromo.jdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JdkProviderTest {

    @Test
    @DisplayName("auto-discovers src.zip from java.home when present")
    void discoversFromJavaHome(@TempDir Path tmp) {
        var provider = new JdkProvider(tmp.resolve("cache"));
        // Skip on JRE-only test environments (rare in modern JDK distributions).
        assumeTrue(provider.available(),
                "JDK does not ship src.zip — skipping; this is only a JRE.");
    }

    @Test
    @DisplayName("extracts java.lang.String into the cache directory")
    void extractsStringSource(@TempDir Path tmp) throws IOException {
        var provider = new JdkProvider(tmp.resolve("cache"));
        assumeTrue(provider.available(),
                "JDK does not ship src.zip — skipping.");

        var resolved = provider.resolveSource("java.base", "java.lang", "String");
        assertTrue(resolved.isPresent(), "expected to extract java.lang.String");
        var content = Files.readString(resolved.get(), StandardCharsets.UTF_8);
        assertTrue(content.contains("public final class String"),
                "extracted file should contain the String class declaration");
        // Second call must hit the cache (no re-extract).
        var again = provider.resolveSource("java.base", "java.lang", "String");
        assertEquals(resolved.get(), again.orElseThrow());
    }

    @Test
    @DisplayName("returns empty when src.zip lacks the requested entry")
    void unknownTypeReturnsEmpty(@TempDir Path tmp) {
        var provider = new JdkProvider(tmp.resolve("cache"));
        assumeTrue(provider.available());
        assertFalse(provider.resolveSource("java.base", "java.lang", "NotARealClass").isPresent());
    }
}
