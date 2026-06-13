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

final class WorkspaceScannerTest {

    private final WorkspaceScanner scanner = new WorkspaceScanner();

    @Test
    @DisplayName("scan walks source roots and produces a populated index")
    void scansAndIndexes(@TempDir Path tmp) throws IOException {
        var src = tmp.resolve("src/main/java/example");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Foo.java"), """
                package example;
                public class Foo {
                    public void greet() {}
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(src.resolve("Bar.java"), """
                package example;
                public class Bar {
                    public int total() { return 0; }
                }
                """, StandardCharsets.UTF_8);

        var result = scanner.scan(List.of(tmp.resolve("src/main/java")));

        assertEquals(2, result.fileCount());
        assertTrue(result.elapsedMs() >= 0);
        var idx = result.index();
        assertTrue(idx.findExact("Foo").size() == 1);
        assertTrue(idx.findExact("Bar").size() == 1);
        assertTrue(idx.findExact("greet").size() == 1);
        assertTrue(idx.findExact("total").size() == 1);
    }

    @Test
    @DisplayName("scan on bromo itself finds known classes — M3 acceptance proxy")
    void scansBromoItself() {
        var root = Path.of(".").toAbsolutePath().normalize();
        var result = scanner.scan(List.of(
                root.resolve("src/main/java"),
                root.resolve("src/test/java")));

        // Acceptance: scan finished and indexed plenty of symbols.
        assertTrue(result.fileCount() > 10, "expected >10 java files, got " + result.fileCount());
        assertTrue(result.elapsedMs() < 5_000,
                "M3 acceptance: scan <5s, was " + result.elapsedMs() + "ms");

        var idx = result.index();
        // Spot-check classes we know live in this repo
        assertTrue(idx.findExact("BromoLanguageServer").size() >= 1);
        assertTrue(idx.findExact("PieceTable").size() >= 1);
        assertTrue(idx.findExact("SymbolIndex").size() >= 1);

        // Prefix lookup
        var prefixHits = idx.findByPrefix("Piece", 10);
        assertTrue(prefixHits.stream().anyMatch(d -> d.name().equals("PieceTable")));
    }

    @Test
    @DisplayName("scan tolerates non-existent source roots")
    void scanIgnoresMissingRoots(@TempDir Path tmp) {
        var result = scanner.scan(List.of(tmp.resolve("does/not/exist")));
        assertEquals(0, result.fileCount());
    }
}
