package me.moamenhredeen.bromo.lsp;

import me.moamenhredeen.bromo.compiler.DiagnosticSeverity;
import me.moamenhredeen.bromo.features.CompletionItemKind;
import me.moamenhredeen.bromo.features.CompletionResult;
import me.moamenhredeen.bromo.features.DefinitionResult;
import me.moamenhredeen.bromo.features.HoverResult;
import me.moamenhredeen.bromo.workspace.Positions;
import me.moamenhredeen.bromo.workspace.Uris;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;

/// Translators between LSP4J protocol types and bromo's internal records.
final class Adapters {
    private Adapters() {}

    static URI uri(TextDocumentItem item)                    { return Uris.parse(item.getUri()); }
    static URI uri(TextDocumentIdentifier id)                { return Uris.parse(id.getUri()); }
    static URI uri(VersionedTextDocumentIdentifier id)       { return Uris.parse(id.getUri()); }

    static int offset(CharSequence content, Position position) {
        return Positions.positionToOffset(content, position.getLine(), position.getCharacter());
    }

    static int rangeStart(CharSequence content, Range range) { return offset(content, range.getStart()); }
    static int rangeEnd(CharSequence content, Range range)   { return offset(content, range.getEnd()); }

    static Position toLspPosition(CharSequence content, int offset) {
        var lc = Positions.offsetToPosition(content, offset);
        return new Position(lc.line(), lc.character());
    }

    static Range toLspRange(CharSequence content, int startOffset, int endOffset) {
        return new Range(toLspPosition(content, startOffset), toLspPosition(content, endOffset));
    }

    static Hover toLspHover(HoverResult result, CharSequence content) {
        return new Hover(
                new MarkupContent(MarkupKind.MARKDOWN, result.markdown()),
                toLspRange(content, result.startOffset(), result.endOffset()));
    }

    static Location toLspLocation(DefinitionResult result, CharSequence targetContent) {
        return new Location(
                result.uri().toString(),
                toLspRange(targetContent, result.startOffset(), result.endOffset()));
    }

    static CompletionList toLspCompletionList(CompletionResult result) {
        var items = new ArrayList<org.eclipse.lsp4j.CompletionItem>(result.items().size());
        for (var item : result.items()) {
            var lspItem = new org.eclipse.lsp4j.CompletionItem();
            lspItem.setLabel(item.label());
            lspItem.setKind(toLspCompletionKind(item.kind()));
            if (item.detail() != null) lspItem.setDetail(item.detail());
            if (item.insertText() != null) lspItem.setInsertText(item.insertText());
            items.add(lspItem);
        }
        return new CompletionList(result.isIncomplete(), items);
    }

    static org.eclipse.lsp4j.CompletionItemKind toLspCompletionKind(CompletionItemKind kind) {
        return switch (kind) {
            case CLASS       -> org.eclipse.lsp4j.CompletionItemKind.Class;
            case INTERFACE   -> org.eclipse.lsp4j.CompletionItemKind.Interface;
            case ENUM        -> org.eclipse.lsp4j.CompletionItemKind.Enum;
            case RECORD      -> org.eclipse.lsp4j.CompletionItemKind.Struct;
            case ANNOTATION  -> org.eclipse.lsp4j.CompletionItemKind.Class;
            case METHOD      -> org.eclipse.lsp4j.CompletionItemKind.Method;
            case CONSTRUCTOR -> org.eclipse.lsp4j.CompletionItemKind.Constructor;
            case FIELD       -> org.eclipse.lsp4j.CompletionItemKind.Field;
            case VARIABLE    -> org.eclipse.lsp4j.CompletionItemKind.Variable;
            case KEYWORD     -> org.eclipse.lsp4j.CompletionItemKind.Keyword;
            case SNIPPET     -> org.eclipse.lsp4j.CompletionItemKind.Snippet;
        };
    }

    static org.eclipse.lsp4j.Diagnostic toLspDiagnostic(
            me.moamenhredeen.bromo.compiler.Diagnostic d, CharSequence content) {
        var lsp = new org.eclipse.lsp4j.Diagnostic();
        lsp.setRange(toLspRange(content, d.startOffset(), d.endOffset()));
        lsp.setSeverity(toLspSeverity(d.severity()));
        lsp.setMessage(d.message());
        lsp.setSource("bromo");
        lsp.setCode(d.code());
        return lsp;
    }

    static PublishDiagnosticsParams toPublishDiagnostics(
            URI uri,
            List<me.moamenhredeen.bromo.compiler.Diagnostic> diagnostics,
            CharSequence content) {
        var lspDiags = new ArrayList<org.eclipse.lsp4j.Diagnostic>(diagnostics.size());
        for (var d : diagnostics) lspDiags.add(toLspDiagnostic(d, content));
        return new PublishDiagnosticsParams(uri.toString(), lspDiags);
    }

    private static org.eclipse.lsp4j.DiagnosticSeverity toLspSeverity(DiagnosticSeverity severity) {
        return switch (severity) {
            case ERROR   -> org.eclipse.lsp4j.DiagnosticSeverity.Error;
            case WARNING -> org.eclipse.lsp4j.DiagnosticSeverity.Warning;
            case INFO    -> org.eclipse.lsp4j.DiagnosticSeverity.Information;
            case HINT    -> org.eclipse.lsp4j.DiagnosticSeverity.Hint;
        };
    }
}
