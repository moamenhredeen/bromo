package com.almato.bromo.project.maven.resolver;

import com.almato.bromo.project.ClasspathEntry;
import com.almato.bromo.project.ProjectModel;
import com.almato.bromo.project.ProjectModelProvider;
import com.almato.bromo.project.maven.MavenProjectModel;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelBuilder;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
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

    @Override
    public String name() {
        return "maven-resolver";
    }

    @Override
    public ProjectModel load(Path workspaceRoot) throws IOException {
        Path pomFile = workspaceRoot.resolve("pom.xml");
        Model model = buildEffectiveModel(pomFile);

        RepositorySystem system = newRepositorySystem();
        DefaultRepositorySystemSession session = newSession(system);
        List<RemoteRepository> remotes = List.of(centralRepository());

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

    private static Model buildEffectiveModel(Path pomFile) throws IOException {
        DefaultModelBuilder builder = new DefaultModelBuilderFactory().newInstance();
        DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
        request.setPomFile(pomFile.toFile());
        request.setProcessPlugins(false);
        request.setSystemProperties(System.getProperties());
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

    /// Resolves the main-jar classpath. Source attachments (`-sources.jar`)
    /// are intentionally **not** fetched here — they cost an extra round of
    /// resolution per dependency and would inflate cold-load time (the metric
    /// driving the R1 replacement trigger). Goto-definition fetches them
    /// lazily on first use through a separate resolver.
    private static List<ClasspathEntry> resolveClasspath(
            Model model,
            RepositorySystem system,
            DefaultRepositorySystemSession session,
            List<RemoteRepository> remotes) throws IOException {

        CollectRequest collect = new CollectRequest();
        for (org.apache.maven.model.Dependency dep : model.getDependencies()) {
            collect.addDependency(new Dependency(toAether(dep), scopeOf(dep)));
        }
        collect.setRepositories(remotes);

        try {
            var result = system.resolveDependencies(session, new DependencyRequest(collect, null));
            List<ClasspathEntry> classpath = new ArrayList<>();
            for (ArtifactResult ar : result.getArtifactResults()) {
                File f = ar.getArtifact().getFile();
                if (f != null) {
                    classpath.add(ClasspathEntry.of(f.toPath()));
                }
            }
            return List.copyOf(classpath);
        } catch (DependencyResolutionException e) {
            throw new IOException("dependency resolution failed for "
                    + model.getGroupId() + ":" + model.getArtifactId(), e);
        }
    }

    private static org.eclipse.aether.artifact.Artifact toAether(org.apache.maven.model.Dependency dep) {
        String extension = dep.getType() != null ? dep.getType() : "jar";
        String classifier = dep.getClassifier() != null ? dep.getClassifier() : "";
        return new DefaultArtifact(
                dep.getGroupId(),
                dep.getArtifactId(),
                classifier,
                extension,
                dep.getVersion());
    }

    private static String scopeOf(org.apache.maven.model.Dependency dep) {
        return dep.getScope() != null ? dep.getScope() : "compile";
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
