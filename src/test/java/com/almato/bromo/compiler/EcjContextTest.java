package com.almato.bromo.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.almato.bromo.workspace.FileStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EcjContextTest {

    @Test
    @DisplayName("clean source — no diagnostics produced")
    void cleanSource(@TempDir Path tmp) throws IOException {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        Files.writeString(src.resolve("Foo.java"), """
                package example;
                public class Foo {
                    public int total(int x) { return x + 1; }
                }
                """, StandardCharsets.UTF_8);

        var fs = new FileStore();
        var ctx = new EcjContext(fs, List.of(tmp.resolve("src/main/java")), List.of());
        var diags = ctx.compileWorkspace();
        assertEquals(0, diags.values().stream().mapToInt(List::size).sum(), "expected zero diagnostics");
    }

    @Test
    @DisplayName("type mismatch — produces an error diagnostic at the offending offset")
    void typeMismatchSurfacesError(@TempDir Path tmp) throws IOException {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var file = src.resolve("Bad.java");
        Files.writeString(file, """
                package example;
                public class Bad {
                    public int wrong() { String s = 42; return 0; }
                }
                """, StandardCharsets.UTF_8);

        var fs = new FileStore();
        var ctx = new EcjContext(fs, List.of(tmp.resolve("src/main/java")), List.of());
        var diags = ctx.compileWorkspace();

        var fileDiags = diags.get(file.toUri());
        assertNotNull(fileDiags, "diagnostics map missing entry for " + file);
        assertTrue(fileDiags.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR),
                "expected at least one ERROR, got: " + fileDiags);
    }

    @Test
    @DisplayName("open document overlay overrides disk contents during compile")
    void overlayOverridesDisk(@TempDir Path tmp) throws IOException {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var file = src.resolve("Live.java");
        Files.writeString(file, """
                package example;
                public class Live {}
                """, StandardCharsets.UTF_8);

        var fs = new FileStore();
        // Open with broken content even though disk is clean.
        fs.openDocument(file.toUri(), "java", """
                package example;
                public class Live {
                    int bad = "no";
                }
                """);

        var ctx = new EcjContext(fs, List.of(tmp.resolve("src/main/java")), List.of());
        var diags = ctx.compileWorkspace();
        var fileDiags = diags.get(file.toUri());
        assertTrue(fileDiags != null && !fileDiags.isEmpty(),
                "expected at least one diagnostic from the overlay, got " + fileDiags);
    }

    @Test
    @DisplayName("cross-file references resolve within the input unit set")
    void crossFileReferences(@TempDir Path tmp) throws IOException {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        Files.writeString(src.resolve("Helper.java"), """
                package example;
                public class Helper {
                    public static int doubled(int x) { return x * 2; }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(src.resolve("User.java"), """
                package example;
                public class User {
                    public int run() { return Helper.doubled(21); }
                }
                """, StandardCharsets.UTF_8);

        var ctx = new EcjContext(new FileStore(),
                List.of(tmp.resolve("src/main/java")), List.of());
        var diags = ctx.compileWorkspace();

        int total = diags.values().stream().mapToInt(List::size).sum();
        assertEquals(0, total, "expected no diagnostics, got: " + diags);
    }

    private static Path mkdirs(Path p) throws IOException {
        Files.createDirectories(p);
        return p;
    }
}
