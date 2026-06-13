package me.moamenhredeen.bromo.features;

import java.util.List;

/// Result of a completion query.
///
/// `isIncomplete` mirrors the LSP signal: when `true`, the client re-queries
/// as the user types more characters; when `false`, the items are
/// authoritative for the current prefix.
public record CompletionResult(List<CompletionItem> items, boolean isIncomplete) {

    public static final CompletionResult EMPTY = new CompletionResult(List.of(), false);

    public CompletionResult {
        items = List.copyOf(items);
    }
}
