package com.almato.bromo.lsp;

import com.almato.bromo.workspace.Workspace;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;

/// LSP4J adapter for workspace-level requests.
///
/// Method bodies are filled in as milestones land:
/// - M2 — project-model loading on initial open (workspace folders from `initialize`).
/// - M9 — `didChangeConfiguration`, `didChangeWatchedFiles`, file-watch reactions.
public final class BromoWorkspaceService implements WorkspaceService {

    @SuppressWarnings("unused") // wired up as features land
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
        // M9
    }
}
