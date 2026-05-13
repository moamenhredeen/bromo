package com.almato.bromo.bench;

import com.almato.bromo.project.maven.resolver.MavenResolverProvider;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// M2 perf gate / R1 replacement-trigger metric.
///
/// Records the wall time of `MavenResolverProvider.load(bromo)`. The R1
/// trigger fires at p95 > 3 s on a real Maven project; this bench captures
/// the v0 baseline so we can detect drift.
///
/// Project loads are expensive (network + POM model build + transitive
/// dependency resolution) so we run only a small number of samples to keep
/// the bench profile fast. Reported numbers are still informative for trend
/// monitoring.
final class ProjectLoadBench {

    @Test
    @DisplayName("MavenResolverProvider.load(bromo) — baseline + R1 trigger")
    void bromoProjectLoad() throws Exception {
        var provider = new MavenResolverProvider();
        var root = Path.of(".").toAbsolutePath().normalize();

        // One warm-up to JIT the resolver, populate the local repo cache,
        // and exercise model-building.
        provider.load(root);

        var result = BenchRunner.measure(0, 5, () -> {
            try {
                provider.load(root);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        long p95ms = result.p95ns() / 1_000_000L;
        System.out.println("MavenResolverProvider.load(bromo): "
                + "p50=" + (result.p50ns() / 1_000_000L) + "ms "
                + "p95=" + p95ms + "ms "
                + "p99=" + (result.p99ns() / 1_000_000L) + "ms");
        if (p95ms > 3_000) {
            System.out.println("  ⚠ R1 trigger fires: p95 > 3000ms — schedule hand-rolled replacement");
        }
    }
}
