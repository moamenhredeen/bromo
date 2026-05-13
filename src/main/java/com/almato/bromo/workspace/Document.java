package com.almato.bromo.workspace;

import java.net.URI;
import java.util.Objects;

/// An open document — its mutable content (piece table) plus revision number.
///
/// Edits arrive from `didChange`, are translated to flat character offsets by
/// the adapter layer, and apply in place. Each edit advances [#revision()].
///
/// Thread-safety: mutation and read methods are `synchronized`. The
/// underlying [PieceTable] is intentionally not thread-safe on its own —
/// `Document` is the synchronization boundary.
public final class Document {

    private final URI uri;
    private final String languageId;
    private PieceTable buffer;
    private Revision revision;

    public Document(URI uri, String languageId, String text, Revision initial) {
        this.uri = Objects.requireNonNull(uri, "uri");
        this.languageId = Objects.requireNonNull(languageId, "languageId");
        this.buffer = new PieceTable(Objects.requireNonNull(text, "text"));
        this.revision = Objects.requireNonNull(initial, "initial");
    }

    public URI uri() { return uri; }
    public String languageId() { return languageId; }

    public synchronized Revision revision() { return revision; }

    public synchronized int length() { return buffer.length(); }

    /// Returns the current buffer as a `CharSequence`. The view is **live** —
    /// concurrent edits change what subsequent reads see. For a stable snapshot
    /// use [#text()].
    public synchronized CharSequence content() { return buffer; }

    /// Eager snapshot of the current content as a fresh `String`.
    public synchronized String text() { return buffer.text(); }

    public synchronized void applyRangeEdit(int offset, int length, String newText, Revision next) {
        buffer.replace(offset, length, newText);
        revision = next;
    }

    public synchronized void applyFullChange(String newText, Revision next) {
        buffer = new PieceTable(newText);
        revision = next;
    }
}
