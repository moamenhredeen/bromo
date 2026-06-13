package me.moamenhredeen.bromo.project;

import java.io.IOException;
import java.nio.file.Path;

/// SPI: build-system-specific loader for a [ProjectModel].
///
/// Implementations are discovered at workspace open: each provider's
/// [#supports] is called against the workspace root; the first matching
/// provider's [#load] is invoked to produce a [ProjectModel].
///
/// v0 ships exactly one provider:
/// [me.moamenhredeen.bromo.project.maven.resolver.MavenResolverProvider]. The R1
/// replacement track adds a hand-rolled Maven provider; future work brings
/// Gradle / Bazel.
public interface ProjectModelProvider {

    /// `true` iff this provider can produce a [ProjectModel] for [workspaceRoot].
    /// Should be cheap (filesystem checks only).
    boolean supports(Path workspaceRoot);

    /// Load the project model for [workspaceRoot]. May involve network I/O on
    /// first call (downloading deps); subsequent calls usually hit local caches.
    ProjectModel load(Path workspaceRoot) throws IOException;

    /// Short identifier, used in logs and progress messages (e.g. `"maven-resolver"`).
    String name();
}
