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
    @DisplayName("disk edit + markDirty invalidates the cache")
    void diskEditWithMarkDirtyInvalidatesCache(@TempDir Path tmp) throws Exception {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var file = src.resolve("Bar.java");
        Files.writeString(file, """
                package example;
                public class Bar { int x; }
                """, StandardCharsets.UTF_8);

        try (var ctx = new EcjContext(new FileStore(),
                List.of(tmp.resolve("src/main/java")), List.of())) {
            var first = ctx.compileWorkspace();
            // Bump mtime + content so the eventual signature comparison
            // sees a change.
            Thread.sleep(10);
            Files.writeString(file, """
                    package example;
                    public class Bar { int x = "no"; }
                    """, StandardCharsets.UTF_8);
            // External (non-FileStore) edits are the LSP layer's responsibility
            // to surface via didChangeWatchedFiles → markDirty().
            ctx.markDirty();
            var second = ctx.compileWorkspace();
            assertNotSame(first, second,
                    "edit + markDirty should invalidate cache and trigger fresh compile");
            assertTrue(second.values().stream()
                            .anyMatch(diags -> diags.stream()
                                    .anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR)),
                    "expected an ERROR diagnostic after the breaking edit");
        }
    }

    @Test
    @DisplayName("disk edit without markDirty returns the cached map — contract is explicit")
    void diskEditWithoutMarkDirtyReturnsCache(@TempDir Path tmp) throws Exception {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var file = src.resolve("Quux.java");
        Files.writeString(file, """
                package example;
                public class Quux { int x; }
                """, StandardCharsets.UTF_8);

        try (var ctx = new EcjContext(new FileStore(),
                List.of(tmp.resolve("src/main/java")), List.of())) {
            var first = ctx.compileWorkspace();
            Thread.sleep(10);
            Files.writeString(file, """
                    package example;
                    public class Quux { int x = "no"; }
                    """, StandardCharsets.UTF_8);
            // No markDirty → the dirty-flag fast path returns the cached
            // result. This is the v0 contract: external file edits do not
            // invalidate without explicit notification.
            var second = ctx.compileWorkspace();
            assertSame(first, second,
                    "disk edit alone (no markDirty) must not invalidate the dirty-flag cache");
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
