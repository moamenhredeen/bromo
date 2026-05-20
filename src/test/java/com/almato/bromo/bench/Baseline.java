package com.almato.bromo.bench;

import com.almato.bromo.bench.BenchRunner.Result;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// On-disk bench baseline store with a regression check.
///
/// Each baseline is one tiny JSON file under [#dir]; the schema is just the
/// three percentile nanoseconds plus a sample count and a recorded-at
/// timestamp. No dependency on a JSON library — bench has zero runtime deps
/// and we keep it that way.
///
/// Workflow:
/// - A bench calls [#check] with a [Result]. If no baseline exists or the
///   `rebaseline=true` system property is set, the baseline is recorded and
///   the outcome is [Outcome#RECORDED_NEW].
/// - Otherwise the current p95 is compared to the baseline p95. A divergence
///   greater than the supplied tolerance reports [Outcome#REGRESSED] (slower)
///   or [Outcome#IMPROVED] (faster); within the band reports
///   [Outcome#WITHIN_TOLERANCE].
/// - Improvements do **not** auto-overwrite the baseline. Rebaselining is an
///   explicit commit — both because perf can regress silently across a series
///   of small "improvements", and because reviewers should see the new
///   baseline in the diff.
public final class Baseline {

    /// Plan default — see `bench/` section in `.claude/plans/i-want-to-build-snug-canyon.md`.
    public static final double DEFAULT_TOLERANCE_PERCENT = 10.0;

    /// Repo-relative directory where baselines are stored.
    public static final Path DEFAULT_DIR = Path.of("bench", "baselines");

    private static final Pattern P50 = Pattern.compile("\"p50ns\"\\s*:\\s*(\\d+)");
    private static final Pattern P95 = Pattern.compile("\"p95ns\"\\s*:\\s*(\\d+)");
    private static final Pattern P99 = Pattern.compile("\"p99ns\"\\s*:\\s*(\\d+)");
    private static final Pattern SAMPLES = Pattern.compile("\"samples\"\\s*:\\s*(\\d+)");

    private final Path dir;
    private final boolean forceRebaseline;

    public Baseline(Path dir) { this(dir, false); }

    public Baseline(Path dir, boolean forceRebaseline) {
        this.dir = dir;
        this.forceRebaseline = forceRebaseline;
    }

    /// Convenience: default dir + `-Drebaseline=true` system property toggle.
    public static Baseline fromSysProps() {
        return new Baseline(DEFAULT_DIR, Boolean.getBoolean("rebaseline"));
    }

    public Optional<Result> load(String name) throws IOException {
        Path file = dir.resolve(name + ".json");
        if (!Files.exists(file)) return Optional.empty();
        String content = Files.readString(file, StandardCharsets.UTF_8);
        long p50 = readLong(content, P50);
        long p95 = readLong(content, P95);
        long p99 = readLong(content, P99);
        int samples = (int) readLong(content, SAMPLES);
        return Optional.of(new Result(p50, p95, p99, samples));
    }

    public void store(String name, Result r) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".json");
        String json = """
                {
                  "name": "%s",
                  "p50ns": %d,
                  "p95ns": %d,
                  "p99ns": %d,
                  "samples": %d,
                  "recordedAt": "%s"
                }
                """.formatted(name, r.p50ns(), r.p95ns(), r.p99ns(), r.samples(), Instant.now());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    public Report check(String name, Result current, double tolerancePercent) throws IOException {
        var prev = load(name);
        if (prev.isEmpty() || forceRebaseline) {
            store(name, current);
            return new Report(Outcome.RECORDED_NEW, current, prev.orElse(null), 0);
        }
        double diff = diffPercent(prev.get().p95ns(), current.p95ns());
        Outcome out;
        if (diff > tolerancePercent)       out = Outcome.REGRESSED;
        else if (diff < -tolerancePercent) out = Outcome.IMPROVED;
        else                               out = Outcome.WITHIN_TOLERANCE;
        return new Report(out, current, prev.get(), diff);
    }

    /// Compare [current] against the on-disk baseline for [name] and throw on
    /// regression beyond [DEFAULT_TOLERANCE_PERCENT]. Logs the outcome to
    /// stdout so the bench report shows every entry, not just failures.
    public static void checkRegression(String name, Result current) {
        checkRegression(name, current, DEFAULT_TOLERANCE_PERCENT);
    }

    /// Variant with an explicit tolerance — used by benches whose sample
    /// counts are necessarily small (full project load, full workspace scan)
    /// so the default 10% would be dominated by run-to-run noise.
    public static void checkRegression(String name, Result current, double tolerancePercent) {
        try {
            var report = fromSysProps().check(name, current, tolerancePercent);
            System.out.printf("%-40s %-18s diff=%+6.1f%%  %s%n",
                    name, report.outcome(), report.diffPercent(), current);
            if (report.outcome() == Outcome.REGRESSED) {
                throw new AssertionError(String.format(
                        "Bench regression on %s: %+.1f%% beyond %.1f%% tolerance "
                                + "(baseline p95=%.2fµs, current p95=%.2fµs). "
                                + "If intentional, rerun with -Drebaseline=true.",
                        name, report.diffPercent(), tolerancePercent,
                        report.baseline().p95us(), current.p95us()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Relative change of [currentNs] over [baselineNs] as a percentage.
    /// Positive = slower (regression); negative = faster (improvement).
    public static double diffPercent(long baselineNs, long currentNs) {
        if (baselineNs == 0) return 0;
        return 100.0 * (currentNs - baselineNs) / baselineNs;
    }

    private static long readLong(String content, Pattern p) {
        Matcher m = p.matcher(content);
        if (!m.find()) {
            throw new IllegalStateException("baseline file missing field: " + p.pattern());
        }
        return Long.parseLong(m.group(1));
    }

    public enum Outcome { RECORDED_NEW, WITHIN_TOLERANCE, IMPROVED, REGRESSED }

    public record Report(Outcome outcome, Result current, Result baseline, double diffPercent) {}
}
