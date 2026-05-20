package com.almato.bromo.bench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.almato.bromo.compiler.EcjContext;
import com.almato.bromo.project.maven.MavenProjectModel;
import com.almato.bromo.project.maven.resolver.MavenResolverProvider;
import com.almato.bromo.workspace.FileStore;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// M4 perf gate: workspace-wide compile producing diagnostics on bromo's
/// own source tree.
///
/// Acceptance from the plan: edit → diagnostic <500 ms p95 on a ≤100-file
/// module. v0 implementation recompiles the whole module per request; M4.5
/// will add a long-lived `Compiler` with reused `LookupEnvironment` if the
/// bench shows we need it.
final class DiagnosticsBench {

    @Test
    @DisplayName("EcjContext.compileWorkspace() on bromo — M4 acceptance")
    void compileBromo() throws Exception {
        var root = Path.of(".").toAbsolutePath().normalize();
        var model = (MavenProjectModel) new MavenResolverProvider().load(root);
        try (var ctx = new EcjContext(new FileStore(), model.sourceRoots(), model.classpathBinaries())) {

            // Warmup — fills the cache so subsequent calls hit the M4.5 fast path.
            // To measure cold compile, we read the warmup result.
            long coldStart = System.nanoTime();
            ctx.compileWorkspace();
            long coldElapsedMs = (System.nanoTime() - coldStart) / 1_000_000L;
            System.out.println("EcjContext.compileWorkspace(bromo) — cold: " + coldElapsedMs + "ms");

            // After M4.5: repeat with no changes should hit the cache and return quickly.
            var cachedResult = BenchRunner.measure(0, 50, () -> {
                try { ctx.compileWorkspace(); } catch (Exception e) { throw new RuntimeException(e); }
            });
            long cachedP95us = cachedResult.p95ns() / 1_000L;
            System.out.println("EcjContext.compileWorkspace(bromo) — cached (no changes): "
                    + "p50=" + (cachedResult.p50ns() / 1_000L) + "µs "
                    + "p95=" + cachedP95us + "µs");

            // 50-sample filesystem walk + per-file mtime probe — env-dependent.
            Baseline.checkRegression("diagnostics.compile-bromo-cached", cachedResult, 100.0);

            assertTrue(coldElapsedMs < 5_000,
                    "M4 sanity: cold workspace compile must be <5s; was " + coldElapsedMs + "ms");
            // M4.5: cache hit costs one filesystem walk + per-file signature
            // probe (size+mtime, or text.hashCode() for open buffers). 25ms
            // covers bromo's tree today; the regression check on the baseline
            // catches drift before it eats the latency budget.
            assertTrue(cachedP95us < 25_000,
                    "M4.5: cached compile p95 must be <25ms; was " + cachedP95us + "µs");
        }
    }
}
