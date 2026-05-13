package com.almato.bromo.project;

import java.nio.file.Path;
import java.util.Optional;

/// A single classpath element: a compiled artifact and (optionally) its
/// attached source archive.
///
/// Modelled after Eclipse JDT's `IClasspathEntry.sourceAttachmentPath` /
/// IntelliJ's `Library.RootProvider`: the source attachment is a property
/// of the entry itself, not a parallel list, so consumers can't accidentally
/// pair a binary with the wrong source jar.
///
/// `binary` is a jar, a class directory, or any path ECJ can read as a
/// `FileSystem.Classpath`. `sources`, when present, is a jar or zip whose
/// entries are `.java` files matching the binaries — e.g. a Maven
/// `-sources` classifier artifact or the JDK's `lib/src.zip`.
public record ClasspathEntry(Path binary, Optional<Path> sources) {

    public ClasspathEntry {
        if (binary == null) throw new IllegalArgumentException("binary path is required");
        if (sources == null) throw new IllegalArgumentException("sources must be Optional, not null");
    }

    public static ClasspathEntry of(Path binary) {
        return new ClasspathEntry(binary, Optional.empty());
    }

    public static ClasspathEntry of(Path binary, Path sources) {
        return new ClasspathEntry(binary, Optional.of(sources));
    }
}
