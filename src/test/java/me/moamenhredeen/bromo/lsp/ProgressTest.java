package me.moamenhredeen.bromo.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.WindowClientCapabilities;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressReport;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class ProgressTest {

    @Test
    @DisplayName("with work-done capability: begin sends createProgress + Begin and logs")
    void beginWithWorkDone() {
        var client = new RecordingClient();
        var p = new Progress(client, capsWith(true));
        p.begin("loading");

        assertEquals(1, client.creates.size(), "expected one createProgress call");
        assertEquals(1, client.progress.size(), "expected one progress notification");
        var note = client.progress.get(0);
        var begin = assertInstanceOf(WorkDoneProgressBegin.class, note.getValue().getLeft());
        assertEquals("loading", begin.getTitle());
        assertEquals(1, client.logs.size());
        assertTrue(client.logs.get(0).getMessage().contains("loading"));
    }

    @Test
    @DisplayName("report sends a Report notification after a successful begin")
    void reportEmitsReport() {
        var client = new RecordingClient();
        var p = new Progress(client, capsWith(true));
        p.begin("loading");
        p.report("resolving deps");

        assertEquals(2, client.progress.size());
        var report = assertInstanceOf(WorkDoneProgressReport.class,
                client.progress.get(1).getValue().getLeft());
        assertEquals("resolving deps", report.getMessage());
    }

    @Test
    @DisplayName("end sends an End notification and a log line")
    void endEmitsEnd() {
        var client = new RecordingClient();
        var p = new Progress(client, capsWith(true));
        p.begin("loading");
        p.end("ready");

        var last = client.progress.get(client.progress.size() - 1);
        var end = assertInstanceOf(WorkDoneProgressEnd.class, last.getValue().getLeft());
        assertEquals("ready", end.getMessage());
    }

    @Test
    @DisplayName("without work-done capability: only logMessage fires")
    void fallbackToLogMessageOnly() {
        var client = new RecordingClient();
        var p = new Progress(client, capsWith(false));
        p.begin("loading");
        p.report("step");
        p.end("done");

        assertEquals(0, client.creates.size(), "no createProgress when capability is off");
        assertEquals(0, client.progress.size(), "no progress notifications when capability is off");
        assertEquals(3, client.logs.size());
    }

    @Test
    @DisplayName("null client is tolerated — every method is a no-op")
    void nullClientNoOps() {
        var p = new Progress(null, capsWith(true));
        p.begin("x"); p.report("y"); p.end("z"); p.log("w");
    }

    @Test
    @DisplayName("null capabilities default to log-only")
    void nullCapabilitiesLogOnly() {
        var client = new RecordingClient();
        var p = new Progress(client, null);
        p.begin("loading");
        assertEquals(1, client.logs.size());
        assertEquals(0, client.progress.size());
    }

    private static ClientCapabilities capsWith(boolean workDone) {
        var caps = new ClientCapabilities();
        var window = new WindowClientCapabilities();
        window.setWorkDoneProgress(workDone);
        caps.setWindow(window);
        return caps;
    }

    private static final class RecordingClient implements LanguageClient {
        final List<MessageParams> logs = new ArrayList<>();
        final List<ProgressParams> progress = new ArrayList<>();
        final List<WorkDoneProgressCreateParams> creates = new ArrayList<>();

        @Override public void telemetryEvent(Object object) {}
        @Override public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {}
        @Override public void showMessage(MessageParams params) {}
        @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void logMessage(MessageParams message) { logs.add(message); }
        @Override public void notifyProgress(ProgressParams params) {
            assertNotNull(params.getToken(), "progress token must be set");
            progress.add(params);
        }
        @Override public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
            creates.add(params);
            return CompletableFuture.completedFuture(null);
        }
    }
}
