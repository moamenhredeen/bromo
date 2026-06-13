package me.moamenhredeen.bromo.symbol;

import java.net.URI;

/// A single entry in the [SymbolIndex] — one declaration in one source file.
///
/// Fields:
/// - `kind`        — what was declared (see [SymbolKind]).
/// - `simpleName`  — the identifier as it appears in source (e.g. `parse`).
/// - `binaryName`  — fully-qualified name including enclosing types
///                   (e.g. `me.moamenhredeen.bromo.workspace.Uris.parse`).
/// - `descriptor`  — a stable string describing the declaration's signature
///                   (e.g. `(String) : URI` for methods, `URI` for fields,
///                   empty for types). Not strictly JVM-format yet; will become
///                   so when ECJ bindings drive Tier-2 lookups.
/// - `uri`         — source file URI.
/// - `startOffset` — start of the identifier in the source's char stream.
/// - `endOffset`   — exclusive end of the identifier.
public record SymbolEntry(
        SymbolKind kind,
        String simpleName,
        String binaryName,
        String descriptor,
        URI uri,
        int startOffset,
        int endOffset
) {}
