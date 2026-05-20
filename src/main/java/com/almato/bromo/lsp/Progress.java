package com.almato.bromo.lsp;

import java.util.UUID;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressNotification;
import org.eclipse.lsp4j.WorkDoneProgressReport;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;

/// Initialize-time progress + log channel.
///
/// LSP has two distinct mechanisms for "the server is doing something
/// and the editor should say so":
/// - **`window/logMessage`** — always-on append to the editor's LSP log
///   buffer (Neovim's `:LspLog`, VSCode's "Output" panel). Cheap, no
///   client opt-in required.
/// - **`$/progress`** — proper progress bar / status-line indicator, but
///   only when the client advertises `window.workDoneProgress=true` in
///   its capabilities and the server requests a token via
///   `window/workDoneProgress/create`.
///
/// This class fans every event to both channels: progress notifications
/// when the client supports them, log lines unconditionally. Callers
/// don't have to branch on capabilities.
///
/// Thread-safety: every method is a single LSP4J call (asynchronous on
/// the wire); the LSP4J launcher serialises stdout writes. Safe to call
/// from a virtual thread.
public final class Progress {

    private final LanguageClient client;
    private final boolean workDoneSupported;
    private final String token;

    public Progress(LanguageClient client, ClientCapabilities caps) {
        this.client = client;
        this.workDoneSupported = isWorkDoneSupported(caps);
        this.token = this.workDoneSupported ? UUID.randomUUID().toString() : null;
    }

    /// Requests a progress token from the client (when supported) and
    /// emits a `WorkDoneProgressBegin`. Always logs [title] regardless of
    /// capability. Blocks on the `createProgress` round-trip — callers
    /// should be on a virtual thread.
    public void begin(String title) {
        if (client == null) return;
        log(title);
        if (!workDoneSupported) return;
        try {
            client.createProgress(new WorkDoneProgressCreateParams(Either.forLeft(token))).get();
        } catch (Exception ignored) {
            // Client rejected the token — fall back to log-only for the rest.
            return;
        }
        var begin = new WorkDoneProgressBegin();
        begin.setTitle(title);
        notify(begin);
    }

    public void report(String message) {
        if (client == null) return;
        log(message);
        if (!workDoneSupported) return;
        var report = new WorkDoneProgressReport();
        report.setMessage(message);
        notify(report);
    }

    public void end(String message) {
        if (client == null) return;
        log(message);
        if (!workDoneSupported) return;
        var end = new WorkDoneProgressEnd();
        end.setMessage(message);
        notify(end);
    }

    public void log(String message) {
        if (client == null) return;
        client.logMessage(new MessageParams(MessageType.Info, "bromo: " + message));
    }

    private void notify(WorkDoneProgressNotification n) {
        client.notifyProgress(new ProgressParams(
                Either.forLeft(token),
                Either.forLeft(n)));
    }

    private static boolean isWorkDoneSupported(ClientCapabilities caps) {
        if (caps == null) return false;
        var window = caps.getWindow();
        if (window == null) return false;
        return Boolean.TRUE.equals(window.getWorkDoneProgress());
    }
}
