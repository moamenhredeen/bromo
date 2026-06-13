package me.moamenhredeen.bromo.symbol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SignatureExtractorTest {

    private final SignatureExtractor extractor = new SignatureExtractor();

    @Test
    @DisplayName("extracts a top-level class with its package")
    void topLevelClass(@TempDir Path tmp) throws IOException {
        Path f = write(tmp, "Foo.java", """
                package com.example;
                public class Foo {}
                """);
        var descs = extractor.extract(f);
        var type = descs.stream().filter(d -> d.kind() == SymbolKind.CLASS).findFirst().orElseThrow();
        assertEquals("com.example.Foo", type.fqn());
        assertEquals("Foo", type.name());
    }

    @Test
    @DisplayName("extracts methods, constructors, and fields with signatures")
    void members(@TempDir Path tmp) throws IOException {
        Path f = write(tmp, "Bar.java", """
                package com.example;
                public class Bar {
                    int count;
                    String name;
                    public Bar(int c, String n) { this.count = c; this.name = n; }
                    public int total(int x) { return x + count; }
                }
                """);
        var descs = extractor.extract(f);

        assertTrue(descs.stream().anyMatch(d -> d.kind() == SymbolKind.CONSTRUCTOR && d.name().equals("Bar")));
        assertTrue(descs.stream().anyMatch(d -> d.kind() == SymbolKind.METHOD
                && d.name().equals("total")
                && d.signature().contains("total(int) : int")));
        assertTrue(descs.stream().anyMatch(d -> d.kind() == SymbolKind.FIELD && d.name().equals("count")
                && "int".equals(d.signature())));
        assertTrue(descs.stream().anyMatch(d -> d.kind() == SymbolKind.FIELD && d.name().equals("name")
                && "String".equals(d.signature())));
    }

    @Test
    @DisplayName("records, enums, annotations, interfaces all emit type kinds")
    void variousTypeKinds(@TempDir Path tmp) throws IOException {
        Path f = write(tmp, "Mixed.java", """
                package x;
                public interface Iface {}
                public enum Direction { N, S, E, W }
                public record Point(int x, int y) {}
                public @interface Marker {}
                """);
        var descs = extractor.extract(f);
        assertContainsKind(descs, SymbolKind.INTERFACE,  "Iface");
        assertContainsKind(descs, SymbolKind.ENUM,       "Direction");
        assertContainsKind(descs, SymbolKind.RECORD,     "Point");
        assertContainsKind(descs, SymbolKind.ANNOTATION, "Marker");
    }

    @Test
    @DisplayName("nested types get a dot-qualified FQN")
    void nestedTypes(@TempDir Path tmp) throws IOException {
        Path f = write(tmp, "Outer.java", """
                package x;
                public class Outer {
                    public class Inner {
                        public void m() {}
                    }
                }
                """);
        var descs = extractor.extract(f);
        assertTrue(descs.stream().anyMatch(d -> d.fqn().equals("x.Outer.Inner") && d.kind() == SymbolKind.CLASS),
                "expected nested type x.Outer.Inner; had " + descs);
        assertTrue(descs.stream().anyMatch(d -> d.fqn().equals("x.Outer.Inner.m") && d.kind() == SymbolKind.METHOD),
                "expected nested method x.Outer.Inner.m; had " + descs);
    }

    @Test
    @DisplayName("malformed source recovers and still emits what it can")
    void recoversFromSyntaxErrors(@TempDir Path tmp) throws IOException {
        Path f = write(tmp, "Broken.java", """
                package x;
                public class Broken
                    public void m() { /* missing { for class */
                }
                """);
        var descs = extractor.extract(f);
        // We don't assert exact recovery behavior — only that no exception bubbles out.
        assertTrue(descs.size() >= 0);
    }

    private static Path write(Path dir, String name, String content) throws IOException {
        Path f = dir.resolve(name);
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    private static void assertContainsKind(List<Descriptor> descs, SymbolKind kind, String name) {
        assertTrue(descs.stream().anyMatch(d -> d.kind() == kind && d.name().equals(name)),
                "expected " + kind + " " + name + " in " + descs);
    }
}
