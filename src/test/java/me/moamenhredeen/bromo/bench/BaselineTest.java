package me.moamenhredeen.bromo.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.moamenhredeen.bromo.bench.BenchRunner.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Unit tests for the bench-baseline store and regression check.
final class BaselineTest {

    @Test
    @DisplayName("store then load round-trips the same percentile values")
    void storeLoadRoundTrip(@TempDir Path tmp) throws IOException {
        var b = new Baseline(tmp);
        var r = new Result(1_000L, 2_000L, 3_000L, 50);
        b.store("demo", r);

        var loaded = b.load("demo").orElseThrow();
        assertEquals(1_000L, loaded.p50ns());
        assertEquals(2_000L, loaded.p95ns());
        assertEquals(3_000L, loaded.p99ns());
        assertEquals(50, loaded.samples());
    }

    @Test
    @DisplayName("load returns empty when no baseline file exists")
    void loadMissingReturnsEmpty(@TempDir Path tmp) throws IOException {
        var b = new Baseline(tmp);
        assertTrue(b.load("never-stored").isEmpty());
    }

    @Test
    @DisplayName("first check records a new baseline")
    void firstCheckRecords(@TempDir Path tmp) throws IOException {
        var b = new Baseline(tmp);
        var report = b.check("hover", new Result(100, 200, 300, 50), 10.0);
        assertEquals(Baseline.Outcome.RECORDED_NEW, report.outcome());
        assertTrue(b.load("hover").isPresent());
    }

    @Test
    @DisplayName("second check within tolerance reports WITHIN_TOLERANCE")
    void withinToleranceOk(@TempDir Path tmp) throws IOException {
        var b = new Baseline(tmp);
        b.store("hover", new Result(100, 200, 300, 50));
        var report = b.check("hover", new Result(100, 210, 320, 50), 10.0);
        assertEquals(Baseline.Outcome.WITHIN_TOLERANCE, report.outcome());
    }

    @Test
    @DisplayName("p95 regression beyond tolerance reports REGRESSED")
    void regressionDetected(@TempDir Path tmp) throws IOException {
        var b = new Baseline(tmp);
        b.store("hover", new Result(100, 200, 300, 50));
        var report = b.check("hover", new Result(100, 260, 380, 50), 10.0);
        assertEquals(Baseline.Outcome.REGRESSED, report.outcome());
        assertTrue(report.diffPercent() > 10.0, "diffPercent should exceed tolerance");
    }

    @Test
    @DisplayName("improvement beyond tolerance reports IMPROVED but does not auto-rebaseline")
    void improvementReported(@TempDir Path tmp) throws IOException {
        var b = new Baseline(tmp);
        b.store("hover", new Result(100, 200, 300, 50));
        var report = b.check("hover", new Result(80, 100, 150, 50), 10.0);
        assertEquals(Baseline.Outcome.IMPROVED, report.outcome());
        // Baseline file should still hold the OLD value — auto-rebaseline must
        // be an explicit commit, not a silent overwrite.
        assertEquals(200L, b.load("hover").orElseThrow().p95ns());
    }

    @Test
    @DisplayName("rebaseline=true overwrites existing baseline and reports RECORDED_NEW")
    void rebaselineOverwrites(@TempDir Path tmp) throws IOException {
        var b = new Baseline(tmp);
        b.store("hover", new Result(100, 200, 300, 50));
        var rebase = new Baseline(tmp, true);
        var report = rebase.check("hover", new Result(50, 75, 90, 50), 10.0);
        assertEquals(Baseline.Outcome.RECORDED_NEW, report.outcome());
        assertEquals(75L, b.load("hover").orElseThrow().p95ns());
    }

    @Test
    @DisplayName("diffPercent: 0 on equal, positive on regression, negative on improvement")
    void diffPercentSemantics() {
        assertEquals(0.0, Baseline.diffPercent(200, 200));
        assertEquals(50.0, Baseline.diffPercent(200, 300));
        assertEquals(-50.0, Baseline.diffPercent(200, 100));
    }

    @Test
    @DisplayName("baseline file path uses the supplied name verbatim with .json suffix")
    void baselineFileLayout(@TempDir Path tmp) throws IOException {
        var b = new Baseline(tmp);
        b.store("a.b.c", new Result(1, 2, 3, 10));
        assertTrue(Files.exists(tmp.resolve("a.b.c.json")));
    }
}
