package com.almato.bromo.project.classpath;

import com.almato.bromo.project.ClasspathEntry;
import java.util.List;

/// Holds the workspace's resolved classpath.
///
/// v0 surface: just the entries. M4 adds lookup helpers (e.g. find-jar-for-type).
public final class ClasspathService {

    private final List<ClasspathEntry> entries;

    public ClasspathService(List<ClasspathEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<ClasspathEntry> entries() {
        return entries;
    }
}
