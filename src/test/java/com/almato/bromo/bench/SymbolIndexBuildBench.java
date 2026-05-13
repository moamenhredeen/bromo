package com.almato.bromo.bench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.almato.bromo.symbol.WorkspaceScanner;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// M3 perf gate: full workspace scan against bromo's own source tree.
///
/// Acceptance from the plan: scan ≤1000 files in <5 s. bromo itself has far
/// fewer than 1000 files at this milestone — the bench mainly produces a
/// baseline for regression tracking. Lookup latency is also captured.
final class SymbolIndexBuildBench {

    @Test
    @DisplayName("Scan bromo + run workspace-symbol lookups — M3 acceptance proxy")
    void scanAndLookup() {
        var scanner = new WorkspaceScanner();
        var roots = List.of(
                Path.of("src/main/java").toAbsolutePath(),
                Path.of("src/test/java").toAbsolutePath());

        // Warm up the JIT against the same workload.
        scanner.scan(roots);

        var scanResult = BenchRunner.measure(0, 5, () -> scanner.scan(roots));
        long p95scan = scanResult.p95ns() / 1_000_000L;
        System.out.println("WorkspaceScanner.scan(bromo): "
                + "p50=" + (scanResult.p50ns() / 1_000_000L) + "ms "
                + "p95=" + p95scan + "ms");

        // Build one index for the lookup bench.
        var index = scanner.scan(roots).index();
        System.out.println("Index size: " + index.size() + " descriptors");

        var lookupResult = BenchRunner.measure(2_000, 20_000, () -> index.findByPrefix("Pie", 20));
        System.out.println("SymbolIndex.findByPrefix(\"Pie\"): " + lookupResult);

        assertTrue(p95scan < 5_000,
                "M3 acceptance: scan p95 must be <5s; was " + p95scan + "ms");
        assertTrue(lookupResult.p95ns() < 30_000_000L,
                "M3 acceptance: prefix lookup p95 must be <30ms; was " + lookupResult.p95us() / 1000.0 + "ms");
    }
}
