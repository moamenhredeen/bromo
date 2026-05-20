package com.almato.bromo.query;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.almato.bromo.compiler.EcjContext;
import com.almato.bromo.workspace.FileStore;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Verifies [QueryEngine]'s parsed-AST cache for open documents.
final class QueryEngineTest {

    @Test
    @DisplayName("repeated cachedParsedAst at same revision returns identical AST instance")
    void cacheHitsReturnSameAst(@TempDir Path tmp) throws IOException {
        Path src = mkdirs(tmp.resolve("src/main/java/ex"));
        Path file = src.resolve("Foo.java");
        String text = "package ex; public class Foo {}";
        Files.writeString(file, text, StandardCharsets.UTF_8);

        var fs = new FileStore();
        try (var ecj = new EcjContext(fs, List.of(tmp.resolve("src/main/java")), List.of());
             var qe = new QueryEngine(fs, ecj)) {
            URI uri = file.toUri();
            fs.openDocument(uri, "java", text);
            var first = qe.cachedParsedAst(uri).orElseThrow();
            var second = qe.cachedParsedAst(uri).orElseThrow();
            assertSame(first, second, "cache hit should return the same AST instance");
        }
    }

    @Test
    @DisplayName("editing an open document invalidates its cached AST")
    void editInvalidatesAst(@TempDir Path tmp) throws IOException {
        Path src = mkdirs(tmp.resolve("src/main/java/ex"));
        Path file = src.resolve("Foo.java");
        String text = "package ex; public class Foo {}";
        Files.writeString(file, text, StandardCharsets.UTF_8);

        var fs = new FileStore();
        try (var ecj = new EcjContext(fs, List.of(tmp.resolve("src/main/java")), List.of());
             var qe = new QueryEngine(fs, ecj)) {
            URI uri = file.toUri();
            var doc = fs.openDocument(uri, "java", text);
            var first = qe.cachedParsedAst(uri).orElseThrow();

            doc.applyFullChange("package ex; public class Foo { int x; }", fs.nextRevision());
            fs.notifyEdit(uri);

            var second = qe.cachedParsedAst(uri).orElseThrow();
            assertNotSame(first, second, "edit must invalidate the cached AST");
        }
    }

    @Test
    @DisplayName("cachedParsedAst for a non-open URI returns empty")
    void closedDocumentReturnsEmpty(@TempDir Path tmp) throws IOException {
        Path src = mkdirs(tmp.resolve("src/main/java/ex"));
        Path file = src.resolve("Foo.java");
        Files.writeString(file, "package ex; public class Foo {}", StandardCharsets.UTF_8);

        var fs = new FileStore();
        try (var ecj = new EcjContext(fs, List.of(tmp.resolve("src/main/java")), List.of());
             var qe = new QueryEngine(fs, ecj)) {
            assertTrue(qe.cachedParsedAst(file.toUri()).isEmpty(),
                    "closed document must not be cached or materialised");
        }
    }

    @Test
    @DisplayName("closing a document drops its cache entry")
    void closeDropsCache(@TempDir Path tmp) throws IOException {
        Path src = mkdirs(tmp.resolve("src/main/java/ex"));
        Path file = src.resolve("Foo.java");
        String text = "package ex; public class Foo {}";
        Files.writeString(file, text, StandardCharsets.UTF_8);

        var fs = new FileStore();
        try (var ecj = new EcjContext(fs, List.of(tmp.resolve("src/main/java")), List.of());
             var qe = new QueryEngine(fs, ecj)) {
            URI uri = file.toUri();
            fs.openDocument(uri, "java", text);
            qe.cachedParsedAst(uri).orElseThrow();
            assertTrue(qe.cachedSize() >= 1);

            fs.closeDocument(uri);
            assertTrue(qe.cachedParsedAst(uri).isEmpty());
        }
    }

    @Test
    @DisplayName("reopening with new content produces a fresh AST")
    void reopenInvalidatesAst(@TempDir Path tmp) throws IOException {
        Path src = mkdirs(tmp.resolve("src/main/java/ex"));
        Path file = src.resolve("Foo.java");
        String text = "package ex; public class Foo {}";
        Files.writeString(file, text, StandardCharsets.UTF_8);

        var fs = new FileStore();
        try (var ecj = new EcjContext(fs, List.of(tmp.resolve("src/main/java")), List.of());
             var qe = new QueryEngine(fs, ecj)) {
            URI uri = file.toUri();
            fs.openDocument(uri, "java", text);
            var first = qe.cachedParsedAst(uri).orElseThrow();

            fs.openDocument(uri, "java", "package ex; public class Foo { void m() {} }");
            var second = qe.cachedParsedAst(uri).orElseThrow();
            assertNotSame(first, second, "reopen must produce a fresh AST");
        }
    }

    @Test
    @DisplayName("cachedSnapshot returns the source char[] alongside the AST and stays consistent across calls")
    void cachedSnapshotReturnsSourceWithAst(@TempDir Path tmp) throws IOException {
        Path src = mkdirs(tmp.resolve("src/main/java/ex"));
        Path file = src.resolve("Foo.java");
        String text = "package ex; public class Foo {}";
        Files.writeString(file, text, StandardCharsets.UTF_8);

        var fs = new FileStore();
        try (var ecj = new EcjContext(fs, List.of(tmp.resolve("src/main/java")), List.of());
             var qe = new QueryEngine(fs, ecj)) {
            URI uri = file.toUri();
            fs.openDocument(uri, "java", text);
            var first = qe.cachedSnapshot(uri).orElseThrow();
            var second = qe.cachedSnapshot(uri).orElseThrow();
            assertSame(first.ast(), second.ast(), "cache hit must reuse the AST");
            assertSame(first.source(), second.source(), "cache hit must reuse the source array");
            assertTrue(new String(first.source()).equals(text), "source must round-trip the document text");
        }
    }

    private static Path mkdirs(Path p) throws IOException {
        Files.createDirectories(p);
        return p;
    }
}
