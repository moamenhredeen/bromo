package me.moamenhredeen.bromo.project.maven.resolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

/// Minimal [ModelResolver] for `DefaultModelBuilder`.
///
/// Maven's own `DefaultModelResolver` is package-private and unreachable
/// from outside the model-builder module — same reason every embedding of
/// the resolver (m2e, NetBeans, takari) carries a copy of this class.
///
/// Resolves parent POMs and BOM imports (`<dependencyManagement>` with
/// `<scope>import</scope>`) by asking [RepositorySystem] for the
/// corresponding `pom` artifact. The build was failing for projects that
/// inherited from `spring-boot-dependencies` or any other remote parent
/// because the previous code passed no resolver and the model-builder
/// NPE'd as soon as it needed one.
final class BromoModelResolver implements ModelResolver {

    private final RepositorySystemSession session;
    private final RepositorySystem system;
    private final List<RemoteRepository> repositories;
    private final List<RemoteRepository> externalRepositories;
    private final Set<String> repositoryIds;

    BromoModelResolver(RepositorySystemSession session,
                       RepositorySystem system,
                       List<RemoteRepository> repositories) {
        this.session = session;
        this.system = system;
        this.repositories = new ArrayList<>(repositories);
        this.externalRepositories = Collections.unmodifiableList(new ArrayList<>(repositories));
        this.repositoryIds = new HashSet<>();
        for (RemoteRepository r : repositories) repositoryIds.add(r.getId());
    }

    private BromoModelResolver(BromoModelResolver other) {
        this.session = other.session;
        this.system = other.system;
        this.repositories = new ArrayList<>(other.repositories);
        this.externalRepositories = other.externalRepositories;
        this.repositoryIds = new HashSet<>(other.repositoryIds);
    }

    @Override
    public ModelSource resolveModel(String groupId, String artifactId, String version)
            throws UnresolvableModelException {
        Artifact pom = new DefaultArtifact(groupId, artifactId, "", "pom", version);
        try {
            var result = system.resolveArtifact(session,
                    new ArtifactRequest(pom, repositories, null));
            return new FileModelSource(result.getArtifact().getFile());
        } catch (ArtifactResolutionException e) {
            throw new UnresolvableModelException(
                    e.getMessage(), groupId, artifactId, version, e);
        }
    }

    @Override
    public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
        return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
    }

    @Override
    public ModelSource resolveModel(Dependency dependency) throws UnresolvableModelException {
        return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
    }

    @Override
    public void addRepository(Repository repository) throws InvalidRepositoryException {
        addRepository(repository, false);
    }

    @Override
    public void addRepository(Repository repository, boolean replace) throws InvalidRepositoryException {
        if (!replace && !repositoryIds.add(repository.getId())) return;
        repositories.add(toAether(repository));
    }

    @Override
    public ModelResolver newCopy() {
        return new BromoModelResolver(this);
    }

    private static RemoteRepository toAether(Repository r) {
        return new RemoteRepository.Builder(r.getId(), r.getLayout(), r.getUrl()).build();
    }
}
