package me.moamenhredeen.bromo.features;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import me.moamenhredeen.bromo.compiler.EcjContext;
import me.moamenhredeen.bromo.compiler.LibrarySourceProvider;
import me.moamenhredeen.bromo.compiler.SourceResolver;
import me.moamenhredeen.bromo.jdk.JdkProvider;
import me.moamenhredeen.bromo.project.maven.MavenProjectModel;
import me.moamenhredeen.bromo.project.maven.resolver.MavenResolverProvider;
import me.moamenhredeen.bromo.query.QueryEngine;
import me.moamenhredeen.bromo.util.CancelToken;
import me.moamenhredeen.bromo.workspace.FileStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HoverFeatureTest {

    @Test
    @DisplayName("hover on a local class name yields its qualified name")
    void hoverOnLocalClass(@TempDir Path tmp) throws IOException {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var file = src.resolve("Foo.java");
        var source = """
                package example;
                public class Foo {
                    Foo other;
                }
                """;
        Files.writeString(file, source, StandardCharsets.UTF_8);

        var files = new FileStore();
        var ctx = new EcjContext(files, List.of(tmp.resolve("src/main/java")), List.of());
        var hover = new HoverFeature(ctx, files, sourceResolver(tmp), new me.moamenhredeen.bromo.symbol.SymbolIndex());

        int offset = source.indexOf("Foo other") + "F".length() - 1;
        var result = hover.hover(file.toUri(), offset, CancelToken.never());
        assertTrue(result.isPresent(), "expected hover, got empty");
        assertTrue(result.get().markdown().contains("example.Foo"),
                "expected qualified name; got " + result.get().markdown());
    }

    @Test
    @DisplayName("hover on JDK type resolves via classpath (uses bromo's own classpath)")
    void hoverOnJdkType(@TempDir Path tmp) throws Exception {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var file = src.resolve("Use.java");
        var source = """
                package example;
                public class Use {
                    String s;
                }
                """;
        Files.writeString(file, source, StandardCharsets.UTF_8);

        var files = new FileStore();
        // Empty classpath is enough — JDK is auto-discovered via jrt-fs in parseWithBindings.
        var ctx = new EcjContext(files, List.of(tmp.resolve("src/main/java")), List.of());
        var hover = new HoverFeature(ctx, files, sourceResolver(tmp), new me.moamenhredeen.bromo.symbol.SymbolIndex());

        int offset = source.indexOf("String");
        var result = hover.hover(file.toUri(), offset, CancelToken.never());
        assertTrue(result.isPresent(), "expected hover");
        assertTrue(result.get().markdown().contains("java.lang.String"),
                "expected java.lang.String in hover; got " + result.get().markdown());
    }

    @Test
    @DisplayName("hover on a method invocation shows the signature")
    void hoverOnMethod(@TempDir Path tmp) throws IOException {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var file = src.resolve("Calc.java");
        var source = """
                package example;
                public class Calc {
                    int doubled(int x) { return x * 2; }
                    int run() { return doubled(21); }
                }
                """;
        Files.writeString(file, source, StandardCharsets.UTF_8);

        var files = new FileStore();
        var ctx = new EcjContext(files, List.of(tmp.resolve("src/main/java")), List.of());
        var hover = new HoverFeature(ctx, files, sourceResolver(tmp), new me.moamenhredeen.bromo.symbol.SymbolIndex());

        int offset = source.indexOf("doubled(21)");
        var result = hover.hover(file.toUri(), offset, CancelToken.never());
        assertTrue(result.isPresent(), "expected hover");
        assertTrue(result.get().markdown().contains("doubled"),
                "expected method name in hover; got " + result.get().markdown());
    }

    @Test
    @DisplayName("hover on cross-file workspace type pulls javadoc from the declaring file")
    void hoverOnCrossFileWorkspaceJavadoc(@TempDir Path tmp) throws IOException {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var helper = src.resolve("Helper.java");
        var user = src.resolve("User.java");
        Files.writeString(helper, """
                package example;

                /// Helper utility for arithmetic.
                ///
                /// Used by [User].
                public class Helper {
                    public static int doubled(int x) { return x * 2; }
                }
                """, StandardCharsets.UTF_8);
        var userSrc = """
                package example;
                public class User {
                    public int run() { return Helper.doubled(21); }
                }
                """;
        Files.writeString(user, userSrc, StandardCharsets.UTF_8);

        var files = new FileStore();
        var ctx = new EcjContext(files, List.of(tmp.resolve("src/main/java")), List.of());
        var symbols = new me.moamenhredeen.bromo.symbol.WorkspaceScanner()
                .scan(List.of(tmp.resolve("src/main/java"))).index();
        var hover = new HoverFeature(ctx, files, sourceResolver(tmp), symbols);

        int offset = userSrc.indexOf("Helper.doubled");
        var result = hover.hover(user.toUri(), offset, CancelToken.never());
        assertTrue(result.isPresent(), "expected hover");
        var md = result.get().markdown();
        assertTrue(md.contains("Helper utility for arithmetic."),
                "expected cross-file javadoc body in hover; got:\n" + md);
    }

    @Test
    @DisplayName("hover on workspace type with markdown (///) javadoc includes the doc body")
    void hoverOnMarkdownJavadoc(@TempDir Path tmp) throws IOException {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var file = src.resolve("Doodad.java");
        var source = """
                package example;

                /// Wraps a single doodad with bells on.
                ///
                /// Used by widgets when they need a doodad.
                public class Doodad {
                    Doodad other;
                }
                """;
        Files.writeString(file, source, StandardCharsets.UTF_8);

        var files = new FileStore();
        var ctx = new EcjContext(files, List.of(tmp.resolve("src/main/java")), List.of());
        var hover = new HoverFeature(ctx, files, sourceResolver(tmp), new me.moamenhredeen.bromo.symbol.SymbolIndex());

        int offset = source.indexOf("Doodad other");
        var result = hover.hover(file.toUri(), offset, CancelToken.never());
        assertTrue(result.isPresent(), "expected hover");
        var md = result.get().markdown();
        assertTrue(md.contains("Wraps a single doodad with bells on."),
                "expected markdown javadoc body in hover; got:\n" + md);
        assertTrue(md.contains("Used by widgets when they need a doodad."),
                "expected second paragraph from markdown javadoc; got:\n" + md);
    }

    @Test
    @DisplayName("hover on JDK type includes javadoc from src.zip")
    void hoverOnJdkTypeWithDoc(@TempDir Path tmp) throws IOException {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var file = src.resolve("Use.java");
        var source = """
                package example;
                public class Use {
                    String s;
                }
                """;
        Files.writeString(file, source, StandardCharsets.UTF_8);

        assumeTrue(new JdkProvider(tmp.resolve("probe")).available(),
                "JDK does not ship src.zip — skipping javadoc test.");

        var files = new FileStore();
        var ctx = new EcjContext(files, List.of(tmp.resolve("src/main/java")), List.of());
        var hover = new HoverFeature(ctx, files, sourceResolver(tmp), new me.moamenhredeen.bromo.symbol.SymbolIndex());

        int offset = source.indexOf("String");
        var result = hover.hover(file.toUri(), offset, CancelToken.never());
        assertTrue(result.isPresent(), "expected hover");
        var md = result.get().markdown();
        assertTrue(md.contains("java.lang.String"),
                "expected the qualified name; got " + md);
        // Javadoc on java.lang.String mentions strings of characters in
        // its first paragraph across every JDK version we care about.
        assertTrue(md.toLowerCase().contains("string"),
                "expected javadoc body in hover output; got " + md);
        // Smell-test: the markdown should now be appreciably longer than the
        // bare signature. The pre-javadoc render of `String` is ~120 chars.
        assertTrue(md.length() > 300,
                "javadoc should expand the hover output; was only "
                        + md.length() + " chars: " + md);
    }

    @Test
    @DisplayName("repeated hover on the same open document hits the query cache")
    void hoverHitsQueryCache(@TempDir Path tmp) throws IOException {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var file = src.resolve("Foo.java");
        var source = """
                package example;
                public class Foo {
                    Foo other;
                }
                """;
        Files.writeString(file, source, StandardCharsets.UTF_8);

        var files = new FileStore();
        files.openDocument(file.toUri(), "java", source);
        var ctx = new EcjContext(files, List.of(tmp.resolve("src/main/java")), List.of());
        try (var queries = new QueryEngine(files, ctx)) {
            var hover = new HoverFeature(ctx, files, sourceResolver(tmp),
                    new me.moamenhredeen.bromo.symbol.SymbolIndex(), queries);

            int offset = source.indexOf("Foo other");
            var first = hover.hover(file.toUri(), offset, CancelToken.never());
            assertTrue(first.isPresent());
            int cachedAfterFirst = queries.cachedSize();
            assertTrue(cachedAfterFirst == 1,
                    "expected exactly one cached AST after first hover, got " + cachedAfterFirst);

            var second = hover.hover(file.toUri(), offset, CancelToken.never());
            assertTrue(second.isPresent());
            assertTrue(queries.cachedSize() == 1, "second hover should reuse cache");
            assertTrue(first.get().markdown().equals(second.get().markdown()),
                    "cached AST should yield identical hover output");
        }
    }

    private static SourceResolver sourceResolver(Path tmp) {
        return new SourceResolver(
                new JdkProvider(tmp.resolve("target/bromo-cache/sources/jdk")),
                new LibrarySourceProvider(List.of(), tmp.resolve("target/bromo-cache/sources/lib")));
    }

    private static Path mkdirs(Path p) throws IOException {
        Files.createDirectories(p);
        return p;
    }
}
