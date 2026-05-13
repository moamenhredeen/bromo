package com.almato.bromo.workspace;

/// Monotonic revision number.
///
/// The [FileStore] hands out a fresh `Revision` for every file mutation
/// (open / edit / close). Each `Document` carries the revision of its last
/// mutation, so query caches can recognise stale entries with a single
/// comparison.
///
/// Revisions are total-ordered and comparable; equality is value equality.
public record Revision(long value) implements Comparable<Revision> {

    /// The revision held by a document before any edit has been applied.
    public static final Revision INITIAL = new Revision(0);

    public Revision next() {
        return new Revision(value + 1);
    }

    @Override
    public int compareTo(Revision other) {
        return Long.compare(value, other.value);
    }
}
