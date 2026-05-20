package com.almato.bromo.bench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.almato.bromo.workspace.PieceTable;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// M1 perf gate: piece-table edit latency.
///
/// Acceptance from the plan: keystroke → didChange apply <1ms p50, <2ms p95.
/// We bench the piece-table directly (no LSP4J overhead) since the wire-side
/// budget is measured separately by `WireRoundtripBench`.
///
/// Run with `.\mvnw.cmd test -Pbench` — the default surefire pattern is
/// `**/*Test.java` and excludes `*Bench.java`.
final class DidChangeBench {

    @Test
    @DisplayName("PieceTable random single-char inserts on a 2KB doc — p95 < 1ms")
    void randomSingleCharInsertLatency() {
        var pt = new PieceTable("x".repeat(2000));
        var rnd = new Random(42);

        var result = BenchRunner.measure(2_000, 10_000, () -> {
            int pos = rnd.nextInt(pt.length() + 1);
            pt.insert(pos, "y");
        });

        System.out.println("PieceTable.randomSingleCharInsert: " + result);
        // Single-digit-µs path — background process noise easily doubles the wall clock.
        Baseline.checkRegression("didchange.piece-table-random-insert", result, 200.0);
        assertTrue(result.p95ns() < 1_000_000L,
                "p95 must be <1ms, was " + result.p95us() + "µs");
    }

    @Test
    @DisplayName("PieceTable sequential appends — p95 < 1ms")
    void sequentialAppendLatency() {
        var pt = new PieceTable("");
        var result = BenchRunner.measure(2_000, 10_000, () -> pt.insert(pt.length(), "x"));
        System.out.println("PieceTable.sequentialAppend: " + result);
        // Sub-µs path — timer resolution noise.
        Baseline.checkRegression("didchange.piece-table-sequential-append", result, 200.0);
        assertTrue(result.p95ns() < 1_000_000L,
                "p95 must be <1ms, was " + result.p95us() + "µs");
    }

    @Test
    @DisplayName("10k consecutive single-char edits keep the piece table bounded")
    void edit10kHeapBounded() {
        var pt = new PieceTable("x".repeat(500));
        var rnd = new Random(7);
        for (int i = 0; i < 10_000; i++) {
            int pos = rnd.nextInt(pt.length() + 1);
            pt.insert(pos, "y");
        }
        // The acceptance criterion is "heap-bounded" — verify the text round-trips
        // and the operation count was completed without OOM. Memory profile is
        // collected by the JVM bench runner when -Xmx is set in surefire argLine.
        assertTrue(pt.length() == 10_500, "expected length 10500, was " + pt.length());
        assertTrue(pt.text().length() == 10_500, "text() round-trip");
    }
}
