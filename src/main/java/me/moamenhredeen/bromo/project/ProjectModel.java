package me.moamenhredeen.bromo.project;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/// Build-system-agnostic view of a workspace.
///
/// Once `module-info.java` lands (JPMS milestone after M2), this becomes
/// `sealed`; cross-package `permits` requires a named module. For now it's a
/// regular interface and discipline keeps the impl set small.
public interface ProjectModel {

    /// Absolute workspace root (the directory the LSP client opened).
    Path root();

    /// All source roots (main + test). Each is an existing directory under [#root()],
    /// or the empty list if none were detected.
    List<Path> sourceRoots();

    /// Resolved classpath. Each entry pairs a compiled artifact with its
    /// optional source attachment. Order matters: earlier entries win on
    /// conflict, matching javac's classpath ordering rules.
    List<ClasspathEntry> classpath();

    /// Convenience view of [#classpath()] reduced to binary paths only.
    /// ECJ's `INameEnvironment` and friends only need compiled artifacts,
    /// so most compile-side callers reach for this projection.
    default List<Path> classpathBinaries() {
        return classpath().stream().map(ClasspathEntry::binary).toList();
    }

    /// Target JDK release (e.g. `"25"`). Maps to javac/ECJ `--release`.
    Optional<String> javaRelease();

    /// Additional compiler args specified in the build (e.g. `--enable-preview`).
    List<String> compilerArgs();

    /// Human-readable project name. Used in logs and progress messages.
    String name();
}
