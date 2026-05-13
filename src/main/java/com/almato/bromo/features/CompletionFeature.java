package com.almato.bromo.features;

import com.almato.bromo.compiler.EcjContext;
import com.almato.bromo.symbol.Descriptor;
import com.almato.bromo.symbol.SymbolIndex;
import com.almato.bromo.symbol.SymbolKind;
import com.almato.bromo.util.CancelToken;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/// Two-tier completion router.
///
/// Routing rule:
/// - If the cursor sits **right after a `.`** (with or without a partial
///   identifier between dot and cursor) → delegate to
///   [MemberCompletionResolver] for ECJ-backed member-access completion.
///   That path resolves the receiver's binding, walks its type hierarchy,
///   and enumerates members whose name starts with the partial prefix.
/// - Otherwise → fall back to identifier-prefix completion against the
///   workspace [SymbolIndex].
///
/// If member-access resolution fails (e.g. the receiver's type can't be
/// resolved), the result is an empty list rather than a fall-through to
/// prefix completion — top-level symbols would be the wrong answer for
/// `foo.bar|`.
public final class CompletionFeature {

    private static final int LIMIT = 50;

    private final SymbolIndex symbols;
    private final EcjContext ecj;

    public CompletionFeature(SymbolIndex symbols) {
        this(symbols, null);
    }

    public CompletionFeature(SymbolIndex symbols, EcjContext ecj) {
        this.symbols = symbols;
        this.ecj = ecj;
    }

    public CompletionResult completionsAt(URI uri, CharSequence content, int offset, CancelToken cancel) {
        if (offset < 0 || offset > content.length()) return CompletionResult.EMPTY;
        if (cancel.isCancelled()) return CompletionResult.EMPTY;

        if (MemberCompletionResolver.isAfterDot(content, offset)) {
            if (ecj == null) return CompletionResult.EMPTY;
            return new MemberCompletionResolver(ecj)
                    .tryComplete(uri, content, offset, cancel)
                    .orElse(CompletionResult.EMPTY);
        }

        return prefixCompletion(content, offset, cancel);
    }

    // ---- prefix path -------------------------------------------------------

    private CompletionResult prefixCompletion(CharSequence content, int offset, CancelToken cancel) {
        String prefix = extractPrefix(content, offset);
        if (prefix.isEmpty()) return CompletionResult.EMPTY;

        List<Descriptor> matches = symbols.findByPrefix(prefix, LIMIT + 1);
        if (cancel.isCancelled()) return CompletionResult.EMPTY;

        boolean incomplete = matches.size() > LIMIT;
        if (incomplete) matches = matches.subList(0, LIMIT);

        var items = new ArrayList<CompletionItem>(matches.size());
        for (var d : matches) {
            items.add(new CompletionItem(d.name(), kindFor(d.kind()), detailFor(d)));
        }
        return new CompletionResult(items, incomplete);
    }

    private static String extractPrefix(CharSequence content, int offset) {
        int start = offset;
        while (start > 0 && Character.isJavaIdentifierPart(content.charAt(start - 1))) {
            start--;
        }
        return content.subSequence(start, offset).toString();
    }

    private static String detailFor(Descriptor d) {
        return d.signature() != null && !d.signature().isEmpty() ? d.signature() : d.fqn();
    }

    private static CompletionItemKind kindFor(SymbolKind k) {
        return switch (k) {
            case CLASS       -> CompletionItemKind.CLASS;
            case RECORD      -> CompletionItemKind.RECORD;
            case INTERFACE   -> CompletionItemKind.INTERFACE;
            case ENUM        -> CompletionItemKind.ENUM;
            case ANNOTATION  -> CompletionItemKind.ANNOTATION;
            case METHOD      -> CompletionItemKind.METHOD;
            case CONSTRUCTOR -> CompletionItemKind.CONSTRUCTOR;
            case FIELD       -> CompletionItemKind.FIELD;
        };
    }
}
