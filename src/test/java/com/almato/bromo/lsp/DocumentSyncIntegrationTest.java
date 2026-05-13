package com.almato.bromo.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// End-to-end: drives `didOpen` → `didChange` → `didClose` through real
/// LSP4J wiring over piped streams and asserts the workspace state after
/// each event (synchronised via the FileStore change listener).
final class DocumentSyncIntegrationTest {

    @Test
    @DisplayName("didOpen → didChange → didClose round-trip updates the workspace")
    void documentSync() throws Exception {
        var clientToServer = new PipedOutputStream();
        var serverIn       = new PipedInputStream(clientToServer, 1 << 14);
        var serverToClient = new PipedOutputStream();
        var clientIn       = new PipedInputStream(serverToClient, 1 << 14);

        var server = new BromoLanguageServer();
        var serverLauncher = LSPLauncher.createServerLauncher(server, serverIn, serverToClient);
        server.connect(serverLauncher.getRemoteProxy());
        serverLauncher.startListening();

        var events = new ArrayBlockingQueue<URI>(16);
        server.workspace().files().addChangeListener(events::add);

        var clientLauncher = Launcher.createLauncher(new SilentClient(), LanguageServer.class, clientIn, clientToServer);
        var serverProxy = clientLauncher.getRemoteProxy();
        clientLauncher.startListening();

        URI uri = URI.create("file:///tmp/Foo.java");

        // didOpen
        var item = new TextDocumentItem(uri.toString(), "java", 1, "class Foo {}");
        serverProxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(item));
        assertEquals(uri, events.poll(5, TimeUnit.SECONDS), "didOpen event");
        var opened = server.workspace().files().getOpen(uri).orElseThrow();
        assertEquals("class Foo {}", opened.text());

        // didChange: replace "Foo" with "Bar"
        var change = new TextDocumentContentChangeEvent(
                new Range(new Position(0, 6), new Position(0, 9)),
                3,
                "Bar");
        var changeParams = new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(uri.toString(), 2),
                List.of(change));
        serverProxy.getTextDocumentService().didChange(changeParams);
        assertEquals(uri, events.poll(5, TimeUnit.SECONDS), "didChange event");
        assertEquals("class Bar {}", server.workspace().files().getOpen(uri).orElseThrow().text());

        // didClose
        serverProxy.getTextDocumentService().didClose(
                new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri.toString())));
        assertEquals(uri, events.poll(5, TimeUnit.SECONDS), "didClose event");
        assertNotNull(server.workspace().files().openDocuments());
        assertEquals(0, server.workspace().files().openDocuments().size());
    }

    static final class SilentClient implements LanguageClient {
        @Override public void telemetryEvent(Object object) {}
        @Override public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {}
        @Override public void showMessage(MessageParams params) {}
        @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void logMessage(MessageParams message) {}
    }
}
