package com.almato.bromo.project.maven;

import com.almato.bromo.project.ProjectModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/// Maven flavor of [ProjectModel].
///
/// Produced by either:
/// - v0: [com.almato.bromo.project.maven.resolver.MavenResolverProvider]
/// - v0.1+ (R1 trigger): a hand-rolled `pom.xml` parser
///
/// Both produce the same record so consumers don't care which loader ran.
public record MavenProjectModel(
        Path root,
        List<Path> sourceRoots,
        List<Path> classpath,
        Optional<String> javaRelease,
        List<String> compilerArgs,
        String groupId,
        String artifactId,
        String version
) implements ProjectModel {

    @Override
    public String name() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
