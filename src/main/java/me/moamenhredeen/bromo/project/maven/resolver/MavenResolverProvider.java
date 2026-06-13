package me.moamenhredeen.bromo.project.maven.resolver;

import me.moamenhredeen.bromo.project.ClasspathEntry;
import me.moamenhredeen.bromo.project.ProjectModel;
import me.moamenhredeen.bromo.project.ProjectModelProvider;
import me.moamenhredeen.bromo.project.maven.MavenProjectModel;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelBuilder;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.ArtifactType;
import org.eclipse.aether.artifact.ArtifactTypeRegistry;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;

/// v0 [ProjectModelProvider] backed by Maven Resolver (Aether) + Maven
/// model-builder.
///
/// **Scheduled for replacement on the R1 trigger** (cold load >3s p95). The
/// hand-rolled successor parses `pom.xml` via StAX and walks `~/.m2/repository`
/// directly; this provider becomes a fallback for unsupported POM constructs
/// (profile activations, plugin executions affecting classpath, …).
public final class MavenResolverProvider implements ProjectModelProvider {

    private static final String CENTRAL_URL = "https://repo.maven.apache.org/maven2/";

    @Override
    public boolean supports(Path workspaceRoot) {
        return Files.isRegularFile(workspaceRoot.resolve("pom.xml"));
    }

    /// Walks up from [workspaceRoot] to find a parent `pom.xml` whose
    /// `<modules>` block lists [workspaceRoot] as a reactor module. When
    /// found, returns the `src/main/java` and `src/test/java` directories
    /// of every *other* module in that reactor.
    ///
    /// Used by [me.moamenhredeen.bromo.workspace.Workspace] to populate the
    /// symbol index across the whole multi-module checkout when only one
    /// module is opened in the editor. Without this, goto-def into a
    /// sibling module's type falls through to the binary jar — which
    /// often has no `-sources.jar` attachment (private snapshots, locally
    /// installed jars from `mvn install`) and leaves the user with "no
    /// location found".
    ///
    /// Stays a quick StAX parse — driving the full Maven model builder
    /// for every parent walked would double `attachToRoot` time. We don't
    /// recurse beyond one level: a reactor parent of a reactor parent is
    /// the wrong scope to add as a symbol-index root.
    public static List<Path> discoverReactorSiblingSources(Path workspaceRoot) {
        Path parent = workspaceRoot.toAbsolutePath().normalize().getParent();
        if (parent == null) return List.of();
        Path parentPom = parent.resolve("pom.xml");
        if (!Files.isRegularFile(parentPom)) return List.of();

        List<String> modules = readModules(parentPom);
        String selfName = workspaceRoot.toAbsolutePath().normalize().getFileName().toString();
        if (!modules.contains(selfName)) return List.of();

        var roots = new ArrayList<Path>();
        for (String module : modules) {
            if (module.equals(selfName)) continue;
            Path siblingDir = parent.resolve(module).normalize();
            Path mainSrc = siblingDir.resolve("src/main/java");
            Path testSrc = siblingDir.resolve("src/test/java");
            if (Files.isDirectory(mainSrc)) roots.add(mainSrc);
            if (Files.isDirectory(testSrc)) roots.add(testSrc);
        }
        return List.copyOf(roots);
    }

    /// Quick StAX-only `<module>` extractor. Skips profile-activated modules
    /// (`<profile><modules>`) intentionally — they're rarely needed for the
    /// LSP symbol index and would require profile evaluation we don't do.
    private static List<String> readModules(Path pomFile) {
        var result = new ArrayList<String>();
        try (InputStream in = Files.newInputStream(pomFile)) {
            XMLStreamReader r = XMLInputFactory.newDefaultFactory().createXMLStreamReader(in);
            int depth = 0;
            int profileDepth = -1;
            int modulesDepth = -1;
            while (r.hasNext()) {
                int e = r.next();
                if (e == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    String name = r.getLocalName();
                    if (name.equals("profile")) profileDepth = depth;
                    else if (name.equals("modules") && profileDepth < 0) modulesDepth = depth;
                    else if (name.equals("module") && modulesDepth > 0 && depth == modulesDepth + 1) {
                        String text = r.getElementText().trim();
                        if (!text.isEmpty()) result.add(text);
                        depth--; // getElementText consumed the END_ELEMENT
                    }
                } else if (e == XMLStreamConstants.END_ELEMENT) {
                    if (modulesDepth == depth) modulesDepth = -1;
                    if (profileDepth == depth) profileDepth = -1;
                    depth--;
                }
            }
        } catch (IOException | XMLStreamException ignored) {
            // Best-effort. Returning {} silently degrades to single-module mode.
        }
        return result;
    }

    @Override
    public String name() {
        return "maven-resolver";
    }

    @Override
    public ProjectModel load(Path workspaceRoot) throws IOException {
        Path pomFile = workspaceRoot.resolve("pom.xml");

        RepositorySystem system = newRepositorySystem();
        DefaultRepositorySystemSession session = newSession(system);
        List<RemoteRepository> remotes = List.of(centralRepository());

        Model model = buildEffectiveModel(pomFile, system, session, remotes);

        List<ClasspathEntry> classpath = resolveClasspath(model, system, session, remotes);
        List<Path> sourceRoots = computeSourceRoots(workspaceRoot, model);
        Optional<String> javaRelease = readJavaRelease(model);

        return new MavenProjectModel(
                workspaceRoot.toAbsolutePath().normalize(),
                sourceRoots,
                classpath,
                javaRelease,
                List.copyOf(readCompilerArgs(model)),
                model.getGroupId(),
                model.getArtifactId(),
                model.getVersion());
    }

    // ---- effective POM -----------------------------------------------------

    private static Model buildEffectiveModel(Path pomFile,
                                             RepositorySystem system,
                                             DefaultRepositorySystemSession session,
                                             List<RemoteRepository> remotes) throws IOException {
        return buildEffectiveModel(
                pomFile,
                new DefaultModelBuilderFactory().newInstance(),
                new BromoModelResolver(session, system, remotes));
    }

    private static Model buildEffectiveModel(Path pomFile,
                                             DefaultModelBuilder builder,
                                             org.apache.maven.model.resolution.ModelResolver resolver)
            throws IOException {
        DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
        request.setPomFile(pomFile.toFile());
        request.setProcessPlugins(false);
        request.setSystemProperties(System.getProperties());
        // Without a ModelResolver the builder NPE's the moment it has to
        // walk a remote <parent> POM (e.g. spring-boot-dependencies) or
        // resolve a <dependencyManagement> import. BromoModelResolver
        // fronts the same Aether RepositorySystem we use for the main
        // classpath resolution below.
        request.setModelResolver(resolver.newCopy());
        try {
            ModelBuildingResult result = builder.build(request);
            return result.getEffectiveModel();
        } catch (ModelBuildingException e) {
            throw new IOException("failed to build effective POM from " + pomFile, e);
        }
    }

    private static List<Path> computeSourceRoots(Path root, Model model) {
        List<Path> result = new ArrayList<>(2);
        String mainSrc = model.getBuild() != null && model.getBuild().getSourceDirectory() != null
                ? model.getBuild().getSourceDirectory()
                : "src/main/java";
        String testSrc = model.getBuild() != null && model.getBuild().getTestSourceDirectory() != null
                ? model.getBuild().getTestSourceDirectory()
                : "src/test/java";
        result.add(resolveAgainst(root, mainSrc));
        result.add(resolveAgainst(root, testSrc));
        return List.copyOf(result);
    }

    private static Path resolveAgainst(Path root, String maybeRelative) {
        Path p = Path.of(maybeRelative);
        return (p.isAbsolute() ? p : root.resolve(p)).normalize();
    }

    private static Optional<String> readJavaRelease(Model model) {
        var props = model.getProperties();
        if (props == null) return Optional.empty();
        String r = props.getProperty("maven.compiler.release");
        if (r == null) r = props.getProperty("maven.compiler.source");
        return Optional.ofNullable(r);
    }

    private static List<String> readCompilerArgs(Model model) {
        // For v0 we don't dig into plugin <configuration> blocks — pulls in
        // plugin processing we deliberately disabled. The hand-rolled R1
        // replacement can extract these if benchmarks show they matter.
        return List.of();
    }

    // ---- dependency resolution --------------------------------------------

    /// Resolves the main-jar classpath and attaches any sibling
    /// `-sources.jar` that already exists in the local Maven repository.
    ///
    /// We walk transitives ourselves rather than calling
    /// `system.resolveDependencies(...)`. Aether's `DefaultArtifactDescriptorReader`
    /// silently returns *zero* dependencies for any POM whose dependencies
    /// rely on a parent's `<dependencyManagement>` for versioning — a real
    /// codebase shape (the smoke target's `branch-core` had unversioned
    /// `<dependency>` entries supplied by a `mml-middleware` parent). The
    /// failure was silent: `getExceptions()` was empty, the descriptor
    /// just had no deps, and entire transitive subtrees dropped off the
    /// classpath even though the artifacts and POMs were present in
    /// `~/.m2`.
    ///
    /// Build the effective POM ourselves via [BromoModelResolver] (which
    /// is already known to work — `attachToRoot` uses the same builder
    /// for the root POM) and BFS the dependency graph. Aether is reduced
    /// to a per-artifact file lookup, which is fast and reliable.
    private static List<ClasspathEntry> resolveClasspath(
            Model model,
            RepositorySystem system,
            DefaultRepositorySystemSession session,
            List<RemoteRepository> remotes) throws IOException {

        ArtifactTypeRegistry registry = session.getArtifactTypeRegistry();
        Set<String> seen = new HashSet<>();
        Deque<org.apache.maven.model.Dependency> queue = new ArrayDeque<>();

        // Seed: all direct deps of the root project, every scope. Transitive
        // expansion below is scope-restricted; the root itself can pull in
        // test deps so the LSP works in test code too.
        for (var d : model.getDependencies()) {
            if (seen.add(key(d))) queue.add(d);
        }

        // Reuse one model builder + resolver + per-pom cache for the whole
        // walk. Without this, expanding a 391-dep tree re-builds every
        // parent POM hundreds of times (~70s on the smoke target). The
        // cache is sound because POMs in the local Maven repo are
        // effectively immutable for the duration of one load() call.
        DefaultModelBuilder modelBuilder = new DefaultModelBuilderFactory().newInstance();
        BromoModelResolver modelResolver = new BromoModelResolver(session, system, remotes);
        Map<Path, Model> modelCache = new HashMap<>();

        var classpath = new ArrayList<ClasspathEntry>();
        var logger = System.getLogger(MavenResolverProvider.class.getName());

        while (!queue.isEmpty()) {
            var dep = queue.poll();
            if (isOptional(dep)) continue;

            var aetherArtifact = toAether(dep, registry);

            // Resolve the jar/test-jar file.
            Path binary = resolveFile(system, session, remotes, aetherArtifact);
            if (binary != null) {
                classpath.add(new ClasspathEntry(binary, siblingSourcesJar(binary)));
            } else {
                logger.log(System.Logger.Level.WARNING,
                        () -> "missing artifact: " + aetherArtifact);
            }

            // Expand transitives via our own ModelBuilder, which respects
            // <parent>/<dependencyManagement> properly.
            var pomArtifact = new DefaultArtifact(
                    aetherArtifact.getGroupId(),
                    aetherArtifact.getArtifactId(),
                    "", "pom",
                    aetherArtifact.getVersion());
            Path pomFile = resolveFile(system, session, remotes, pomArtifact);
            if (pomFile == null) continue;

            Model depModel = modelCache.get(pomFile);
            if (depModel == null) {
                try {
                    depModel = buildEffectiveModel(pomFile, modelBuilder, modelResolver);
                } catch (IOException e) {
                    logger.log(System.Logger.Level.WARNING,
                            () -> "failed to expand transitives of " + aetherArtifact + ": " + e.getMessage());
                    continue;
                }
                modelCache.put(pomFile, depModel);
            }
            for (var sub : depModel.getDependencies()) {
                if (!isTransitiveScope(sub.getScope())) continue;
                if (isOptional(sub)) continue;
                if (seen.add(key(sub))) queue.add(sub);
            }
        }
        return List.copyOf(classpath);
    }

    private static Path resolveFile(RepositorySystem system,
                                    DefaultRepositorySystemSession session,
                                    List<RemoteRepository> remotes,
                                    org.eclipse.aether.artifact.Artifact artifact) {
        org.eclipse.aether.artifact.Artifact toResolve = artifact;
        // Maven version ranges (e.g. lsp4j's gson dep "[2.9.1,3.0)") need to
        // be reduced to a concrete version before artifact resolution.
        String v = artifact.getVersion();
        if (v != null && (v.startsWith("[") || v.startsWith("("))) {
            try {
                var rr = system.resolveVersionRange(session,
                        new VersionRangeRequest(artifact, remotes, null));
                if (rr.getHighestVersion() == null) return null;
                toResolve = new DefaultArtifact(
                        artifact.getGroupId(), artifact.getArtifactId(),
                        artifact.getClassifier(), artifact.getExtension(),
                        rr.getHighestVersion().toString());
            } catch (VersionRangeResolutionException e) {
                return null;
            }
        }
        try {
            ArtifactResult result = system.resolveArtifact(session,
                    new ArtifactRequest(toResolve, remotes, null));
            File f = result.getArtifact().getFile();
            return f != null ? f.toPath() : null;
        } catch (ArtifactResolutionException e) {
            return null;
        }
    }

    private static String key(org.apache.maven.model.Dependency d) {
        return d.getGroupId() + ":" + d.getArtifactId() + ":"
                + (d.getClassifier() == null ? "" : d.getClassifier()) + ":"
                + (d.getType() == null ? "jar" : d.getType());
    }

    private static boolean isOptional(org.apache.maven.model.Dependency d) {
        return Boolean.parseBoolean(d.getOptional());
    }

    /// Maven scope rules pruned to what an LSP cares about: when walking
    /// transitives of a *compile*-reachable dep, follow compile / runtime
    /// (provided is intentionally included — needed for IDE-time
    /// resolution of e.g. `javax.servlet-api`). Test scope is workspace-only.
    private static boolean isTransitiveScope(String scope) {
        if (scope == null || scope.isEmpty()) return true; // default = compile
        return switch (scope) {
            case "compile", "runtime", "provided" -> true;
            default -> false;
        };
    }

    private static Optional<Path> siblingSourcesJar(Path binary) {
        String name = binary.getFileName().toString();
        if (!name.endsWith(".jar")) return Optional.empty();
        String sourcesName = name.substring(0, name.length() - ".jar".length()) + "-sources.jar";
        Path sibling = binary.resolveSibling(sourcesName);
        return Files.isRegularFile(sibling) ? Optional.of(sibling) : Optional.empty();
    }

    /// Map a Maven [Dependency] to an Aether [Artifact], respecting Maven's
    /// type → (extension, classifier) registry.
    ///
    /// Maven `<type>` is not a file extension. `test-jar` maps to
    /// `(extension=jar, classifier=tests)`; `javadoc` to `(jar, javadoc)`;
    /// `ejb-client` to `(jar, client)`; etc. The previous version passed the
    /// type string directly as the extension, so any dep using a Maven type
    /// alias would be requested as `groupId:artifactId:test-jar:version` and
    /// fail to resolve — which then cascaded: branch-core couldn't be
    /// traversed past the broken sibling, dropping its entire compile
    /// subtree (including the main `mml-base.jar` that contained
    /// `OrderService`) from the classpath.
    private static org.eclipse.aether.artifact.Artifact toAether(
            org.apache.maven.model.Dependency dep, ArtifactTypeRegistry registry) {
        String typeId = dep.getType() != null ? dep.getType() : "jar";
        ArtifactType type = registry != null ? registry.get(typeId) : null;

        String extension = type != null ? type.getExtension() : typeId;
        String classifier;
        if (dep.getClassifier() != null && !dep.getClassifier().isEmpty()) {
            classifier = dep.getClassifier();
        } else {
            classifier = type != null ? type.getClassifier() : "";
        }

        return new DefaultArtifact(
                dep.getGroupId(),
                dep.getArtifactId(),
                classifier,
                extension,
                dep.getVersion());
    }

    // ---- Aether bootstrap --------------------------------------------------

    private static RepositorySystem newRepositorySystem() {
        @SuppressWarnings("deprecation")
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
        return locator.getService(RepositorySystem.class);
    }

    private static DefaultRepositorySystemSession newSession(RepositorySystem system) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        var local = new LocalRepository(System.getProperty("user.home") + "/.m2/repository");
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, local));
        return session;
    }

    private static RemoteRepository centralRepository() {
        return new RemoteRepository.Builder("central", "default", CENTRAL_URL).build();
    }
}
