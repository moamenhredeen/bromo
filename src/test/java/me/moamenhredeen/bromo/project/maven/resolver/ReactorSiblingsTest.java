package me.moamenhredeen.bromo.project.maven.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Reactor-sibling discovery — used by [me.moamenhredeen.bromo.workspace.Workspace]
/// to expand the symbol index across all modules of a multi-module Maven
/// project even when only one module is opened.
final class ReactorSiblingsTest {

    @Test
    @DisplayName("returns sibling src/main/java + src/test/java when parent pom lists modules")
    void discoversSiblings(@TempDir Path tmp) throws IOException {
        reactor(tmp, "a", "b", "c");
        Path a = tmp.resolve("a");

        var roots = MavenResolverProvider.discoverReactorSiblingSources(a);
        assertTrue(containsEndingWith(roots, "b/src/main/java"),
                "expected b/src/main/java in sibling roots: " + roots);
        assertTrue(containsEndingWith(roots, "c/src/main/java"),
                "expected c/src/main/java in sibling roots: " + roots);
        assertTrue(containsEndingWith(roots, "b/src/test/java"));
        assertFalse(containsEndingWith(roots, "a/src/main/java"),
                "current module must not be re-included: " + roots);
    }

    @Test
    @DisplayName("returns empty when no parent pom exists")
    void noParentPom(@TempDir Path tmp) throws IOException {
        Path standalone = tmp.resolve("solo");
        Files.createDirectories(standalone);
        Files.writeString(standalone.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion></project>", StandardCharsets.UTF_8);
        assertEquals(List.of(),
                MavenResolverProvider.discoverReactorSiblingSources(standalone));
    }

    @Test
    @DisplayName("returns empty when parent pom has no <modules>")
    void parentWithoutModules(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.x</groupId><artifactId>p</artifactId><version>1</version>
                </project>
                """, StandardCharsets.UTF_8);
        Path child = tmp.resolve("a");
        Files.createDirectories(child);
        Files.writeString(child.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion></project>", StandardCharsets.UTF_8);
        assertEquals(List.of(),
                MavenResolverProvider.discoverReactorSiblingSources(child));
    }

    @Test
    @DisplayName("skips siblings without a src/main/java directory")
    void skipsSiblingsWithoutSources(@TempDir Path tmp) throws IOException {
        reactor(tmp, "a", "empty");
        // Remove empty/src so it has no java sources at all.
        deleteRecursively(tmp.resolve("empty").resolve("src"));

        var roots = MavenResolverProvider.discoverReactorSiblingSources(tmp.resolve("a"));
        assertFalse(containsEndingWith(roots, "empty/src/main/java"),
                "empty sibling should be skipped: " + roots);
    }

    private static boolean containsEndingWith(List<Path> roots, String suffix) {
        String normalized = suffix.replace('/', java.io.File.separatorChar);
        return roots.stream().anyMatch(p -> p.toString().endsWith(normalized));
    }

    /// Build a parent pom listing each [moduleName] as a module, plus
    /// the module directories with their pom.xml + src/main/java + src/test/java.
    private static void reactor(Path tmp, String... moduleNames) throws IOException {
        var modules = new StringBuilder();
        for (var n : moduleNames) modules.append("<module>").append(n).append("</module>");
        Files.writeString(tmp.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.x</groupId><artifactId>p</artifactId><version>1</version>
                  <packaging>pom</packaging>
                  <modules>%s</modules>
                </project>
                """.formatted(modules), StandardCharsets.UTF_8);
        for (var n : moduleNames) {
            Path dir = tmp.resolve(n);
            Files.createDirectories(dir.resolve("src/main/java"));
            Files.createDirectories(dir.resolve("src/test/java"));
            Files.writeString(dir.resolve("pom.xml"),
                    "<project><modelVersion>4.0.0</modelVersion></project>",
                    StandardCharsets.UTF_8);
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(x -> { try { Files.delete(x); } catch (IOException ignored) {} });
        }
    }
}
