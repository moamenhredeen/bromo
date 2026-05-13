package com.almato.bromo.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.almato.bromo.compiler.EcjContext;
import com.almato.bromo.util.CancelToken;
import com.almato.bromo.workspace.FileStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MemberCompletionResolverTest {

    @Test
    @DisplayName("isAfterDot identifies cursor positions right after a dot")
    void isAfterDotDetection() {
        assertTrue(MemberCompletionResolver.isAfterDot("foo.", 4));
        assertTrue(MemberCompletionResolver.isAfterDot("foo.bar", 7));     // mid identifier
        assertFalse(MemberCompletionResolver.isAfterDot("foo", 3));         // no dot
        assertFalse(MemberCompletionResolver.isAfterDot("foo ", 4));        // space after
        assertFalse(MemberCompletionResolver.isAfterDot("", 0));
    }

    @Test
    @DisplayName("instance member access on a local field — list its methods/fields")
    void instanceMemberAccess(@TempDir Path tmp) throws IOException {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var file = src.resolve("Holder.java");
        var source = """
                package example;
                public class Holder {
                    public int count;
                    public String label;
                    public int total(int x) { return x + count; }
                    void use() {
                        Holder h = new Holder();
                        h.
                    }
                }
                """;
        Files.writeString(file, source, StandardCharsets.UTF_8);

        var resolver = newResolver(tmp);
        int dotOffset = source.indexOf("h.\n") + 2; // right after the dot
        var result = resolver.tryComplete(file.toUri(), source, dotOffset, CancelToken.never());
        assertTrue(result.isPresent(), "expected member completion");

        var labels = result.get().items().stream().map(CompletionItem::label).toList();
        assertTrue(labels.contains("count"),  "expected field 'count'; had " + labels);
        assertTrue(labels.contains("label"),  "expected field 'label'; had " + labels);
        assertTrue(labels.contains("total"),  "expected method 'total'; had " + labels);
    }

    @Test
    @DisplayName("instance member access on a String literal — JDK methods come through")
    void stringLiteralMembers(@TempDir Path tmp) throws IOException {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var file = src.resolve("Use.java");
        var source = """
                package example;
                public class Use {
                    void run() {
                        int n = "hello".
                    }
                }
                """;
        Files.writeString(file, source, StandardCharsets.UTF_8);

        var resolver = newResolver(tmp);
        int dotOffset = source.indexOf("\"hello\".\n") + "\"hello\".".length();
        var result = resolver.tryComplete(file.toUri(), source, dotOffset, CancelToken.never());
        assertTrue(result.isPresent(), "expected member completion on String literal");
        var labels = result.get().items().stream().map(CompletionItem::label).toList();
        assertTrue(labels.contains("length"),  "expected length() in completions; had " + labels);
        assertTrue(labels.contains("charAt"),  "expected charAt() in completions; had " + labels);
    }

    @Test
    @DisplayName("partial typed prefix narrows the candidate list")
    void partialPrefixNarrowsList(@TempDir Path tmp) throws IOException {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var file = src.resolve("Use.java");
        var source = """
                package example;
                public class Use {
                    void run() {
                        int n = "hello".le
                    }
                }
                """;
        Files.writeString(file, source, StandardCharsets.UTF_8);

        var resolver = newResolver(tmp);
        int prefixEnd = source.indexOf(".le") + 3;  // cursor just past 'e'
        var result = resolver.tryComplete(file.toUri(), source, prefixEnd, CancelToken.never());
        assertTrue(result.isPresent());
        var labels = result.get().items().stream().map(CompletionItem::label).toList();
        assertFalse(labels.contains("charAt"),  "expected charAt() to be filtered out; had " + labels);
        assertTrue(labels.contains("length"),   "expected length() to match prefix 'le'; had " + labels);
    }

    @Test
    @DisplayName("static member access via type name — class methods come through")
    void staticAccess(@TempDir Path tmp) throws IOException {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var file = src.resolve("Use.java");
        var source = """
                package example;
                public class Use {
                    void run() {
                        int n = Math.ma
                    }
                }
                """;
        Files.writeString(file, source, StandardCharsets.UTF_8);

        var resolver = newResolver(tmp);
        int cursor = source.indexOf(".ma") + 3; // right after the "ma" prefix
        var result = resolver.tryComplete(file.toUri(), source, cursor, CancelToken.never());
        assertTrue(result.isPresent(), "expected static member completion");
        var labels = result.get().items().stream().map(CompletionItem::label).toList();
        assertTrue(labels.contains("max"), "expected static Math.max for prefix 'ma'; had " + labels);
    }

    @Test
    @DisplayName("non-member cursor positions return Optional.empty")
    void nonMemberContextReturnsEmpty(@TempDir Path tmp) throws IOException {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var file = src.resolve("Plain.java");
        var source = """
                package example;
                public class Plain {
                    int x;
                }
                """;
        Files.writeString(file, source, StandardCharsets.UTF_8);

        var resolver = newResolver(tmp);
        // Position right after "int"
        int offset = source.indexOf("int ") + 3;
        assertEquals(java.util.Optional.empty(),
                resolver.tryComplete(file.toUri(), source, offset, CancelToken.never()));
    }

    private MemberCompletionResolver newResolver(Path tmp) {
        var fs = new FileStore();
        var ctx = new EcjContext(fs, List.of(tmp.resolve("src/main/java")), List.of());
        return new MemberCompletionResolver(ctx);
    }

    private static Path mkdirs(Path p) throws IOException {
        Files.createDirectories(p);
        return p;
    }
}
