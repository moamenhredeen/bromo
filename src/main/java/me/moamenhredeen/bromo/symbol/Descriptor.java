package me.moamenhredeen.bromo.symbol;

import java.nio.file.Path;

/// A single symbol record in the Tier-1 index.
///
/// `signature` carries an LSP-friendly rendering of:
/// - method signatures (e.g. `foo(int, String) : void`)
/// - field types (e.g. `String`)
/// - `null` for types
///
/// `offset` / `length` are absolute character offsets into [#source]; the LSP
/// layer converts them to `Range` via [me.moamenhredeen.bromo.workspace.Positions]
/// when answering requests.
public record Descriptor(
        SymbolKind kind,
        String fqn,
        String name,
        String signature,
        Path source,
        int offset,
        int length) {

    public Descriptor {
        if (kind == null)   throw new IllegalArgumentException("kind");
        if (fqn == null)    throw new IllegalArgumentException("fqn");
        if (name == null)   throw new IllegalArgumentException("name");
        if (source == null) throw new IllegalArgumentException("source");
    }
}
