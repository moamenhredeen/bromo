package me.moamenhredeen.bromo.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import me.moamenhredeen.bromo.compiler.EcjContext;
import me.moamenhredeen.bromo.compiler.LibrarySourceProvider;
import me.moamenhredeen.bromo.compiler.SourceResolver;
import me.moamenhredeen.bromo.jdk.JdkProvider;
import me.moamenhredeen.bromo.symbol.SymbolIndex;
import me.moamenhredeen.bromo.symbol.WorkspaceScanner;
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

final class DefinitionFeatureTest {

    @Test
    @DisplayName("same-file method reference jumps to its declaration")
    void sameFileMethod(@TempDir Path tmp) throws IOException {
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

        var feature = newFeature(tmp);
        int callSite = source.indexOf("doubled(21)");
        int declSite = source.indexOf("doubled(int x)");
        var result = feature.definition(file.toUri(), callSite, CancelToken.never());
        assertTrue(result.isPresent(), "expected definition for doubled(21)");
        assertEquals(file.toUri(), result.get().uri());
        assertEquals(declSite, result.get().startOffset(), "expected jump to declaration of doubled");
    }

    @Test
    @DisplayName("same-file class reference jumps to its declaration")
    void sameFileType(@TempDir Path tmp) throws IOException {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var file = src.resolve("Foo.java");
        var source = """
                package example;
                public class Foo {
                    Foo other;
                }
                """;
        Files.writeString(file, source, StandardCharsets.UTF_8);

        var feature = newFeature(tmp);
        int referenceSite = source.indexOf("Foo other");
        int declSite = source.indexOf("Foo {");
        var result = feature.definition(file.toUri(), referenceSite, CancelToken.never());
        assertTrue(result.isPresent());
        assertEquals(file.toUri(), result.get().uri());
        assertEquals(declSite, result.get().startOffset());
    }

    @Test
    @DisplayName("cross-file reference resolves via the symbol index fallback")
    void crossFileFallback(@TempDir Path tmp) throws IOException {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var helper = src.resolve("Helper.java");
        var user = src.resolve("User.java");
        Files.writeString(helper, """
                package example;
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

        var fs = new FileStore();
        var ctx = new EcjContext(fs, List.of(tmp.resolve("src/main/java")), List.of());
        // Build the symbol index so the fallback path has something to find.
        var symbols = new WorkspaceScanner().scan(List.of(tmp.resolve("src/main/java"))).index();
        var feature = new DefinitionFeature(ctx, fs, symbols, sourceResolver(tmp));

        int callSite = userSrc.indexOf("Helper.doubled(21)") + "Helper.".length();
        var result = feature.definition(user.toUri(), callSite, CancelToken.never());
        assertTrue(result.isPresent(), "expected cross-file definition");
        assertEquals(helper.toUri(), result.get().uri(),
                "expected jump to Helper.java; got " + result.get().uri());
    }

    @Test
    @DisplayName("JDK type reference resolves to src.zip — java.lang.String")
    void jdkType(@TempDir Path tmp) throws IOException {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var file = src.resolve("Use.java");
        var source = """
                package example;
                public class Use {
                    String s;
                }
                """;
        Files.writeString(file, source, StandardCharsets.UTF_8);

        var feature = newFeature(tmp);
        assumeTrue(new JdkProvider(tmp.resolve("probe")).available(),
                "JDK does not ship src.zip — skipping JDK navigation test.");

        int cursor = source.indexOf("String s");
        var result = feature.definition(file.toUri(), cursor, CancelToken.never());
        assertTrue(result.isPresent(), "expected definition into JDK source");
        assertTrue(result.get().uri().toString().endsWith("String.java"),
                "expected jump into String.java; got " + result.get().uri());

        var jdkSource = Files.readString(Path.of(result.get().uri()), StandardCharsets.UTF_8);
        var nameOffset = result.get().startOffset();
        // The startOffset names the type identifier; the substring there must be "String".
        assertEquals("String", jdkSource.substring(nameOffset, nameOffset + "String".length()));
    }

    @Test
    @DisplayName("JDK method reference resolves into src.zip — String.length()")
    void jdkMethod(@TempDir Path tmp) throws IOException {
        var src = mkdirs(tmp.resolve("src/main/java/example"));
        var file = src.resolve("Use.java");
        var source = """
                package example;
                public class Use {
                    int run(String s) { return s.length(); }
                }
                """;
        Files.writeString(file, source, StandardCharsets.UTF_8);

        var feature = newFeature(tmp);
        assumeTrue(new JdkProvider(tmp.resolve("probe")).available(),
                "JDK does not ship src.zip — skipping JDK navigation test.");

        int cursor = source.indexOf("length()");
        var result = feature.definition(file.toUri(), cursor, CancelToken.never());
        assertTrue(result.isPresent(), "expected definition into String#length");
        assertTrue(result.get().uri().toString().endsWith("String.java"),
                "expected jump into String.java");

        var jdkSource = Files.readString(Path.of(result.get().uri()), StandardCharsets.UTF_8);
        var nameOffset = result.get().startOffset();
        assertEquals("length", jdkSource.substring(nameOffset, nameOffset + "length".length()),
                "expected to land on the 'length' identifier of the method declaration");
    }

    private DefinitionFeature newFeature(Path tmp) {
        var fs = new FileStore();
        var ctx = new EcjContext(fs, List.of(tmp.resolve("src/main/java")), List.of());
        var symbols = new SymbolIndex();
        return new DefinitionFeature(ctx, fs, symbols, sourceResolver(tmp));
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
