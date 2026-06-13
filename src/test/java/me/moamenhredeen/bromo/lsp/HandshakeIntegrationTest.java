package me.moamenhredeen.bromo.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// End-to-end smoke test for the LSP `initialize` handshake — M0 acceptance.
///
/// Spawns [BromoLanguageServer] in-process over piped streams, sends
/// `initialize` as a real LSP4J client, and asserts a well-formed response
/// naming "bromo". This is the closest possible mirror of what an editor does
/// when attaching to the server over stdio, minus the OS-level process boundary.
final class HandshakeIntegrationTest {

    @Test
    @DisplayName("initialize returns capabilities and server info")
    void handshake() throws Exception {
        var clientToServer = new PipedOutputStream();
        var serverIn = new PipedInputStream(clientToServer, 1 << 14);
        var serverToClient = new PipedOutputStream();
        var clientIn = new PipedInputStream(serverToClient, 1 << 14);

        var server = new BromoLanguageServer();
        var serverLauncher = LSPLauncher.createServerLauncher(server, serverIn, serverToClient);
        server.connect(serverLauncher.getRemoteProxy());
        serverLauncher.startListening();

        var clientLauncher = Launcher.createLauncher(
                new SilentClient(),
                LanguageServer.class,
                clientIn,
                clientToServer);
        var serverProxy = clientLauncher.getRemoteProxy();
        clientLauncher.startListening();

        var result = serverProxy.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);

        assertNotNull(result, "initialize result");
        assertNotNull(result.getCapabilities(), "capabilities");
        assertNotNull(result.getServerInfo(), "server info");
        assertEquals("bromo", result.getServerInfo().getName());
    }

    /// Minimum-viable [LanguageClient] — accepts whatever the server pushes,
    /// answers any `showMessageRequest` with `null`.
    private static final class SilentClient implements LanguageClient {
        @Override public void telemetryEvent(Object object) {}
        @Override public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {}
        @Override public void showMessage(MessageParams params) {}
        @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void logMessage(MessageParams message) {}
    }
}
