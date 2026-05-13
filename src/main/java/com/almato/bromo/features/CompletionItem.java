package com.almato.bromo.features;

/// A single completion proposal.
///
/// `label`  — what's shown in the popup (the identifier).
/// `kind`   — drives icon + sort order in the editor.
/// `detail` — secondary text (signature / qualified name).
/// `insertText` — what's inserted when the user accepts; defaults to `label`
///   if `null`.
public record CompletionItem(
        String label,
        CompletionItemKind kind,
        String detail,
        String insertText) {

    public CompletionItem(String label, CompletionItemKind kind, String detail) {
        this(label, kind, detail, null);
    }
}
