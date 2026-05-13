package com.almato.bromo.project.maven.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.almato.bromo.project.maven.MavenProjectModel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Dogfood test: bromo loads its *own* pom.xml via [MavenResolverProvider].
///
/// Relies on the local `~/.m2/repository` already containing bromo's
/// declared deps — true after any successful `mvn compile`, including
/// the one Maven runs before surefire executes us.
final class MavenResolverProviderTest {

    @Test
    @DisplayName("supports() = true on a directory with a pom.xml")
    void supportsBromoRoot(@TempDir Path tmp) throws Exception {
        var provider = new MavenResolverProvider();
        assertFalse(provider.supports(tmp));
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        assertTrue(provider.supports(tmp));
    }

    @Test
    @DisplayName("load(bromo) returns the expected coordinates, source roots, java release")
    void loadsBromoCoordinates() throws Exception {
        var provider = new MavenResolverProvider();
        var root = Path.of(".").toAbsolutePath().normalize();
        var model = (MavenProjectModel) provider.load(root);

        assertEquals("com.almato", model.groupId());
        assertEquals("bromo", model.artifactId());
        assertEquals("0.0.1-SNAPSHOT", model.version());
        assertEquals("25", model.javaRelease().orElseThrow());

        // Default source roots
        assertTrue(model.sourceRoots().stream()
                        .anyMatch(p -> p.endsWith(Path.of("src/main/java"))),
                "main source root: " + model.sourceRoots());
        assertTrue(model.sourceRoots().stream()
                        .anyMatch(p -> p.endsWith(Path.of("src/test/java"))),
                "test source root: " + model.sourceRoots());
    }

    @Test
    @DisplayName("classpath entries attach sibling -sources.jar when present locally")
    void attachesSiblingSourceJars() throws Exception {
        var provider = new MavenResolverProvider();
        var model = (MavenProjectModel) provider.load(Path.of(".").toAbsolutePath().normalize());
        // We don't assume any specific dep ships sources locally — that depends
        // on the developer's ~/.m2 state. We only assert the modelling is
        // consistent: any sources attachment that's present must (a) live next
        // to its binary and (b) actually exist on disk.
        for (var entry : model.classpath()) {
            if (entry.sources().isEmpty()) continue;
            var sourcesPath = entry.sources().get();
            assertTrue(java.nio.file.Files.isRegularFile(sourcesPath),
                    "sources jar must exist on disk: " + sourcesPath);
            assertEquals(entry.binary().getParent(), sourcesPath.getParent(),
                    "sources jar must sit alongside its binary: " + sourcesPath
                            + " vs " + entry.binary());
        }
    }

    @Test
    @DisplayName("classpath resolves all direct deps + transitives we know we use")
    void resolvesClasspath() throws Exception {
        var provider = new MavenResolverProvider();
        var model = (MavenProjectModel) provider.load(Path.of(".").toAbsolutePath().normalize());
        var jarNames = model.classpath().stream()
                .map(e -> e.binary().getFileName().toString())
                .toList();

        // Direct runtime deps
        assertContains(jarNames, "ecj-");
        assertContains(jarNames, "org.eclipse.lsp4j-");
        assertContains(jarNames, "org.eclipse.lsp4j.jsonrpc-");
        assertContains(jarNames, "maven-resolver-impl-");
        assertContains(jarNames, "maven-resolver-transport-http-");
        assertContains(jarNames, "maven-resolver-provider-");

        // Direct test deps
        assertContains(jarNames, "junit-jupiter-");
        assertContains(jarNames, "jqwik-");

        // A transitive worth checking — gson is pulled in by LSP4J's jsonrpc
        assertContains(jarNames, "gson-");
    }

    private static void assertContains(java.util.List<String> jarNames, String prefix) {
        assertTrue(jarNames.stream().anyMatch(n -> n.startsWith(prefix)),
                "expected a jar starting with '" + prefix + "' in classpath, but had: " + jarNames);
    }
}
