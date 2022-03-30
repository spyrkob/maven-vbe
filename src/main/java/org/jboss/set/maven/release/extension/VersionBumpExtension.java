/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2022, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.set.maven.release.extension;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
import org.jboss.set.maven.release.extension.log.ReportFile;
import org.jboss.set.maven.release.extension.version.InsaneVersionComparator;
import org.jboss.set.maven.release.extension.version.RedHatVersionAcceptor;
import org.jboss.set.maven.release.extension.version.VBEVersionComparator;
import org.jboss.set.maven.release.extension.version.VersionAcceptanceCriteria;
import org.slf4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

@Component(role = AbstractMavenLifecycleParticipant.class, hint = "mailman")
public class VersionBumpExtension extends AbstractMavenLifecycleParticipant {
    //comma separated list of repo names, as defined in pom( I think )
    String VBE_REPOSITORY_NAMES = "vbe.repository.names";
    String VBE_LOG_FILE = "vbe.log.file";
    private static final String DEFAULT_LOG_FILE = "vbe.update.yaml";
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
    private VersionAcceptanceCriteria versionTester = new RedHatVersionAcceptor();
    private Map<String,ProjectReportEntry> reportMaterial = new TreeMap<>(Comparator.comparing(String::toString));
    @Override
    public void afterSessionStart(MavenSession session) throws MavenExecutionException {
        super.afterSessionStart(session);
        // TODO: for now nothing
    }

    @Override
    public void afterProjectsRead(final MavenSession session) throws MavenExecutionException {
        logger.info("\n\n========== Red Hat Channel Version Extension[VBE] Starting ==========\n");
        //NOTE: this will work only for project defined deps, if something is in parent, it ~cant be changed.
        if (session == null) {
            return;
        }

        this.session = session;
        long ts = System.currentTimeMillis();
        configureProperties(session);

        if (!shouldSkip(session)) {
          //NOTE: iterate over artifacts/deps and find most recent version available in repos
            //TODO: inject channels into this, as now it will just consume whole repo, its fine as long as it is sanitazed
            configure(session);

            //NOTE: to handle ALL modules. Those are different "projects"
            for(MavenProject mavenProject:session.getAllProjects()) {
                //TODO: debug and check if this will cover dep/parent projects
                //TODO: determine if we need to update every project?
                logger.info("[VBE][PROCESSING]   Project {}:{}", mavenProject.getGroupId(), mavenProject.getArtifactId());
                if (mavenProject.getDependencyManagement() != null) {
                    processProject(mavenProject);
                }

                logger.info("[VBE][FINISHED]   Project {}:{}", mavenProject.getGroupId(), mavenProject.getArtifactId());
            }
            report();
        }

        logger.info("\n\n========== Red Hat Channel Version Extension Finished in " + (System.currentTimeMillis() - ts) + "ms ==========\n");
    }

    private void report() {
        logger.info("[VBE][REPORT] Artifact report for main project {}:{}", session.getCurrentProject().getGroupId(), session.getCurrentProject().getArtifactId());
        this.reportMaterial.values().stream().forEach(v->{v.report();});
        logger.info("[VBE][REPORT] ======================================================");

        //now print log yaml log file if need be
        final Supplier<Collection<ProjectReportEntry>> s= () -> {return reportMaterial.values();};
        final Supplier<String> groupIdSupplier = () -> {return session.getCurrentProject().getGroupId();};
        final Supplier<String> artifactIdSupplier = () -> {return session.getCurrentProject().getArtifactId();};
        final List<String> presentRepositories = session.getCurrentProject().getRepositories().stream().map(mr -> { return mr.getUrl(); }).collect(Collectors.toList());
        final List<String> activeRepositories = repositories.stream().map(rr -> { return rr.getUrl(); }).collect(Collectors.toList());

        final ReportFile reportFile = new ReportFile(groupIdSupplier, artifactIdSupplier, presentRepositories, activeRepositories, s);

        String logFile = System.getProperty(VBE_LOG_FILE);
        logFile = logFile != null?logFile:session.getExecutionRootDirectory()+File.separator+DEFAULT_LOG_FILE;
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.findAndRegisterModules();
        try {
             mapper.writeValue(new File(logFile), reportFile);
        } catch(Exception e) {
            logger.error("[VBE] {}:{}, failed to write log file '{}' {}", session.getCurrentProject().getGroupId(),
                   logFile, e);
        }
        
    }

    private void processProject(final MavenProject mavenProject) {
        // NOTE: two step, project has deps and dep management, both need to be tuned.
        // not ideal, because it will resolve artifacts twice
        if (mavenProject.getDependencyManagement() != null) {
            final DependencyManagement dependencyManagement = mavenProject.getDependencyManagement();
            for (Dependency dependency : dependencyManagement.getDependencies()) {
                updateDependency(mavenProject, dependency, true, (org.eclipse.aether.artifact.Artifact a) -> {
                    mavenProject.getManagedVersionMap().get(dependency.getManagementKey()).setResolvedVersion(a.getVersion());
                    mavenProject.getManagedVersionMap().get(dependency.getManagementKey()).setVersion(a.getVersion());
                    dependency.setVersion(a.getVersion());
                }, versionTester);
            }
        }

        for (Dependency dependency : mavenProject.getDependencies()) {
            updateDependency(mavenProject, dependency, false, (org.eclipse.aether.artifact.Artifact a) -> {
                dependency.setVersion(a.getVersion());
            }, versionTester);
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
            final Consumer<org.eclipse.aether.artifact.Artifact> mavenProjectVersionUpdater, final VersionAcceptanceCriteria tester) {
        if (!shouldProcess(dependency)) {
            logger.info("[VBE] {}:{}, skipping dependency{} {}:{}", mavenProject.getGroupId(), mavenProject.getArtifactId(),
                    managed?"(M)":"",dependency.getGroupId(), dependency.getArtifactId());
        } else {
            resolveDependencyVersionUpdate(dependency, nextVersion ->{
                // fetch artifact
                if (nextVersion == null || nextVersion.getVersion().equals(dependency.getVersion())) {
                    return;
                }
                ArtifactRequest request = new ArtifactRequest();
                DefaultArtifact requestedArtifact = new DefaultArtifact(String.format("%s:%s:%s:%s", dependency.getGroupId(),
                        dependency.getArtifactId(), dependency.getType(), nextVersion.getVersion()));
                request.setArtifact(requestedArtifact);
                request.setRepositories(Collections.singletonList(nextVersion.getRepository()));
                ArtifactResult result;
                try {
                    result = repo.resolveArtifact(session.getRepositorySession(), request);
                } catch (ArtifactResolutionException e) {
                    logger.info("[VBE] {}:{}, failed to resolve dependency{} {}:{}:{}", mavenProject.getGroupId(),
                            mavenProject.getArtifactId(), managed ? "(M)" : "", dependency.getGroupId(),
                            dependency.getArtifactId(), nextVersion.getVersion(), e);
                    return;
                }
                if (result.isMissing() || !result.isResolved()) {
                    logger.info("[VBE] {}:{}, failed to resolve dependency{} {}:{}:", mavenProject.getGroupId(),
                            mavenProject.getArtifactId(), managed ? "(M)" : "", dependency.getGroupId(),
                            dependency.getArtifactId(), nextVersion);
                    return;
                }
                logger.info("[VBE] {}:{}, updating dependency{} {}:{}  {}-->{}", mavenProject.getGroupId(),
                        mavenProject.getArtifactId(), managed?"(M)":"", dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(),
                        nextVersion.getVersion());
                //NOTE: from this point on dependency is has changed
                final String tmpVersion = dependency.getVersion();
                mavenProjectVersionUpdater.accept(result.getArtifact());
                nextVersion.setOldVersion(tmpVersion);
                reportVersionChange(mavenProject, nextVersion);
                
            },tester);
            
        }
    }

    private void reportVersionChange(final MavenProject mavenProject, final VBEVersionUpdate nextVersion) {
        final String id = ProjectReportEntry.generateKey(mavenProject);
        if(reportMaterial.containsKey(id)) {
            final ProjectReportEntry entry = reportMaterial.get(id);
            //This will happen when project has maven dep management and dependency declared...
            if(entry.hasEntry(nextVersion)) {
                final VBEVersionUpdate existing = entry.get(nextVersion.generateKey(nextVersion));
                if(!existing.getVersion().equals(nextVersion.getVersion())) {
                    existing.markViolation(nextVersion);
                }
            }else {
                entry.addReportArtifact(nextVersion);
            }
        } else {
            final ProjectReportEntry entry = new ProjectReportEntry(mavenProject, logger);
            entry.addReportArtifact(nextVersion);
            reportMaterial.put(id, entry);
        }
        
    }

    /**
     * Perform metadata look up and determine if there is more recent version. If there is, pass it to consumer.
     * @param dependency
     * @param versionConsumer
     */
    private void resolveDependencyVersionUpdate(final Dependency dependency,final Consumer<VBEVersionUpdate> versionConsumer, final VersionAcceptanceCriteria tester) {
        try {
            // TODO: discriminate major/minor/micro here?
            List<MetadataResult> results = fetchDependencyMetadata(dependency);
            if (results == null || results.size() == 0) {
                logger.info("[VBE] {}:{}, failed to fetch metadata for dependency {}:{}",
                        session.getCurrentProject().getGroupId(), session.getCurrentProject().getArtifactId(),
                        dependency.getGroupId(), dependency.getArtifactId());
                return;
            }

            // remove deps that are not good, sort, pick one.
            results = results.stream().filter(m -> m != null && !m.isMissing() && m.isResolved())
                    .collect(Collectors.toList());
            // TODO: check on release/latest: technically its possible to release previous major/minor?

            // easy to mess with Channel API and below streams
            final List<VBEVersionUpdate> allArtifactVersions = new ArrayList<>();
            for(MetadataResult metadataResult:results) {
                final Metadata m = metadataResult.getMetadata();
                try (FileReader reader = new FileReader(m.getFile())) {
                    final org.apache.maven.artifact.repository.metadata.Metadata md = new MetadataXpp3Reader().read(reader);
                    final Versioning v = md.getVersioning();
                    if (v != null) {
                        // yyyymmddHHMMSS --> v.getLastUpdated()
                        final List<VBEVersionUpdate> unfoldedChunk = unfoldVersioning(metadataResult,v);
                        allArtifactVersions.addAll(unfoldedChunk);
                    } else {
                        //this should not happen?
                        continue;
                    }
                } catch (IOException | XmlPullParserException e) {
                    logger.info("[VBE] {}:{}, failed to parse metadata {}:{}. {} {}", session.getCurrentProject().getGroupId(),
                            session.getCurrentProject().getArtifactId(), dependency.getGroupId(), dependency.getArtifactId(),
                            m.getFile(), e.getMessage(), e);

                }
            }
            Optional<VBEVersionUpdate> optionalVersion = allArtifactVersions.stream()
              .filter(vbe-> {return tester.accept(dependency.getVersion(), vbe.getVersion());})
              .collect(Collectors.maxBy(VBEVersionComparator.INSTANCE));

            if(!optionalVersion.isPresent()) {
                logger.info("[VBE] {}:{}, no suitable update {}:{}", session.getCurrentProject().getGroupId(),
                        session.getCurrentProject().getArtifactId(), dependency.getGroupId(), dependency.getArtifactId());
                return;
            }
            final VBEVersionUpdate possibleUpdate = optionalVersion.get();
            // first should be most senior one.
            if (possibleUpdate != null) {
                if (InsaneVersionComparator.INSTANCE.compare(possibleUpdate.getVersion(), dependency.getVersion()) > 0) {
                    logger.info("[VBE] {}:{}, possible update for dependency {}:{} {}->{}",
                            session.getCurrentProject().getGroupId(), session.getCurrentProject().getArtifactId(),
                            dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(), possibleUpdate.getVersion());
                    versionConsumer.accept(possibleUpdate);
                    return;
                } else {
                    logger.info("[VBE] {}:{}, no viable version found for update {}:{} {}<->{}",
                            session.getCurrentProject().getGroupId(), session.getCurrentProject().getArtifactId(),
                            dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(), possibleUpdate.getVersion());
                    return;
                }
            } else {
                logger.info("[VBE] {}:{}, no possible update for dependency {}:{}", session.getCurrentProject().getGroupId(),
                        session.getCurrentProject().getArtifactId(), dependency.getGroupId(), dependency.getArtifactId());
                return;
            }
        } catch (Exception e) {
            logger.error("[VBE] {}:{}, failed to fetch info for {}:{} -> {}", session.getCurrentProject().getGroupId(),
                    session.getCurrentProject().getArtifactId(), dependency.getGroupId(), dependency.getArtifactId(), e);
            return;
        }
    }

    private List<VBEVersionUpdate> unfoldVersioning(final MetadataResult metadataResult, final Versioning versioning){
        final List<VBEVersionUpdate> list = new ArrayList<>();
        for(String v:versioning.getVersions()) {
            list.add(new VBEVersionUpdate(metadataResult, v));
        }
        return list;
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

    private void configure(final MavenSession session) throws MavenExecutionException {
        this.repositories = configureRepositories(session);
        final ServiceLoader<VersionAcceptanceCriteria> versionAcceptanceServices = ServiceLoader.load(VersionAcceptanceCriteria.class);
        final Iterator<VersionAcceptanceCriteria> it = versionAcceptanceServices.iterator();
        if(it.hasNext()) {
            final String property = System.getProperty(VersionAcceptanceCriteria.VBE_VERSION_ACCEPTOR_PROPERTY);
            if(property != null && property.length() > 0) {
                while(it.hasNext()) {
                    final VersionAcceptanceCriteria tmp = it.next();
                    final String className = tmp.getClass().getName(); 
                    if(className.endsWith(property) || className.equals(property)) {
                        this.versionTester = tmp;
                        break;
                    }
                }

                if(this.versionTester == null) {
                    logger.warn("[VBE] {}:{}, no version acceptor, defualting to 'RedhatVersionAcceptor'", session.getCurrentProject().getGroupId());
                    this.versionTester = new RedHatVersionAcceptor();
                }
            } else {
                //first is RHT
                this.versionTester = it.next();
            }
        } else {
            logger.warn("[VBE] {}:{}, no version acceptor, defualting to 'RedhatVersionAcceptor'", session.getCurrentProject().getGroupId());
            this.versionTester = new RedHatVersionAcceptor();
        }
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
        final String repositoryNames = System.getProperty(VBE_REPOSITORY_NAMES);
        final Set<String> names = new HashSet<>();
        logger.info("[VBE] Repository names used for updates:");
        if(repositoryNames !=null && repositoryNames.length() > 0) {
            for(String s:repositoryNames.split(",")) {
                logger.info("  - {}",s);
                names.add(s.trim());
            }
        }
        
        final List<RemoteRepository> repositories = new ArrayList<>();

        logger.info("[VBE] Repositories present in reactor:");
        for (org.apache.maven.model.Repository repo : session.getCurrentProject().getRepositories()) {
            final String id = repo.getId() == null ? UUID.randomUUID().toString() : repo.getId();
            logger.info("  - {}: {}", id, repo.getUrl());
            if (names.size() == 0 || names.contains(id)) {
                final RemoteRepository.Builder builder = new RemoteRepository.Builder(id, repo.getLayout(), repo.getUrl());
                repositories.add(builder.build());
            }
        }
        logger.info("[VBE] Reading metadata and artifacts from {} project {}", repositories.size(),
                repositories.size() > 1 ? "repositories" : "repository:");
        for (RemoteRepository r : repositories) {
            logger.info("  - {}: {}", r.getId(), r.getUrl());
        }

        return repositories;
    }

}
