package com.almato.bromo.lsp;

import com.almato.bromo.features.CompletionFeature;
import com.almato.bromo.features.DefinitionFeature;
import com.almato.bromo.features.HoverFeature;
import com.almato.bromo.util.CancelToken;
import com.almato.bromo.workspace.Document;
import com.almato.bromo.workspace.Workspace;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

/// LSP4J adapter for text-document notifications and requests.
///
/// Feature handlers (hover, definition, completion) are paradigm-agnostic and
/// take our own param records + a [CancelToken]; this class is the **only**
/// layer that touches `CompletableFuture` + LSP4J types.
///
/// Diagnostic publishing is debounced — multiple `didChange` events within
/// the debounce window coalesce into a single workspace compile.
public final class BromoTextDocumentService implements TextDocumentService {

    private static final System.Logger LOG = System.getLogger(BromoTextDocumentService.class.getName());
    private static final long DIAGNOSTIC_DEBOUNCE_MS = 200;

    private final Workspace workspace;
    private final Executor requestExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService diagnosticScheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private volatile ScheduledFuture<?> pendingDiagnostics;
    private volatile LanguageClient client;

    public BromoTextDocumentService(Workspace workspace) {
        this.workspace = workspace;
    }

    void setClient(LanguageClient client) {
        this.client = client;
    }

    void shutdown() {
        diagnosticScheduler.shutdownNow();
    }

    // ---- document sync -----------------------------------------------------

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        var item = params.getTextDocument();
        var languageId = item.getLanguageId() != null ? item.getLanguageId() : "java";
        workspace.files().openDocument(Adapters.uri(item), languageId, item.getText());
        scheduleDiagnostics();
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        URI uri = Adapters.uri(params.getTextDocument());
        Document doc = workspace.files().getOpen(uri).orElseThrow(
                () -> new IllegalStateException("didChange on un-open document: " + uri));
        for (var change : params.getContentChanges()) {
            if (change.getRange() == null) {
                doc.applyFullChange(change.getText(), workspace.files().nextRevision());
            } else {
                var range = change.getRange();
                var content = doc.content();
                int start = Adapters.rangeStart(content, range);
                int end = Adapters.rangeEnd(content, range);
                doc.applyRangeEdit(start, end - start, change.getText(), workspace.files().nextRevision());
            }
        }
        workspace.files().notifyEdit(uri);
        scheduleDiagnostics();
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        workspace.files().closeDocument(Adapters.uri(params.getTextDocument()));
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        scheduleDiagnostics();
    }

    // ---- feature requests --------------------------------------------------

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFuture.supplyAsync(() -> {
            var ecj = workspace.ecj().orElse(null);
            if (ecj == null) return null;
            URI uri = Adapters.uri(params.getTextDocument());
            CharSequence content = contentOf(uri);
            if (content == null) return null;
            int offset = Adapters.offset(content, params.getPosition());
            var feature = new HoverFeature(ecj, workspace.files());
            return feature.hover(uri, offset, CancelToken.never())
                    .map(r -> Adapters.toLspHover(r, content))
                    .orElse(null);
        }, requestExecutor);
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            DefinitionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            var ecj = workspace.ecj().orElse(null);
            if (ecj == null) return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of());
            URI uri = Adapters.uri(params.getTextDocument());
            CharSequence content = contentOf(uri);
            if (content == null) return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of());
            int offset = Adapters.offset(content, params.getPosition());
            var feature = new DefinitionFeature(ecj, workspace.files(), workspace.symbols());
            var result = feature.definition(uri, offset, CancelToken.never());
            if (result.isEmpty()) {
                return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of());
            }
            var targetContent = contentOf(result.get().uri());
            if (targetContent == null) {
                return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of());
            }
            return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(
                    List.of(Adapters.toLspLocation(result.get(), targetContent)));
        }, requestExecutor);
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            URI uri = Adapters.uri(params.getTextDocument());
            CharSequence content = contentOf(uri);
            if (content == null) {
                return Either.<List<CompletionItem>, CompletionList>forRight(new CompletionList(false, List.of()));
            }
            int offset = Adapters.offset(content, params.getPosition());
            var feature = new CompletionFeature(workspace.symbols(), workspace.ecj().orElse(null));
            var result = feature.completionsAt(uri, content, offset, CancelToken.never());
            return Either.<List<CompletionItem>, CompletionList>forRight(Adapters.toLspCompletionList(result));
        }, requestExecutor);
    }

    // ---- diagnostic publishing ---------------------------------------------

    private void scheduleDiagnostics() {
        var previous = pendingDiagnostics;
        if (previous != null) previous.cancel(false);
        pendingDiagnostics = diagnosticScheduler.schedule(
                this::publishDiagnostics, DIAGNOSTIC_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void publishDiagnostics() {
        var ecj = workspace.ecj().orElse(null);
        if (ecj == null || client == null) return;
        try {
            var diagnostics = ecj.compileWorkspace();
            for (var entry : diagnostics.entrySet()) {
                var content = contentOf(entry.getKey());
                if (content == null) continue;
                PublishDiagnosticsParams params = Adapters.toPublishDiagnostics(
                        entry.getKey(), entry.getValue(), content);
                client.publishDiagnostics(params);
            }
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "diagnostic compile failed", e);
        }
    }

    // ---- helpers -----------------------------------------------------------

    private CharSequence contentOf(URI uri) {
        var open = workspace.files().getOpen(uri);
        if (open.isPresent()) return open.get().content();
        try {
            return Files.readString(Paths.get(uri), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
