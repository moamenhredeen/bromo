package com.almato.bromo.lsp;

import com.almato.bromo.workspace.Workspace;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;

/// LSP4J adapter for workspace-level requests.
///
/// `didChangeWatchedFiles` flips the compile-cache dirty bit so the next
/// `compileWorkspace` re-checks signatures instead of taking the fast path.
/// Full `didChangeConfiguration` handling is M9.
public final class BromoWorkspaceService implements WorkspaceService {

    private final Workspace workspace;

    public BromoWorkspaceService(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        // M9
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        workspace.ecj().ifPresent(ctx -> ctx.markDirty());
    }
}
