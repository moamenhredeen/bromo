package com.almato.bromo.lsp;

import com.almato.bromo.workspace.Uris;
import com.almato.bromo.workspace.Workspace;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/// LSP4J facade for bromo.
///
/// Initialize flow:
/// 1. Advertise capabilities: incremental text sync, hover, definition,
///    completion (with `.` as a trigger character for future member-access).
/// 2. Resolve the workspace root from `initialize` params.
/// 3. Spawn a virtual thread that loads the project model + builds the
///    Tier-1 symbol index + brings up the Tier-2 compile engine. The
///    `initialize` response returns immediately so the editor doesn't block.
public final class BromoLanguageServer implements LanguageServer, LanguageClientAware {

    private static final System.Logger LOG = System.getLogger(BromoLanguageServer.class.getName());
    private static final String NAME = "bromo";
    private static final String VERSION = "0.0.1-SNAPSHOT";

    private final Workspace workspace = new Workspace();
    private final BromoTextDocumentService textDocuments = new BromoTextDocumentService(workspace);
    private final BromoWorkspaceService workspaceService = new BromoWorkspaceService(workspace);

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        var capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental);
        capabilities.setHoverProvider(true);
        capabilities.setDefinitionProvider(true);
        capabilities.setCompletionProvider(new CompletionOptions(false, List.of(".")));

        Path root = resolveRoot(params);
        if (root != null) {
            final Path target = root;
            Thread.ofVirtual().name("bromo-init", 1).start(() -> {
                try {
                    workspace.attachToRoot(target);
                    LOG.log(System.Logger.Level.INFO,
                            () -> "workspace attached: " + target
                                    + " (sources="
                                    + workspace.projectModel().map(m -> m.sourceRoots().size()).orElse(0)
                                    + ", classpath="
                                    + workspace.projectModel().map(m -> m.classpath().size()).orElse(0)
                                    + ", symbols=" + workspace.symbols().size() + ")");
                } catch (Exception e) {
                    LOG.log(System.Logger.Level.WARNING,
                            "workspace attach failed for " + target, e);
                }
            });
        }

        return CompletableFuture.completedFuture(
                new InitializeResult(capabilities, new ServerInfo(NAME, VERSION)));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        textDocuments.shutdown();
        workspace.ecj().ifPresent(ecj -> {
            try { ecj.close(); } catch (Exception ignored) {}
        });
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override public TextDocumentService getTextDocumentService() { return textDocuments; }
    @Override public WorkspaceService  getWorkspaceService()      { return workspaceService; }

    @Override
    public void connect(LanguageClient client) {
        textDocuments.setClient(client);
    }

    public Workspace workspace() { return workspace; }

    /// Pick the first usable root from the LSP initialize params.
    private static Path resolveRoot(InitializeParams params) {
        var folders = params.getWorkspaceFolders();
        if (folders != null && !folders.isEmpty()) {
            try { return Uris.toPath(Uris.parse(folders.get(0).getUri())); } catch (Exception ignored) {}
        }
        @SuppressWarnings("deprecation")
        String rootUri = params.getRootUri();
        if (rootUri != null) {
            try { return Uris.toPath(Uris.parse(rootUri)); } catch (Exception ignored) {}
        }
        return null;
    }
}
