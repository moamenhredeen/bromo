package com.almato.bromo.workspace;

import java.util.ArrayList;
import java.util.List;

/// Mutable text buffer for an open document.
///
/// Classic piece-table implementation: an immutable `original` buffer plus an
/// append-only `added` buffer, with an `ArrayList<Piece>` describing the
/// current logical content as a sequence of `(source, offset, length)`
/// triples. Each edit splits at most two pieces and inserts/removes pieces.
///
/// Complexity (n = number of pieces, m = content length):
/// - `insert` / `delete` / `replace`: O(n)
/// - `charAt`: O(n)
/// - `subSequence`: O(n + m')
/// - `text`: O(m)
///
/// An `ArrayList` is intentionally chosen over a balanced tree for v0 — most
/// real documents settle at ≤ a few hundred pieces, well within
/// linear-scan budget. Profile before swapping in a tree.
///
/// Implements [CharSequence] so the buffer can be handed to ECJ without
/// copying to a `String`. Use [#text()] for an eager `String` snapshot.
///
/// **Not thread-safe.** The owning [Document] serializes access.
public final class PieceTable implements CharSequence {

    private enum Source { ORIGINAL, ADDED }

    private record Piece(Source source, int offset, int length) {
        Piece slice(int relStart, int relLength) {
            return new Piece(source, offset + relStart, relLength);
        }
    }

    private final char[] original;
    private final StringBuilder added = new StringBuilder();
    private final List<Piece> pieces = new ArrayList<>();
    private int length;

    public PieceTable(String initial) {
        this.original = initial.toCharArray();
        if (!initial.isEmpty()) {
            pieces.add(new Piece(Source.ORIGINAL, 0, initial.length()));
            length = initial.length();
        }
    }

    public void insert(int offset, String text) {
        if (text.isEmpty()) return;
        if (offset < 0 || offset > length) {
            throw new IndexOutOfBoundsException("offset=" + offset + ", length=" + length);
        }
        int addedStart = added.length();
        added.append(text);
        var inserted = new Piece(Source.ADDED, addedStart, text.length());

        if (offset == length) {
            pieces.add(inserted);
        } else {
            int[] loc = locate(offset);
            int idx = loc[0];
            int rel = loc[1];
            var piece = pieces.get(idx);
            if (rel == 0) {
                pieces.add(idx, inserted);
            } else {
                var left = piece.slice(0, rel);
                var right = piece.slice(rel, piece.length - rel);
                pieces.set(idx, left);
                pieces.add(idx + 1, inserted);
                pieces.add(idx + 2, right);
            }
        }
        length += text.length();
    }

    public void delete(int offset, int len) {
        if (len == 0) return;
        if (offset < 0 || len < 0 || offset + len > length) {
            throw new IndexOutOfBoundsException(
                    "offset=" + offset + ", len=" + len + ", length=" + length);
        }
        int[] loc = locate(offset);
        int i = loc[0];
        int rel = loc[1];
        int remaining = len;

        // Trim the start piece if the deletion begins mid-piece.
        if (rel > 0) {
            var piece = pieces.get(i);
            int tail = piece.length - rel;
            if (remaining < tail) {
                // Middle delete within a single piece: split into [0, rel) and [rel+remaining, end).
                var left = piece.slice(0, rel);
                var right = piece.slice(rel + remaining, tail - remaining);
                pieces.set(i, left);
                pieces.add(i + 1, right);
                length -= len;
                return;
            }
            // Delete the tail of the start piece.
            pieces.set(i, piece.slice(0, rel));
            remaining -= tail;
            i++;
        }
        // Drop or trim subsequent pieces.
        while (remaining > 0 && i < pieces.size()) {
            var piece = pieces.get(i);
            if (piece.length <= remaining) {
                pieces.remove(i);
                remaining -= piece.length;
            } else {
                pieces.set(i, piece.slice(remaining, piece.length - remaining));
                remaining = 0;
            }
        }
        length -= len;
    }

    public void replace(int offset, int len, String text) {
        delete(offset, len);
        insert(offset, text);
    }

    /// Eager snapshot as a fresh `String`. O(n + m).
    public String text() {
        var sb = new StringBuilder(length);
        for (var piece : pieces) {
            appendPiece(sb, piece, 0, piece.length);
        }
        return sb.toString();
    }

    @Override public int length() { return length; }

    @Override
    public char charAt(int index) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("index=" + index + ", length=" + length);
        }
        int[] loc = locate(index);
        var piece = pieces.get(loc[0]);
        int abs = piece.offset + loc[1];
        return piece.source == Source.ORIGINAL ? original[abs] : added.charAt(abs);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        if (start < 0 || end > length || start > end) {
            throw new IndexOutOfBoundsException(
                    "start=" + start + ", end=" + end + ", length=" + length);
        }
        var sb = new StringBuilder(end - start);
        int remaining = end - start;
        int[] loc = locate(start);
        int i = loc[0];
        int rel = loc[1];
        while (remaining > 0 && i < pieces.size()) {
            var piece = pieces.get(i);
            int take = Math.min(remaining, piece.length - rel);
            appendPiece(sb, piece, rel, take);
            remaining -= take;
            i++;
            rel = 0;
        }
        return sb.toString();
    }

    @Override public String toString() { return text(); }

    /// Number of pieces currently held — exposed for diagnostics + tests.
    int pieceCount() { return pieces.size(); }

    private void appendPiece(StringBuilder sb, Piece piece, int relStart, int relLength) {
        if (piece.source == Source.ORIGINAL) {
            sb.append(original, piece.offset + relStart, relLength);
        } else {
            int from = piece.offset + relStart;
            sb.append(added, from, from + relLength);
        }
    }

    /// Returns `{pieceIndex, relativeOffsetWithinPiece}` for the given global offset.
    /// For `offset == length`, returns `{pieces.size(), 0}`.
    private int[] locate(int offset) {
        int acc = 0;
        for (int i = 0; i < pieces.size(); i++) {
            int plen = pieces.get(i).length;
            if (offset < acc + plen) {
                return new int[]{i, offset - acc};
            }
            acc += plen;
        }
        return new int[]{pieces.size(), 0};
    }
}
