package me.moamenhredeen.bromo.symbol;

/// Kinds of symbol bromo extracts from a compilation unit.
///
/// Maps loosely onto LSP `SymbolKind` for `workspace/symbol`; the adapter
/// layer does the LSP4J translation.
public enum SymbolKind {
    CLASS,
    INTERFACE,
    ENUM,
    RECORD,
    ANNOTATION,
    METHOD,
    CONSTRUCTOR,
    FIELD,
}
