package me.moamenhredeen.bromo.arch;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Build-failing enforcement of the hard architectural rules from CLAUDE.md.
///
/// Each rule names the *allowed* path prefixes; everything else under
/// `src/main/java/me/moamenhredeen/bromo` must be free of the banned import.
final class ArchitectureTest {

    private static final Path SRC_MAIN = Path.of("src/main/java/me/moamenhredeen/bromo");

    @Test
    @DisplayName("LSP4J imports stay inside lsp/ and wire/")
    void lsp4jBoundary() throws IOException {
        assertNoMatches(
                Pattern.compile("^\\s*import\\s+org\\.eclipse\\.lsp4j(\\..*)?;",
                        Pattern.MULTILINE),
                List.of("lsp/", "wire/", "util/Cancel.java"),
                "Rule: LSP4J imports must stay within lsp/ and wire/ (and util/Cancel.java for the bridge)");
    }

    @Test
    @DisplayName("CompletableFuture imports stay inside lsp/ and util/Cancel.java")
    void completableFutureBoundary() throws IOException {
        assertNoMatches(
                Pattern.compile("^\\s*import\\s+java\\.util\\.concurrent\\.CompletableFuture\\s*;",
                        Pattern.MULTILINE),
                List.of("lsp/", "util/Cancel.java"),
                "Rule: CompletableFuture must stay within lsp/ and util/Cancel.java");
    }

    @Test
    @DisplayName("Aether imports stay inside project/maven/resolver/")
    void aetherBoundary() throws IOException {
        assertNoMatches(
                Pattern.compile("^\\s*import\\s+org\\.eclipse\\.aether(\\..*)?;",
                        Pattern.MULTILINE),
                List.of("project/maven/resolver/"),
                "Rule: Aether imports must stay within project/maven/resolver/");
    }

    @Test
    @DisplayName("Maven model imports stay inside project/maven/resolver/")
    void mavenBoundary() throws IOException {
        assertNoMatches(
                Pattern.compile("^\\s*import\\s+org\\.apache\\.maven(\\..*)?;",
                        Pattern.MULTILINE),
                List.of("project/maven/resolver/"),
                "Rule: Maven model imports must stay within project/maven/resolver/ (paired with the Aether boundary)");
    }

    @Test
    @DisplayName("Charset.defaultCharset() is never used; UTF-8 is explicit")
    void noDefaultCharset() throws IOException {
        var banned = Pattern.compile("Charset\\.defaultCharset\\s*\\(");
        var violations = new ArrayList<String>();
        try (Stream<Path> stream = Files.walk(SRC_MAIN)) {
            stream.filter(ArchitectureTest::isJava)
                    .forEach(p -> appendIfMatch(p, banned, violations));
        }
        if (!violations.isEmpty()) {
            fail("Rule: never use Charset.defaultCharset() — pass StandardCharsets.UTF_8 explicitly.\n  "
                    + String.join("\n  ", violations));
        }
    }

    // ---------- helpers ----------

    private static boolean isJava(Path p) {
        return p.toString().endsWith(".java");
    }

    private static void assertNoMatches(Pattern banned, List<String> allowedPrefixes, String rule)
            throws IOException {
        var violations = new ArrayList<String>();
        try (Stream<Path> stream = Files.walk(SRC_MAIN)) {
            stream.filter(ArchitectureTest::isJava)
                    .forEach(p -> {
                        var rel = relPath(p);
                        for (var allowed : allowedPrefixes) {
                            if (rel.equals(allowed) || rel.startsWith(allowed)) return;
                        }
                        appendIfMatch(p, banned, violations);
                    });
        }
        if (!violations.isEmpty()) {
            fail(rule + "\n  " + String.join("\n  ", violations));
        }
    }

    private static void appendIfMatch(Path p, Pattern banned, List<String> out) {
        try {
            var content = Files.readString(p, StandardCharsets.UTF_8);
            if (banned.matcher(content).find()) {
                out.add(relPath(p));
            }
        } catch (IOException e) {
            out.add(relPath(p) + " (read failed: " + e.getMessage() + ")");
        }
    }

    private static String relPath(Path p) {
        return SRC_MAIN.relativize(p).toString().replace('\\', '/');
    }
}
