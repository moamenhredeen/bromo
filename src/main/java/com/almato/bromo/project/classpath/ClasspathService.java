package com.almato.bromo.project.classpath;

import java.nio.file.Path;
import java.util.List;

/// Holds the workspace's resolved classpath.
///
/// v0 surface: just the entries. M4 adds lookup helpers (e.g. find-jar-for-type).
public final class ClasspathService {

    private final List<Path> entries;

    public ClasspathService(List<Path> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<Path> entries() {
        return entries;
    }
}
