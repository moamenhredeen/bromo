package com.almato.bromo.wire;

import com.almato.bromo.lsp.BromoLanguageServer;
import org.eclipse.lsp4j.launch.LSPLauncher;

/// v0 stdio bridge — wires an LSP4J server to `System.in` / `System.out`.
///
/// Scheduled for replacement on the R2 trigger by a hand-rolled NIO reader/writer
/// + streaming JSON codec + sealed-type pattern-match dispatcher.
public final class Stdio {
    private Stdio() {}

    /// Starts the server and blocks until the input stream closes.
    public static void run(BromoLanguageServer server) throws Exception {
        var launcher = LSPLauncher.createServerLauncher(server, System.in, System.out);
        server.connect(launcher.getRemoteProxy());
        launcher.startListening().get();
    }
}
