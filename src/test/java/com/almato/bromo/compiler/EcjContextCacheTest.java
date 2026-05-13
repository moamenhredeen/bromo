package com.almato.bromo.compiler;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.almato.bromo.workspace.FileStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Verifies the M4.5 caching behaviour of [EcjContext].
final class EcjContextCacheTest {

    @Test
    @DisplayName("second compileWorkspace with no changes returns the same map instance")
    void cachedWhenUnchanged(@TempDir Path tmp) throws IOException {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        Files.writeString(src.resolve("Foo.java"), """
                package example;
                public class Foo { int x; }
                """, StandardCharsets.UTF_8);

        try (var ctx = new EcjContext(new FileStore(),
                List.of(tmp.resolve("src/main/java")), List.of())) {
            Map<?, ?> first  = ctx.compileWorkspace();
            Map<?, ?> second = ctx.compileWorkspace();
            assertSame(first, second, "cached compile should return the same map instance");
        }
    }

    @Test
    @DisplayName("editing a closed file invalidates the cache")
    void diskEditInvalidatesCache(@TempDir Path tmp) throws Exception {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var file = src.resolve("Bar.java");
        Files.writeString(file, """
                package example;
                public class Bar { int x; }
                """, StandardCharsets.UTF_8);

        try (var ctx = new EcjContext(new FileStore(),
                List.of(tmp.resolve("src/main/java")), List.of())) {
            var first = ctx.compileWorkspace();
            // Bump mtime + content so signatureOf changes.
            Thread.sleep(10);
            Files.writeString(file, """
                    package example;
                    public class Bar { int x = "no"; }
                    """, StandardCharsets.UTF_8);
            var second = ctx.compileWorkspace();
            assertNotSame(first, second,
                    "edit should invalidate cache and trigger fresh compile");
            assertTrue(second.values().stream()
                            .anyMatch(diags -> diags.stream()
                                    .anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR)),
                    "expected an ERROR diagnostic after the breaking edit");
        }
    }

    @Test
    @DisplayName("editing an open document via FileStore invalidates the cache")
    void overlayEditInvalidatesCache(@TempDir Path tmp) throws Exception {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var file = src.resolve("Baz.java");
        Files.writeString(file, """
                package example;
                public class Baz {}
                """, StandardCharsets.UTF_8);

        var fs = new FileStore();
        try (var ctx = new EcjContext(fs,
                List.of(tmp.resolve("src/main/java")), List.of())) {
            var first = ctx.compileWorkspace();
            // Open with a deliberately broken overlay.
            fs.openDocument(file.toUri(), "java", """
                    package example;
                    public class Baz { int oops = "no"; }
                    """);
            var second = ctx.compileWorkspace();
            assertNotSame(first, second,
                    "opening with a different content should invalidate cache");
        }
    }

    private static Path mkdirs(Path p) throws IOException {
        Files.createDirectories(p);
        return p;
    }
}
