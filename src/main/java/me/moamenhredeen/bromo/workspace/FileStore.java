package me.moamenhredeen.bromo.workspace;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/// URI → file content registry.
///
/// Maintains two views per URI:
/// - **Open document**: client-driven, mutable via `didChange`. Backed by a
///   piece table.
/// - **Cached unopened buffer**: lazily loaded on demand from disk; mmap-backed.
///
/// A monotonic global clock issues a fresh [Revision] for every file mutation
/// (open, edit, close) so query caches can decide invalidation with a single
/// comparison.
///
/// External consumers can subscribe to mutation events via [#addChangeListener].
/// Listeners are invoked synchronously on the calling thread after the store's
/// state has been updated.
public final class FileStore {

    private final ConcurrentHashMap<URI, Document> open = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<URI, MmapBuffer> cached = new ConcurrentHashMap<>();
    private final AtomicLong clock = new AtomicLong(0);
    private final CopyOnWriteArrayList<Consumer<URI>> listeners = new CopyOnWriteArrayList<>();

    /// Allocate the next monotonic revision.
    public Revision nextRevision() {
        return new Revision(clock.incrementAndGet());
    }

    /// Begin tracking [uri] as an open document with the given initial [text].
    /// Invalidates any previously cached mmap view of the same file.
    public Document openDocument(URI uri, String languageId, String text) {
        var doc = new Document(uri, languageId, text, nextRevision());
        open.put(uri, doc);
        cached.remove(uri);
        fire(uri);
        return doc;
    }

    public Optional<Document> getOpen(URI uri) {
        return Optional.ofNullable(open.get(uri));
    }

    public Collection<Document> openDocuments() {
        return open.values();
    }

    /// Notify listeners that an open document at [uri] has been edited. The
    /// edit itself is applied by [Document] mutation methods; this only
    /// fans out the change event to subscribers (e.g., query engine).
    public void notifyEdit(URI uri) {
        fire(uri);
    }

    /// Drop the open-document state for [uri]. The next [#contentOf] call
    /// will re-read from disk.
    public void closeDocument(URI uri) {
        open.remove(uri);
        cached.remove(uri);
        fire(uri);
    }

    /// Snapshot the content for [uri] from the appropriate source:
    /// open buffer if the client has the document open, mmap-cached otherwise.
    /// Mmap reads are cached until invalidated by an `open` or `close`.
    public CharSequence contentOf(URI uri) throws IOException {
        var doc = open.get(uri);
        if (doc != null) {
            return doc.content();
        }
        var hit = cached.get(uri);
        if (hit != null) return hit;
        var buf = MmapBuffer.open(Uris.toPath(uri));
        cached.put(uri, buf);
        return buf;
    }

    public void addChangeListener(Consumer<URI> listener) {
        listeners.add(listener);
    }

    public void removeChangeListener(Consumer<URI> listener) {
        listeners.remove(listener);
    }

    private void fire(URI uri) {
        for (var listener : listeners) {
            listener.accept(uri);
        }
    }
}
