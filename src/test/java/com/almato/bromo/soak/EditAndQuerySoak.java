package com.almato.bromo.soak;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.almato.bromo.compiler.EcjContext;
import com.almato.bromo.compiler.LibrarySourceProvider;
import com.almato.bromo.compiler.SourceResolver;
import com.almato.bromo.features.CompletionFeature;
import com.almato.bromo.features.HoverFeature;
import com.almato.bromo.jdk.JdkProvider;
import com.almato.bromo.query.QueryEngine;
import com.almato.bromo.symbol.SymbolIndex;
import com.almato.bromo.symbol.WorkspaceScanner;
import com.almato.bromo.util.CancelToken;
import com.almato.bromo.workspace.Document;
import com.almato.bromo.workspace.FileStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// 1000-edit soak with interleaved hover + completion requests.
///
/// Runs under the `soak` profile (excluded from `mvn test` by default so it
/// doesn't slow down the unit suite). Builds a small synthetic workspace,
/// opens one file, then drives random edits at the piece-table while every
/// Nth edit also fires a hover and a completion. Captures p50/p95/p99 of
/// each operation independently and bounds heap growth.
///
/// Goals (from `.claude/plans/i-want-to-build-snug-canyon.md`):
/// - **No Thread.sleep**: tight back-to-back loop, no artificial cadence —
///   that's what CLAUDE.md mandates for tests anyway.
/// - **p99 didChange < 5ms** on the apply path.
/// - **p99 completion < 100ms** on the prefix path.
/// - **p99 hover < 500ms** with QueryEngine caching.
/// - **Heap growth ≤ 2× initial** post-GC. Looser than the plan's 1.5×
///   because GC scheduling under ZGC is async — the test gives the JVM two
///   full collections to settle before measuring.
final class EditAndQuerySoak {

    private static final int EDIT_ITERATIONS = 1000;
    private static final int QUERY_EVERY_N_EDITS = 10;
    private static final long EDIT_P99_BUDGET_NS = 5_000_000L;      // 5ms
    private static final long COMPLETION_P99_BUDGET_NS = 100_000_000L; // 100ms
    private static final long HOVER_P99_BUDGET_NS = 500_000_000L;   // 500ms

    @Test
    @DisplayName("1000 edits + interleaved hover/completion stay within latency + heap bounds")
    void editAndQuerySoak(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("workspace");
        Path src = mkdirs(root.resolve("src/main/java/soak"));
        for (int i = 0; i < 10; i++) {
            Files.writeString(src.resolve("Type" + i + ".java"), """
                    package soak;
                    public class Type%d {
                        public String greet() { return "hi"; }
                        public int compute(int x) { return x * %d; }
                    }
                    """.formatted(i, i), StandardCharsets.UTF_8);
        }

        String mainSource = """
                package soak;
                public class Main {
                    public static void main(String[] args) {
                        Type0 t = new Type0();
                        System.out.println(t.greet());
                    }
                }
                """;
        Path mainFile = src.resolve("Main.java");
        Files.writeString(mainFile, mainSource, StandardCharsets.UTF_8);

        var files = new FileStore();
        var doc = files.openDocument(mainFile.toUri(), "java", mainSource);

        try (var ecj = new EcjContext(files, List.of(root.resolve("src/main/java")), List.of());
             var queries = new QueryEngine(files, ecj)) {

            SymbolIndex symbols = new WorkspaceScanner().scan(List.of(root.resolve("src/main/java"))).index();
            var sources = new SourceResolver(
                    new JdkProvider(root.resolve("target/bromo-cache/sources/jdk")),
                    new LibrarySourceProvider(List.of(), root.resolve("target/bromo-cache/sources/lib")));
            var hover = new HoverFeature(ecj, files, sources, symbols, queries);
            var completion = new CompletionFeature(symbols);

            // Warm up ECJ + JIT (the first hover pays the full cold cost).
            hover.hover(mainFile.toUri(), mainSource.indexOf("Type0"), CancelToken.never());

            long[] editTimes = new long[EDIT_ITERATIONS];
            int hoverCount = EDIT_ITERATIONS / QUERY_EVERY_N_EDITS;
            long[] hoverTimes = new long[hoverCount];
            long[] completionTimes = new long[hoverCount];
            int hoverIdx = 0;

            long heapBefore = settledHeapUsage();

            var rnd = new Random(7);
            for (int i = 0; i < EDIT_ITERATIONS; i++) {
                String insert = i % 2 == 0 ? " " : "//\n";
                int pos = rnd.nextInt(doc.length() + 1);

                long s = System.nanoTime();
                doc.applyRangeEdit(pos, 0, insert, files.nextRevision());
                editTimes[i] = System.nanoTime() - s;
                files.notifyEdit(mainFile.toUri());

                if ((i + 1) % QUERY_EVERY_N_EDITS == 0 && hoverIdx < hoverCount) {
                    long h = System.nanoTime();
                    hover.hover(mainFile.toUri(), 50, CancelToken.never());
                    hoverTimes[hoverIdx] = System.nanoTime() - h;

                    long c = System.nanoTime();
                    completion.completionsAt(mainFile.toUri(), doc.content(),
                            Math.min(40, doc.length()), CancelToken.never());
                    completionTimes[hoverIdx] = System.nanoTime() - c;

                    hoverIdx++;
                }
            }

            long heapAfter = settledHeapUsage();

            reportAndAssert("edit-apply",   editTimes,       EDIT_P99_BUDGET_NS);
            reportAndAssert("hover",        hoverTimes,      HOVER_P99_BUDGET_NS);
            reportAndAssert("completion",   completionTimes, COMPLETION_P99_BUDGET_NS);

            double heapRatio = (double) heapAfter / Math.max(1, heapBefore);
            System.out.printf("heap: before=%dKB after=%dKB ratio=%.2f%n",
                    heapBefore / 1024, heapAfter / 1024, heapRatio);
            assertTrue(heapRatio < 2.0,
                    "heap grew %.2fx (before=%d, after=%d) — expected <2x"
                            .formatted(heapRatio, heapBefore, heapAfter));
        }
    }

    private static void reportAndAssert(String label, long[] samples, long p99Budget) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        long p50 = sorted[sorted.length / 2];
        long p95 = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.95))];
        long p99 = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.99))];
        System.out.printf("%-12s p50=%6dµs p95=%6dµs p99=%6dµs (n=%d)%n",
                label, p50 / 1_000, p95 / 1_000, p99 / 1_000, samples.length);
        assertTrue(p99 < p99Budget,
                "%s p99 %dµs exceeds budget %dµs"
                        .formatted(label, p99 / 1_000, p99Budget / 1_000));
    }

    private static long settledHeapUsage() {
        // Two collections + a short yield-loop give the JVM a chance to compact
        // before we read used memory. No Thread.sleep — CLAUDE.md forbids it.
        System.gc();
        Thread.yield();
        System.gc();
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static Path mkdirs(Path p) throws IOException {
        Files.createDirectories(p);
        return p;
    }
}
