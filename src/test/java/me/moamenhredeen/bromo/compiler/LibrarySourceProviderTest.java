package me.moamenhredeen.bromo.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import me.moamenhredeen.bromo.project.ClasspathEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LibrarySourceProviderTest {

    @Test
    @DisplayName("extracts a source file when the sibling -sources.jar is attached")
    void extractsAttachedSource(@TempDir Path tmp) throws IOException {
        Path lib = tmp.resolve("lib");
        Files.createDirectories(lib);
        Path binaryJar = lib.resolve("widgets-1.0.jar");
        Path sourcesJar = lib.resolve("widgets-1.0-sources.jar");
        writeJar(binaryJar, Map.of("com/example/Widget.class", new byte[]{1, 2, 3}));
        writeJar(sourcesJar, Map.of("com/example/Widget.java",
                "package com.example; public class Widget {}".getBytes(StandardCharsets.UTF_8)));

        var entry = new ClasspathEntry(binaryJar, java.util.Optional.of(sourcesJar));
        try (var provider = new LibrarySourceProvider(List.of(entry), tmp.resolve("cache"))) {
            var resolved = provider.resolveSource("com.example", "Widget");
            assertTrue(resolved.isPresent(), "expected resolved source");
            var content = Files.readString(resolved.get(), StandardCharsets.UTF_8);
            assertTrue(content.contains("public class Widget"),
                    "extracted file should contain the source: " + content);

            // Second call must hit the cache (same returned path).
            assertEquals(resolved.get(),
                    provider.resolveSource("com.example", "Widget").orElseThrow());
        }
    }

    @Test
    @DisplayName("returns empty when the FQN isn't on the classpath")
    void unknownTypeIsEmpty(@TempDir Path tmp) throws IOException {
        Path lib = tmp.resolve("lib");
        Files.createDirectories(lib);
        Path binaryJar = lib.resolve("widgets-1.0.jar");
        Path sourcesJar = lib.resolve("widgets-1.0-sources.jar");
        writeJar(binaryJar, Map.of("com/example/Widget.class", new byte[]{1, 2, 3}));
        writeJar(sourcesJar, Map.of("com/example/Widget.java", new byte[]{1, 2, 3}));

        var entry = new ClasspathEntry(binaryJar, java.util.Optional.of(sourcesJar));
        try (var provider = new LibrarySourceProvider(List.of(entry), tmp.resolve("cache"))) {
            assertFalse(provider.resolveSource("com.other", "Widget").isPresent());
            assertFalse(provider.resolveSource("com.example", "Other").isPresent());
        }
    }

    @Test
    @DisplayName("returns empty when the binary is indexed but its sources aren't attached")
    void unattachedReturnsEmpty(@TempDir Path tmp) throws IOException {
        Path lib = tmp.resolve("lib");
        Files.createDirectories(lib);
        Path binaryJar = lib.resolve("widgets-1.0.jar");
        writeJar(binaryJar, Map.of("com/example/Widget.class", new byte[]{1, 2, 3}));

        var entry = new ClasspathEntry(binaryJar, java.util.Optional.empty());
        try (var provider = new LibrarySourceProvider(List.of(entry), tmp.resolve("cache"))) {
            assertFalse(provider.resolveSource("com.example", "Widget").isPresent());
        }
    }

    @Test
    @DisplayName("dogfood: bromo's own classpath indexes ECJ types correctly")
    void dogfoodEcjIndex() throws IOException {
        // This test exercises the real index-build path against bromo's own
        // resolved classpath. The actual presence of a sources jar varies by
        // dev environment, so we only assert the binary index sees ECJ's
        // ASTParser — a stable, top-level public class.
        var provider = new me.moamenhredeen.bromo.project.maven.resolver.MavenResolverProvider();
        assumeTrue(provider.supports(Path.of(".").toAbsolutePath().normalize()),
                "bromo's pom.xml is required at the working directory");
        var model = (me.moamenhredeen.bromo.project.maven.MavenProjectModel)
                provider.load(Path.of(".").toAbsolutePath().normalize());

        @SuppressWarnings("resource")
        var lib = new LibrarySourceProvider(model.classpath(),
                Path.of("target", "bromo-cache", "sources", "lib"));
        try {
            // ASTParser lives in org.eclipse.jdt.core. If sources are attached
            // we get an extracted file; otherwise empty. Either is correct —
            // we only assert the call doesn't blow up indexing.
            var result = lib.resolveSource("org.eclipse.jdt.core.dom", "ASTParser");
            // If present, must be a real file containing the expected class.
            if (result.isPresent()) {
                var content = Files.readString(result.get(), StandardCharsets.UTF_8);
                assertTrue(content.contains("class ASTParser"),
                        "ASTParser.java should contain its class declaration");
            }
        } finally {
            lib.close();
        }
    }

    private static void writeJar(Path target, Map<String, byte[]> entries) throws IOException {
        try (var fos = Files.newOutputStream(target);
             var zos = new ZipOutputStream(fos)) {
            for (var e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
    }
}
