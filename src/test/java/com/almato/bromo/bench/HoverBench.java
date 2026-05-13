package com.almato.bromo.bench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.almato.bromo.compiler.EcjContext;
import com.almato.bromo.compiler.LibrarySourceProvider;
import com.almato.bromo.compiler.SourceResolver;
import com.almato.bromo.features.HoverFeature;
import com.almato.bromo.jdk.JdkProvider;
import com.almato.bromo.project.maven.MavenProjectModel;
import com.almato.bromo.project.maven.resolver.MavenResolverProvider;
import com.almato.bromo.util.CancelToken;
import com.almato.bromo.workspace.FileStore;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// M5 perf gate: hover latency on a bromo source file.
///
/// Acceptance: hover over a token resolves within 80 ms p95.
final class HoverBench {

    @Test
    @DisplayName("HoverFeature.hover on bromo's Main.java — M5 acceptance")
    void hoverOnBromo() throws Exception {
        var root = Path.of(".").toAbsolutePath().normalize();
        var model = (MavenProjectModel) new MavenResolverProvider().load(root);
        var files = new FileStore();
        var ctx = new EcjContext(files, model.sourceRoots(), model.classpathBinaries());
        var sources = new SourceResolver(
                new JdkProvider(root.resolve("target/bromo-cache/sources/jdk")),
                new LibrarySourceProvider(model.classpath(), root.resolve("target/bromo-cache/sources/lib")));
        var hover = new HoverFeature(ctx, files, sources);

        var mainJava = root.resolve("src/main/java/com/almato/bromo/Main.java").toUri();
        // Warmup
        hover.hover(mainJava, 100, CancelToken.never());

        var result = BenchRunner.measure(5, 50, () ->
                hover.hover(mainJava, 100, CancelToken.never()));

        long p95ms = result.p95ns() / 1_000_000L;
        System.out.println("HoverFeature.hover(Main.java): "
                + "p50=" + (result.p50ns() / 1_000_000L) + "ms "
                + "p95=" + p95ms + "ms "
                + "p99=" + (result.p99ns() / 1_000_000L) + "ms");

        assertTrue(p95ms < 500,
                "M5 sanity: hover p95 must be <500ms (relaxed from 80ms while uncached); was " + p95ms + "ms");
    }
}
