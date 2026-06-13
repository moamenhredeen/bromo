package me.moamenhredeen.bromo.bench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import me.moamenhredeen.bromo.compiler.EcjContext;
import me.moamenhredeen.bromo.compiler.LibrarySourceProvider;
import me.moamenhredeen.bromo.compiler.SourceResolver;
import me.moamenhredeen.bromo.features.DefinitionFeature;
import me.moamenhredeen.bromo.jdk.JdkProvider;
import java.util.List;
import me.moamenhredeen.bromo.project.maven.MavenProjectModel;
import me.moamenhredeen.bromo.project.maven.resolver.MavenResolverProvider;
import me.moamenhredeen.bromo.symbol.WorkspaceScanner;
import me.moamenhredeen.bromo.util.CancelToken;
import me.moamenhredeen.bromo.workspace.FileStore;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// M6 perf gate: goto-definition latency.
///
/// Acceptance: same-file <20 ms / cross-file <50 ms. Current v0 implementation
/// re-parses the file with bindings per request — the same parseWithBindings
/// path measured by [HoverBench]. Numbers should mirror those.
final class DefinitionBench {

    @Test
    @DisplayName("DefinitionFeature.definition on Main.java — M6 acceptance")
    void definitionOnBromo() throws Exception {
        var root = Path.of(".").toAbsolutePath().normalize();
        var model = (MavenProjectModel) new MavenResolverProvider().load(root);
        var fs = new FileStore();
        var ctx = new EcjContext(fs, model.sourceRoots(), model.classpathBinaries());
        var symbols = new WorkspaceScanner().scan(model.sourceRoots()).index();
        var sources = new SourceResolver(
                new JdkProvider(root.resolve("target/bromo-cache/sources/jdk")),
                new LibrarySourceProvider(model.classpath(), root.resolve("target/bromo-cache/sources/lib")));
        var feature = new DefinitionFeature(ctx, fs, symbols, sources);

        var mainJava = root.resolve("src/main/java/me.moamenhredeen/bromo/Main.java").toUri();

        // Warmup
        feature.definition(mainJava, 200, CancelToken.never());

        var result = BenchRunner.measure(5, 50, () ->
                feature.definition(mainJava, 200, CancelToken.never()));

        long p95ms = result.p95ns() / 1_000_000L;
        System.out.println("DefinitionFeature.definition(Main.java): "
                + "p50=" + (result.p50ns() / 1_000_000L) + "ms "
                + "p95=" + p95ms + "ms "
                + "p99=" + (result.p99ns() / 1_000_000L) + "ms");

        // 50-sample ECJ DOM parse — moderate variance.
        Baseline.checkRegression("definition.bromo-main", result, 50.0);

        assertTrue(p95ms < 500,
                "M6 sanity: goto-def p95 must be <500ms (uncached); was " + p95ms + "ms");
    }
}
