package me.moamenhredeen.bromo.query;

import me.moamenhredeen.bromo.compiler.EcjContext;
import me.moamenhredeen.bromo.workspace.Document;
import me.moamenhredeen.bromo.workspace.FileStore;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.eclipse.jdt.core.dom.CompilationUnit;

/// Salsa-style cache for derived language data keyed on per-file revisions.
///
/// v0 surface: a single query — `cachedParsedAst(uri)` — which returns a
/// binding-resolved DOM [CompilationUnit] for an open document. The cache is
/// keyed by `(uri, revision)`; on a revision miss the AST is re-parsed via
/// [EcjContext#parseWithBindings] and stored. Closed files are **not** cached
/// (their content is reread on each call from the cold path).
///
/// Invalidation is fan-in: the engine subscribes to [FileStore] change events
/// and drops the per-URI entry whenever a `didOpen` / `didChange` /
/// `didClose` fires. This avoids stalling readers on a write barrier — the
/// next read recomputes off the new revision.
///
/// Thread-safety: backed by a [ConcurrentHashMap], with per-URI atomicity
/// supplied by `compute`. Two callers racing for the same cold URI serialise
/// inside `compute`; callers for different URIs run in parallel. The
/// compute lambda may run twice if the document is edited between bucket
/// reads — but only on a revision *advance*, never on a *regression*, so it
/// is bounded re-work and never returns a stale AST.
///
/// Hot path: a cache hit costs one `ConcurrentHashMap.get` (~tens of ns)
/// plus a `Document.revision()` read. No allocation, no parsing, no
/// boxing.
public final class QueryEngine implements AutoCloseable {

    private final FileStore files;
    private final EcjContext ecj;
    private final ConcurrentHashMap<URI, AstEntry> astCache = new ConcurrentHashMap<>();
    private final Consumer<URI> invalidator = this::invalidate;

    public QueryEngine(FileStore files, EcjContext ecj) {
        this.files = files;
        this.ecj = ecj;
        files.addChangeListener(invalidator);
    }

    /// Returns the binding-resolved AST + the source `char[]` it was parsed
    /// from. Cache-hit on revision match; cache-miss re-parses through ECJ.
    /// Empty when [uri] is not currently open — caller falls through to the
    /// cold path (read-then-parse).
    ///
    /// Returning the source alongside the AST is what lets the hot path skip
    /// a second `text().toCharArray()` materialization. Hover/definition need
    /// the source array to slice javadoc out of AST node ranges.
    public Optional<AstSnapshot> cachedSnapshot(URI uri) {
        Document doc = files.getOpen(uri).orElse(null);
        if (doc == null) return Optional.empty();
        long rev = doc.revision().value();

        AstEntry entry = astCache.compute(uri, (key, existing) -> {
            if (existing != null && existing.revision == rev) return existing;
            char[] content = doc.text().toCharArray();
            return new AstEntry(rev, content, ecj.parseWithBindings(key, content));
        });
        return Optional.of(new AstSnapshot(entry.ast, entry.source));
    }

    /// Returns just the binding-resolved AST for [uri] if it is an open
    /// document. Convenience over [#cachedSnapshot] for callers that don't
    /// need the source array.
    public Optional<CompilationUnit> cachedParsedAst(URI uri) {
        return cachedSnapshot(uri).map(AstSnapshot::ast);
    }

    /// A cached parse together with the source it was parsed from.
    /// Sharing the array is safe because both fields are written once at
    /// cache-miss time and never mutated thereafter — `char[]` is treated
    /// as immutable for the lifetime of this entry.
    public record AstSnapshot(CompilationUnit ast, char[] source) {}

    /// Drop the cached entry for [uri]. Called from the [FileStore] change
    /// listener — also exposed for tests and explicit invalidation.
    public void invalidate(URI uri) {
        astCache.remove(uri);
    }

    /// Current number of cached entries — for diagnostics and tests.
    public int cachedSize() {
        return astCache.size();
    }

    @Override
    public void close() {
        files.removeChangeListener(invalidator);
        astCache.clear();
    }

    private record AstEntry(long revision, char[] source, CompilationUnit ast) {}
}
