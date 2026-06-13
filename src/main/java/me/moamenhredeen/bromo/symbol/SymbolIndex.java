package me.moamenhredeen.bromo.symbol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/// Workspace-wide symbol index keyed by simple name.
///
/// Backing structure: a [ConcurrentSkipListMap] from `name` (case-sensitive)
/// to a synchronized list of descriptors. Prefix scans use [#findByPrefix]
/// which walks the tail map until the prefix stops matching.
///
/// **Why not a trie at v0?** A skip-list map's `tailMap` + early break is
/// `O(log n + k)` for `k` matches — comfortably inside the 30 ms p95 budget
/// even on workspaces of thousands of files. If the bench shows otherwise,
/// swap in a trie behind this same interface.
///
/// Thread-safe for concurrent adds during scan and concurrent reads during
/// queries.
public final class SymbolIndex {

    private final NavigableMap<String, List<Descriptor>> byName = new ConcurrentSkipListMap<>();

    public void add(Descriptor descriptor) {
        byName.computeIfAbsent(descriptor.name(), _ -> Collections.synchronizedList(new ArrayList<>()))
                .add(descriptor);
    }

    public void addAll(Collection<Descriptor> descriptors) {
        for (var d : descriptors) add(d);
    }

    /// Total descriptor count across all bins. O(b) where b = unique names.
    public int size() {
        int sum = 0;
        for (var v : byName.values()) {
            synchronized (v) { sum += v.size(); }
        }
        return sum;
    }

    public List<Descriptor> findExact(String name) {
        var bucket = byName.get(name);
        if (bucket == null) return List.of();
        synchronized (bucket) {
            return List.copyOf(bucket);
        }
    }

    /// Prefix scan: case-sensitive, returns up to [limit] descriptors in
    /// ascending name order. Walks the skip-list tail starting at [prefix];
    /// stops at the first bin whose name no longer starts with [prefix].
    public List<Descriptor> findByPrefix(String prefix, int limit) {
        var results = new ArrayList<Descriptor>();
        for (var entry : byName.tailMap(prefix).entrySet()) {
            if (!entry.getKey().startsWith(prefix)) break;
            var bucket = entry.getValue();
            synchronized (bucket) {
                for (var d : bucket) {
                    results.add(d);
                    if (results.size() >= limit) return results;
                }
            }
        }
        return results;
    }

    public void clear() {
        byName.clear();
    }

    /// All descriptors in insertion order across all bins. Snapshot copy —
    /// safe to iterate while writes are in flight.
    public List<Descriptor> all() {
        var out = new ArrayList<Descriptor>();
        for (var bucket : byName.values()) {
            synchronized (bucket) {
                out.addAll(bucket);
            }
        }
        return out;
    }
}
