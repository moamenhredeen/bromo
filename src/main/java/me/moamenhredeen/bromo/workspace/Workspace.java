package me.moamenhredeen.bromo.workspace;

import me.moamenhredeen.bromo.compiler.EcjContext;
import me.moamenhredeen.bromo.compiler.LibrarySourceProvider;
import me.moamenhredeen.bromo.compiler.SourceResolver;
import me.moamenhredeen.bromo.jdk.JdkProvider;
import me.moamenhredeen.bromo.project.ProjectModel;
import me.moamenhredeen.bromo.project.maven.MavenProjectModel;
import me.moamenhredeen.bromo.project.maven.resolver.MavenResolverProvider;
import me.moamenhredeen.bromo.query.QueryEngine;
import me.moamenhredeen.bromo.symbol.SymbolIndex;
import me.moamenhredeen.bromo.symbol.WorkspaceScanner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Root container for bromo's workspace state.
///
/// Holds the [FileStore], the loaded [ProjectModel] (once `initialize` has
/// resolved one), the [SymbolIndex], the per-workspace [EcjContext], and the
/// [SourceResolver] backing goto-definition into source attachments
/// (JDK `src.zip` today; Maven `-sources.jar` in the next iteration).
public final class Workspace {

    private final FileStore fileStore = new FileStore();
    private final SymbolIndex symbols = new SymbolIndex();
    private volatile ProjectModel projectModel;
    private volatile EcjContext ecj;
    private volatile QueryEngine queries;
    private volatile SourceResolver sources;
    private volatile JdkProvider jdk;
    private volatile LibrarySourceProvider library;

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

    public Optional<QueryEngine> queries() {
        return Optional.ofNullable(queries);
    }

    public Optional<SourceResolver> sources() {
        return Optional.ofNullable(sources);
    }

    /// Load a project model and bring up the compile engine for [root].
    /// Synchronous — callers run this on a virtual thread when they don't
    /// want to block the LSP `initialize` response.
    public void attachToRoot(Path root) throws IOException {
        var provider = new MavenResolverProvider();
        if (!provider.supports(root)) return;
        var model = (MavenProjectModel) provider.load(root);
        setProjectModel(model);
        var ctx = new EcjContext(fileStore, model.sourceRoots(), model.classpathBinaries());
        setEcj(ctx);
        this.queries = new QueryEngine(fileStore, ctx);

        // Source-attachment cache lives under target/ so `mvn clean` wipes it.
        // We do not put it under .git or the workspace root proper — it's
        // generated data the build owns.
        Path cacheRoot = root.resolve("target").resolve("bromo-cache").resolve("sources");
        this.jdk = new JdkProvider(cacheRoot.resolve("jdk"));
        this.library = new LibrarySourceProvider(model.classpath(), cacheRoot.resolve("lib"));
        this.sources = new SourceResolver(jdk, library);

        // Symbol index covers our own sources plus any sibling modules in the
        // same Maven reactor — without this, goto-def into e.g. a sibling
        // `mml-base` type fails when no `-sources.jar` is attached for the
        // dependency. Sibling sources are NOT added to the ECJ source roots;
        // that would force the compiler to drag the whole reactor into every
        // diagnostic pass. ECJ continues to resolve sibling types from their
        // jars on the classpath; the symbol index is only consulted for
        // navigation + javadoc rendering.
        var scanRoots = new ArrayList<Path>(model.sourceRoots());
        List<Path> reactorSiblings = MavenResolverProvider.discoverReactorSiblingSources(root);
        scanRoots.addAll(reactorSiblings);
        new WorkspaceScanner().scanInto(symbols, scanRoots);
    }

    /// Eagerly parses a handful of source files so the first real LSP request
    /// lands on a warm ECJ `LookupEnvironment` instead of paying first-touch
    /// init. Intended to be called from a virtual thread immediately after
    /// [#attachToRoot]. Silently no-ops when no compile engine is attached.
    public int preWarm() {
        var ctx = ecj;
        if (ctx == null) return 0;
        try {
            return ctx.preWarm(PREWARM_FILE_BUDGET);
        } catch (Exception e) {
            return 0;
        }
    }

    /// Small enough to keep `initialize` fast; large enough that ECJ has built
    /// `LookupEnvironment` tables for the common types before the user types.
    private static final int PREWARM_FILE_BUDGET = 4;

    /// Releases held resources (zip filesystem handles, the compile engine).
    /// Idempotent.
    public void close() {
        if (queries != null) queries.close();
        if (jdk != null) jdk.close();
        if (library != null) library.close();
        if (ecj != null) {
            try { ecj.close(); } catch (Exception ignored) {}
        }
    }
}
