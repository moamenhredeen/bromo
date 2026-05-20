package com.almato.bromo.bench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.almato.bromo.compiler.EcjContext;
import com.almato.bromo.features.CompletionFeature;
import com.almato.bromo.project.maven.MavenProjectModel;
import com.almato.bromo.project.maven.resolver.MavenResolverProvider;
import com.almato.bromo.symbol.WorkspaceScanner;
import com.almato.bromo.util.CancelToken;
import com.almato.bromo.workspace.FileStore;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// M7 + M7.5 perf gates.
///
/// - Prefix completion (`Pie|`) — symbol-index trie lookup; budget <40 ms p95.
/// - Member-access completion (`"foo".le|`) — ECJ binding resolution; budget
///   <80 ms p95 (the harder case in the plan).
final class CompletionBench {

    @Test
    @DisplayName("Prefix completion via SymbolIndex — M7 acceptance")
    void prefixCompletion() {
        var root = Path.of(".").toAbsolutePath().normalize();
        var symbols = new WorkspaceScanner().scan(List.of(
                root.resolve("src/main/java"),
                root.resolve("src/test/java"))).index();
        var feature = new CompletionFeature(symbols);

        var src = "class A { Pie";
        var dummy = URI.create("file:///x/X.java");
        feature.completionsAt(dummy, src, src.length(), CancelToken.never()); // warmup

        var result = BenchRunner.measure(1_000, 10_000,
                () -> feature.completionsAt(dummy, src, src.length(), CancelToken.never()));

        long p95us = result.p95ns() / 1_000L;
        System.out.println("CompletionFeature.completionsAt(prefix=\"Pie\"): "
                + "p50=" + (result.p50ns() / 1_000L) + "µs "
                + "p95=" + p95us + "µs");
        // Sub-µs regime: 10% tolerance is below timer resolution → high noise.
        Baseline.checkRegression("completion.prefix-Pie", result, 75.0);

        assertTrue(p95us < 40_000,
                "M7 sanity: prefix completion p95 must be <40ms; was " + p95us + "µs");
    }

    @Test
    @DisplayName("Member-access completion on String literal — M7.5 acceptance")
    void memberAccess() throws Exception {
        var root = Path.of(".").toAbsolutePath().normalize();
        var model = (MavenProjectModel) new MavenResolverProvider().load(root);
        var fs = new FileStore();
        var ecj = new EcjContext(fs, model.sourceRoots(), model.classpathBinaries());
        var symbols = new WorkspaceScanner().scan(model.sourceRoots()).index();
        var feature = new CompletionFeature(symbols, ecj);

        var source = """
                package x;
                public class Bench {
                    void run() { int n = "hello".le }
                }
                """;
        var dummy = URI.create("file:///x/Bench.java");
        int cursor = source.indexOf(".le") + 3;

        feature.completionsAt(dummy, source, cursor, CancelToken.never()); // warmup

        var result = BenchRunner.measure(5, 30,
                () -> feature.completionsAt(dummy, source, cursor, CancelToken.never()));

        long p95ms = result.p95ns() / 1_000_000L;
        System.out.println("CompletionFeature.completionsAt(\"hello\".le): "
                + "p50=" + (result.p50ns() / 1_000_000L) + "ms "
                + "p95=" + p95ms + "ms "
                + "p99=" + (result.p99ns() / 1_000_000L) + "ms");
        // 30-sample ECJ binding resolution — moderate variance.
        Baseline.checkRegression("completion.member-string-le", result, 50.0);

        assertTrue(p95ms < 500,
                "M7.5 sanity: member-access p95 must be <500ms (uncached); was " + p95ms + "ms");
    }
}
