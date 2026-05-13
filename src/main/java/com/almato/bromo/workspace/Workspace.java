package com.almato.bromo.workspace;

import com.almato.bromo.compiler.EcjContext;
import com.almato.bromo.project.ProjectModel;
import com.almato.bromo.project.maven.MavenProjectModel;
import com.almato.bromo.project.maven.resolver.MavenResolverProvider;
import com.almato.bromo.symbol.SymbolIndex;
import com.almato.bromo.symbol.WorkspaceScanner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/// Root container for bromo's workspace state.
///
/// Holds the [FileStore], the loaded [ProjectModel] (once `initialize` has
/// resolved one), the [SymbolIndex], and the per-workspace [EcjContext] (the
/// Tier-2 compile engine).
public final class Workspace {

    private final FileStore fileStore = new FileStore();
    private final SymbolIndex symbols = new SymbolIndex();
    private volatile ProjectModel projectModel;
    private volatile EcjContext ecj;

    public FileStore files() {
        return fileStore;
    }

    public SymbolIndex symbols() {
        return symbols;
    }

    public Optional<ProjectModel> projectModel() {
        return Optional.ofNullable(projectModel);
    }

    public void setProjectModel(ProjectModel projectModel) {
        this.projectModel = projectModel;
    }

    public Optional<EcjContext> ecj() {
        return Optional.ofNullable(ecj);
    }

    public void setEcj(EcjContext ecj) {
        this.ecj = ecj;
    }

    /// Load a project model and bring up the compile engine for [root].
    /// Synchronous — callers run this on a virtual thread when they don't
    /// want to block the LSP `initialize` response.
    public void attachToRoot(Path root) throws IOException {
        var provider = new MavenResolverProvider();
        if (!provider.supports(root)) return;
        var model = (MavenProjectModel) provider.load(root);
        setProjectModel(model);
        setEcj(new EcjContext(fileStore, model.sourceRoots(), model.classpath()));
        new WorkspaceScanner().scanInto(symbols, model.sourceRoots());
    }
}
