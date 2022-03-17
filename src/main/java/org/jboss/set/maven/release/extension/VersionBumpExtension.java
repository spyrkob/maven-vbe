package org.jboss.set.maven.release.extension;

import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.metadata.DefaultMetadata;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.MetadataRequest;
import org.eclipse.aether.resolution.MetadataResult;
import org.jboss.set.maven.release.extension.version.InsaneVersionComparator;
import org.slf4j.Logger;

@Component(role = AbstractMavenLifecycleParticipant.class, hint = "mailman")
public class VersionBumpExtension extends AbstractMavenLifecycleParticipant {

    @Requirement
    private Logger logger;

    @Requirement
    private PlexusContainer container;

    @Requirement
    RepositorySystem repo;

    // coordinates of the plugin itself
    private String pluginGroupId;
    private String pluginArtifactId;
    //yeah, not a best practice
    private MavenSession session;
    private List<RemoteRepository> repositories;
    @Override
    public void afterSessionStart(MavenSession session) throws MavenExecutionException {
        super.afterSessionStart(session);
        // TODO: for now nothing
    }

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        //NOTE: this will work only for project defined deps, if something is in parent, it ~cant be changed.
        if (session == null) {
            return;
        }
        this.session = session;
        long ts = System.currentTimeMillis();
        configureProperties(session);

        if (shouldSkip(session)) {
            return;
        }
        
        logger.info("\n\n========== Red Hat Channel Version Extension[VBE] Starting ==========\n");
        //NOTE: iterate over artifacts/deps and find most recent version available in repos
        //TODO: inject channels into this, as now it will just consume whole repo, its fine as long as it is sanitazed
        this.repositories = configureRepositories(session);

        for(MavenProject mavenProject:session.getAllProjects()) {
            //TODO: debug and check if this will cover dep/parent projects
            //TODO: determine if we need to update every project?
            logger.info("[PROCESSING]   Project {}:{}", mavenProject.getGroupId(), mavenProject.getArtifactId());
            if (mavenProject.getDependencyManagement() != null) {
                processProject(mavenProject);
            }
            
            logger.info("[FINISHED]   Project {}:{}", mavenProject.getGroupId(), mavenProject.getArtifactId());
        }
        logger.info("\n\n========== Red Hat Channel Version Extension Finished in " + (System.currentTimeMillis() - ts) + "ms ==========\n");
    }

    private void processProject(final MavenProject mavenProject) {
        // NOTE: two step, project has deps and dep management, both need to be tuned.
        if (mavenProject.getDependencyManagement() != null) {
            final DependencyManagement dependencyManagement = mavenProject.getDependencyManagement();
            for (Dependency dependency : dependencyManagement.getDependencies()) {
                updateDependency(mavenProject, dependency, true, (org.eclipse.aether.artifact.Artifact a) -> {
                    mavenProject.getManagedVersionMap().get(dependency.getManagementKey()).setResolvedVersion(a.getVersion());
                    mavenProject.getManagedVersionMap().get(dependency.getManagementKey()).setVersion(a.getVersion());
                    dependency.setVersion(a.getVersion());
                });
            }
        }

        for (Dependency dependency : mavenProject.getDependencies()) {
            updateDependency(mavenProject, dependency, false, (org.eclipse.aether.artifact.Artifact a) -> {
                dependency.setVersion(a.getVersion());
            });
        }
    }

    /**
     * Update dependency if possible and/or desired.
     * @param mavenProject
     * @param dependency
     * @param managed
     * @param mavenProjectVersionUpdater - artifact consumer which will perform maven model/pojo updates
     */
    private void updateDependency(final MavenProject mavenProject, final Dependency dependency, final boolean managed,
            final Consumer<org.eclipse.aether.artifact.Artifact> mavenProjectVersionUpdater) {
        if (!shouldProcess(dependency)) {
            logger.info("[VBE] {}:{}, skipping dependency{} {}:{}", mavenProject.getGroupId(), mavenProject.getArtifactId(),
                    managed?"(M)":"",dependency.getGroupId(), dependency.getArtifactId());
        } else {
            resolveDependencyVersionUpdate(dependency, nextVersion ->{
                // fetch artifact
                if (nextVersion == null || nextVersion.equals(dependency.getVersion())) {
                    return;
                }
                ArtifactRequest request = new ArtifactRequest();
                DefaultArtifact requestedArtifact = new DefaultArtifact(String.format("%s:%s:%s:%s", dependency.getGroupId(),
                        dependency.getArtifactId(), dependency.getType(), nextVersion));
                request.setArtifact(requestedArtifact);
                ArtifactResult result;
                try {
                    result = repo.resolveArtifact(session.getRepositorySession(), request);
                } catch (ArtifactResolutionException e) {
                    logger.info("[VBE] {}:{}, failed to resolve dependency{} {}:{}:", mavenProject.getGroupId(),
                            mavenProject.getArtifactId(), dependency.getGroupId(), managed?"(M)":"", dependency.getArtifactId(), nextVersion);
                    return;
                }
                if (result.isMissing() || !result.isResolved()) {
                    logger.info("[VBE] {}:{}, failed to resolve dependency{} {}:{}:", mavenProject.getGroupId(),
                            mavenProject.getArtifactId(), dependency.getGroupId(), managed?"(M)":"", dependency.getArtifactId(), nextVersion);
                    return;
                }
                logger.info("[VBE] {}:{}, updating dependency{} {}:{}  {}-->{}", mavenProject.getGroupId(),
                        mavenProject.getArtifactId(), managed?"(M)":"", dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(),
                        nextVersion);
                mavenProjectVersionUpdater.accept(result.getArtifact());
            });
            
        }
    }

    /**
     * Perform metadata look up and determine if there is more recent version. If there is, pass it to consumer.
     * @param dependency
     * @param versionConsumer
     */
    private void resolveDependencyVersionUpdate(final Dependency dependency,final Consumer<String> versionConsumer) {
        try {
            // TODO: discriminate major/minor/micro here?
            final List<MetadataResult> metaDataResults = fetchDependencyMetadata(dependency);
            if (metaDataResults.size() == 0) {
                logger.info("[VBE] {}:{}, failed to fetch metadata for dependency {}:{}",
                        session.getCurrentProject().getGroupId(), session.getCurrentProject().getArtifactId(),
                        dependency.getGroupId(), dependency.getArtifactId());
                return;
            }
            final List<MetadataResult> results = fetchDependencyMetadata(dependency);
            if (results == null || results.size() == 0) {
                logger.info("[VBE] {}:{}, no possible update for dependency {}:{}", session.getCurrentProject().getGroupId(),
                        session.getCurrentProject().getArtifactId(), dependency.getGroupId(), dependency.getArtifactId());
                return;
            }

            // remove deps that are not good, sort, pick one.
            final List<Metadata> intermediate = results.stream().filter(m -> m != null && !m.isMissing() && m.isResolved())
                    .map(s -> s.getMetadata()).collect(Collectors.toList());
            // TODO: check on release/latest: technically its possible to release previous major/minor?
            // This is shady and bad, but will do for now...
//          final List<Versioning> metadataVersioning = new ArrayList<>();
            //
//                    for (Metadata result : intermediate) {
//                        try (FileReader reader = new FileReader(result.getFile())) {
//                            final org.apache.maven.artifact.repository.metadata.Metadata md = new MetadataXpp3Reader().read(reader);
//                            final Versioning v = md.getVersioning();
//                            if (v != null) {
//                                // yyyymmddHHMMSS --> v.getLastUpdated()
//                                metadataVersioning.add(v);
//                            }
//                        } catch (IOException | XmlPullParserException e) {
//                            logger.info("[VBE] {}:{}, failed to parse metadata {}:{}. {} {}",
//                                    session.getCurrentProject().getGroupId(), session.getCurrentProject().getArtifactId(),
//                                    dependency.getGroupId(), dependency.getArtifactId(), result.getFile(), e.getMessage(), e);
//                        }
//                    }
//                    Set<String> versions = new TreeSet<>(InsaneVersionComparator.INSTANCE.reversed());
//                    for(Versioning v:metadataVersioning) {
//                        for(String version:v.getVersions()) {
//                            versions.add(version);
//                        }
//                    }
//                    //first one should be most senior.
            // final String possibleUpdate = versions.iterator().next();
            // //NOTE: just as an exercise: this achieves the same as above, going to leave above for now just in case its not
            // easy to mess with Channel API and below streams
            Optional<String> optionalVersion = intermediate.stream().map(m -> {
                try (FileReader reader = new FileReader(m.getFile())) {
                    final org.apache.maven.artifact.repository.metadata.Metadata md = new MetadataXpp3Reader().read(reader);
                    final Versioning v = md.getVersioning();
                    if (v != null) {
                        // yyyymmddHHMMSS --> v.getLastUpdated()
                        return v;
                    }
                } catch (IOException | XmlPullParserException e) {
                    logger.info("[VBE] {}:{}, failed to parse metadata {}:{}. {} {}", session.getCurrentProject().getGroupId(),
                            session.getCurrentProject().getArtifactId(), dependency.getGroupId(), dependency.getArtifactId(),
                            m.getFile(), e.getMessage(), e);

                }
                return null;
            }).filter(Objects::nonNull).map(v -> {
                return v.getVersions();
            }).flatMap(Collection::stream).collect(Collectors.maxBy(InsaneVersionComparator.INSTANCE));

            final String possibleUpdate = optionalVersion.get();
            // first should be most senior one.
            if (possibleUpdate != null) {
                if (InsaneVersionComparator.INSTANCE.compare(possibleUpdate, dependency.getVersion()) > 0) {
                    // This should not happen, though...?
                    logger.info("[VBE] {}:{}, possible update for dependency {}:{} {}->{}",
                            session.getCurrentProject().getGroupId(), session.getCurrentProject().getArtifactId(),
                            dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(), possibleUpdate);
                    versionConsumer.accept(possibleUpdate);
                    return;
                } else {
                    logger.info("[VBE] {}:{}, no viable version found for update {}:{} {}<->{}",
                            session.getCurrentProject().getGroupId(), session.getCurrentProject().getArtifactId(),
                            dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(), possibleUpdate);
                    return;
                }
            } else {
                logger.info("[VBE] {}:{}, no possible update for dependency {}:{}", session.getCurrentProject().getGroupId(),
                        session.getCurrentProject().getArtifactId(), dependency.getGroupId(), dependency.getArtifactId());
                return;
            }
        } catch (Exception e) {
            logger.info("[VBE] {}:{}, failed to fetch info for {}:{} -> {}", session.getCurrentProject().getGroupId(),
                    session.getCurrentProject().getArtifactId(), dependency.getGroupId(), dependency.getArtifactId(), e);
            return;
        }
    }

    private List<MetadataResult> fetchDependencyMetadata(final Dependency dependency){
        final List<MetadataRequest> requests = new ArrayList<>(repositories.size());
        DefaultMetadata md = new DefaultMetadata(dependency.getGroupId(), dependency.getArtifactId(), "maven-metadata.xml", Metadata.Nature.RELEASE);
        // local repository
        requests.add(new MetadataRequest(md, null, ""));
        // remote repositories
        for (RemoteRepository repo : repositories) {
            requests.add(new MetadataRequest(md, repo, ""));
        }
        return repo.resolveMetadata(session.getRepositorySession(), requests);
    }

    private boolean shouldProcess(final Dependency dependency) {
        //TODO: check if there is Channel defined
        return true;
    }

    private boolean shouldSkip(MavenSession session) {
        // <configuration>/<skip>
        Boolean skip = null;

        for (MavenProject p : session.getProjects()) {
            for (Plugin bp : p.getBuildPlugins()) {
                if ((pluginGroupId + ":" + pluginArtifactId).equals(bp.getKey())) {
                    if (bp.getConfiguration() instanceof Xpp3Dom) {
                        XmlPlexusConfiguration config = new XmlPlexusConfiguration((Xpp3Dom) bp.getConfiguration());
                        if (config.getChild("skip") != null) {
                            skip = "true".equalsIgnoreCase(config.getChild("skip").getValue());
                        }
                    }
                    break;
                }
            }
        }

        if (session.getUserProperties().containsKey("skipVersioning")) {
            if (Boolean.parseBoolean(session.getUserProperties().getProperty("skipVersioning"))) {
                skip = true;
            }
        }

        return skip != null && skip;
    }

    private void configureProperties(MavenSession session) throws MavenExecutionException {
        Properties props = new Properties();
        try (InputStream is = getClass().getResourceAsStream("/plugin.properties")) {
            props.load(is);
        } catch (IOException e) {
            throw new MavenExecutionException("Can't load plugin.properties",
                    session.getCurrentProject().getFile());
        }

        pluginGroupId = props.getProperty("plugin.groupId");
        pluginArtifactId = props.getProperty("plugin.artifactId");

    }

    private List<RemoteRepository> configureRepositories(MavenSession session) throws MavenExecutionException {
        List<RemoteRepository> repositories = new ArrayList<>();
        if (repositories.size() == 0) {
            for (org.apache.maven.model.Repository repo : session.getCurrentProject().getRepositories()) {
                String id = repo.getId() == null ? UUID.randomUUID().toString() : repo.getId();
                RemoteRepository.Builder builder = new RemoteRepository.Builder(id, repo.getLayout(), repo.getUrl());
                repositories.add(builder.build());
            }
            logger.info("[VBE]Reading metadata and artifacts from {} project {}", repositories.size(),
                    repositories.size() > 1 ? "repositories" : "repository");
            for (RemoteRepository r : repositories) {
                logger.info("  - {}: {}", r.getId(), r.getUrl());
            }
        }

        return repositories;
    }
}
