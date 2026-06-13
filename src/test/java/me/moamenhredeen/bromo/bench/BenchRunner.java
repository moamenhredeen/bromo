package me.moamenhredeen.bromo.bench;

import java.util.Arrays;

/// Tiny in-process microbench runner.
///
/// Methodology:
/// - Discard the warmup samples entirely.
/// - Measure with `System.nanoTime()`.
/// - Sort samples, report p50 / p95 / p99.
/// - Never report mean (long-tail latency is what matters for an LSP).
///
/// This is intentionally not JMH — JMH is a heavyweight test-scope dep we
/// don't need for the kinds of round-trip and dispatch measurements bromo
/// cares about. For tight CPU microbenches that need de-optimization analysis,
/// add JMH selectively in v1.
public final class BenchRunner {
    private BenchRunner() {}

    /// Runs [body] for [warmupIterations] (discarded) then [measureIterations]
    /// (recorded). Returns a [Result] with timing percentiles in nanoseconds.
    public static Result measure(int warmupIterations, int measureIterations, Runnable body) {
        if (warmupIterations < 0 || measureIterations <= 0) {
            throw new IllegalArgumentException("iterations must be positive");
        }
        for (int i = 0; i < warmupIterations; i++) {
            body.run();
        }
        var samples = new long[measureIterations];
        for (int i = 0; i < measureIterations; i++) {
            long start = System.nanoTime();
            body.run();
            samples[i] = System.nanoTime() - start;
        }
        Arrays.sort(samples);
        return new Result(
                samples[measureIterations / 2],
                samples[Math.min(measureIterations - 1, (int) (measureIterations * 0.95))],
                samples[Math.min(measureIterations - 1, (int) (measureIterations * 0.99))],
                measureIterations);
    }

    /// Bench result. Percentiles in nanoseconds; convenience accessors for µs.
    public record Result(long p50ns, long p95ns, long p99ns, int samples) {

        public double p50us() { return p50ns / 1_000.0; }
        public double p95us() { return p95ns / 1_000.0; }
        public double p99us() { return p99ns / 1_000.0; }

        @Override
        public String toString() {
            return "p50=%.2fµs p95=%.2fµs p99=%.2fµs (n=%d)"
                    .formatted(p50us(), p95us(), p99us(), samples);
        }
    }
}
