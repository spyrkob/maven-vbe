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

import static java.util.Collections.emptySet;
import static java.util.Objects.requireNonNull;
import static org.wildfly.channel.version.VersionMatcher.COMPARATOR;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
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
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.version.Version;
import org.jboss.set.maven.release.extension.log.ReportFile;
import org.jboss.set.maven.release.extension.version.InsaneVersionComparator;
import org.slf4j.Logger;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channel.ChannelSession;
import org.wildfly.channel.MavenArtifact;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.channel.spi.MavenVersionsResolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

@Component(role = AbstractMavenLifecycleParticipant.class, hint = "mailman")
public class VersionBumpExtension extends AbstractMavenLifecycleParticipant {
    // comma separated list of repo names, as defined in pom( I think )
    final String VBE_REPOSITORY_NAMES = "vbe.repository.names";
    final String VBE_LOG_FILE = "vbe.log.file";
    final String VBE_CHANNELS = "vbe.channels";

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
    // yeah, not a best practice
    private MavenSession session;
    private List<RemoteRepository> repositories;
    private ChannelSession channelSession;
    // Report part
    private Map<String, ProjectReportEntry> reportMaterial = new TreeMap<>(Comparator.comparing(String::toString));

    @Override
    public void afterSessionStart(MavenSession session) throws MavenExecutionException {
        super.afterSessionStart(session);
        // TODO: for now nothing
    }

    @Override
    public void afterProjectsRead(final MavenSession session) throws MavenExecutionException {
        logger.info("\n\n========== Red Hat Channel Version Extension[VBE] Starting ==========\n");
        // NOTE: this will work only for project defined deps, if something is in parent, it ~cant be changed.
        if (session == null) {
            return;
        }

        this.session = session;
        long ts = System.currentTimeMillis();

        if (!shouldSkip(session)) {
            // NOTE: iterate over artifacts/deps and find most recent version available in repos
            // TODO: inject channels into this, as now it will just consume whole repo, its fine as long as it is sanitazed
            configure();

            // NOTE: to handle ALL modules. Those are different "projects"
            for (MavenProject mavenProject : session.getAllProjects()) {
                // TODO: debug and check if this will cover dep/parent projects
                // TODO: determine if we need to update every project?
                logger.info("[VBE][PROCESSING]   Project {}:{}", mavenProject.getGroupId(), mavenProject.getArtifactId());
                if (mavenProject.getDependencyManagement() != null) {
                    processProject(mavenProject);
                }

                logger.info("[VBE][FINISHED]   Project {}:{}", mavenProject.getGroupId(), mavenProject.getArtifactId());
            }
            report();
        }

        logger.info("\n\n========== Red Hat Channel Version Extension Finished in " + (System.currentTimeMillis() - ts)
                + "ms ==========\n");
    }

    private void report() {
        logger.info("[VBE][REPORT] Artifact report for main project {}:{}", session.getCurrentProject().getGroupId(),
                session.getCurrentProject().getArtifactId());
        this.reportMaterial.values().stream().forEach(v -> {
            v.report(logger);
        });
        logger.info("[VBE][REPORT] ======================================================");

        // now print log yaml log file if need be
        final Supplier<Collection<ProjectReportEntry>> s = () -> {
            return reportMaterial.values();
        };
        final Supplier<String> groupIdSupplier = () -> {
            return session.getCurrentProject().getGroupId();
        };
        final Supplier<String> artifactIdSupplier = () -> {
            return session.getCurrentProject().getArtifactId();
        };
        final List<String> presentRepositories = session.getCurrentProject().getRepositories().stream().map(mr -> {
            return mr.getUrl();
        }).collect(Collectors.toList());
        final List<String> activeRepositories = repositories.stream().map(rr -> {
            return rr.getUrl();
        }).collect(Collectors.toList());

        final ReportFile reportFile = new ReportFile(groupIdSupplier, artifactIdSupplier, presentRepositories,
                activeRepositories, s);

        String logFile = System.getProperty(VBE_LOG_FILE);
        logFile = logFile != null ? logFile : session.getExecutionRootDirectory() + File.separator + DEFAULT_LOG_FILE;
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.findAndRegisterModules();
        try {
            mapper.writeValue(new File(logFile), reportFile);
        } catch (Exception e) {
            logger.error("[VBE] {}:{}, failed to write log file '{}' {}", session.getCurrentProject().getGroupId(), logFile, e);
        }

    }

    private void processProject(final MavenProject mavenProject) {
        // NOTE: two step, project has deps and dep management, both need to be tuned.
        // not ideal, because it will resolve artifacts twice
        if (mavenProject.getDependencyManagement() != null) {
            final DependencyManagement dependencyManagement = mavenProject.getDependencyManagement();
            for (Dependency dependency : dependencyManagement.getDependencies()) {
                updateDependency(mavenProject, dependency, true, (MavenArtifact a) -> {
                    mavenProject.getManagedVersionMap().get(dependency.getManagementKey()).setResolvedVersion(a.getVersion());
                    mavenProject.getManagedVersionMap().get(dependency.getManagementKey()).setVersion(a.getVersion());
                    dependency.setVersion(a.getVersion());
                });
            }
        }

        for (Dependency dependency : mavenProject.getDependencies()) {
            updateDependency(mavenProject, dependency, false, (MavenArtifact a) -> {
                dependency.setVersion(a.getVersion());
            });
        }
    }

    /**
     * Update dependency if possible and/or desired.
     * 
     * @param mavenProject
     * @param dependency
     * @param managed
     * @param mavenProjectVersionUpdater - artifact consumer which will perform maven model/pojo updates
     */
    private void updateDependency(final MavenProject mavenProject, final Dependency dependency, final boolean managed,
            final Consumer<MavenArtifact> mavenProjectVersionUpdater) {
        resolveDependencyVersionUpdate(mavenProject,dependency, nextVersion -> {
            // NOTE: since default session does not contain repositories that are in channels we have to fetch EVERYTHING
            if (nextVersion == null || nextVersion.getVersion().equals(dependency.getVersion())) {
                return;
            }

            MavenArtifact result;
            try {
                result = this.channelSession.resolveDirectMavenArtifact(dependency.getGroupId(), dependency.getArtifactId(),dependency.getType(), null, nextVersion.getVersion());
            } catch (UnresolvedMavenArtifactException e) {
                if(logger.isDebugEnabled()) {
                    logger.info("[VBE] {}:{}, failed to resolve dependency{} {}:{}:{}", mavenProject.getGroupId(),
                            mavenProject.getArtifactId(), managed ? "(M)" : "", dependency.getGroupId(), dependency.getArtifactId(),
                            nextVersion.getVersion(), e);
                } else {
                    logger.info("[VBE] {}:{}, failed to resolve dependency{} {}:{}", mavenProject.getGroupId(),
                            mavenProject.getArtifactId(), managed ? "(M)" : "", dependency.getGroupId(), dependency.getArtifactId(),
                            nextVersion.getVersion());
                }
                
                return;
            }

            logger.info("[VBE] {}:{}, updating dependency{} {}:{}  {}-->{}", mavenProject.getGroupId(),
                    mavenProject.getArtifactId(), managed ? "(M)" : "", dependency.getGroupId(), dependency.getArtifactId(),
                    dependency.getVersion(), nextVersion.getVersion());
            // NOTE: from this point on dependency is has changed
            final String tmpVersion = dependency.getVersion();
            mavenProjectVersionUpdater.accept(result);
            nextVersion.setOldVersion(tmpVersion);
            reportVersionChange(mavenProject, nextVersion);

        });
    }

    private void reportVersionChange(final MavenProject mavenProject, final VBEVersionUpdate nextVersion) {
        final String id = ProjectReportEntry.generateKey(mavenProject);
        if (reportMaterial.containsKey(id)) {
            final ProjectReportEntry entry = reportMaterial.get(id);
            // This will happen when project has maven dep management and dependency declared...
            if (entry.hasEntry(nextVersion)) {
                final VBEVersionUpdate existing = entry.get(nextVersion.generateKey(nextVersion));
                if (!existing.getVersion().equals(nextVersion.getVersion())) {
                    existing.markViolation(nextVersion);
                }
            } else {
                entry.addReportArtifact(nextVersion);
            }
        } else {
            final ProjectReportEntry entry = new ProjectReportEntry(mavenProject);
            entry.addReportArtifact(nextVersion);
            reportMaterial.put(id, entry);
        }

    }

    /**
     * Perform metadata look up and determine if there is more recent version. If there is, pass it to consumer.
     * 
     * @param dependency
     * @param versionConsumer
     */
    private void resolveDependencyVersionUpdate(final MavenProject mavenProject, final Dependency dependency, final Consumer<VBEVersionUpdate> versionConsumer) {
        try {
            // TODO: discriminate major/minor/micro here?
            final String possibleVersionUpdate = this.channelSession.findLatestMavenArtifactVersion(dependency.getGroupId(),
                    dependency.getArtifactId(), null, null);
            final VBEVersionUpdate possibleUpdate = new VBEVersionUpdate(dependency.getGroupId(), dependency.getArtifactId(),
                    possibleVersionUpdate, dependency.getType());
            possibleUpdate.setOldVersion(dependency.getVersion());

            //messy hack for now
            final Pattern pattern = Pattern.compile("\\d*(\\.\\d+)*");
            final Matcher newVersionMatcher = pattern.matcher(possibleUpdate.getVersion());
            final Matcher oldVersionMatcher = pattern.matcher(possibleUpdate.getOldVersion());
            if(!(newVersionMatcher.find() && oldVersionMatcher.find())) {
                logger.info("[VBE] {}:{}, no viable version found for update {}:{} {}<->{}",
                        mavenProject.getGroupId(), mavenProject.getArtifactId(),
                        dependency.getGroupId(), dependency.getArtifactId(), possibleUpdate.getOldVersion(),
                        possibleUpdate.getVersion());
                return;
            }
            final String[] newVersionSplit = newVersionMatcher.group().split("\\.");
            final String[] oldVersionSplit = oldVersionMatcher.group().split("\\.");
            //https://docs.oracle.com/middleware/1212/core/MAVEN/maven_version.htm#MAVEN8903
            if(newVersionSplit.length > oldVersionSplit.length) {
                //looks like new one went far ahead
                rangeLookup(possibleUpdate, oldVersionSplit);
            } else if(newVersionSplit.length == oldVersionSplit.length){
                if(prefixMatch(newVersionSplit, oldVersionSplit)) {
                    //Do nothing, new version should be fine
                } else {
                    rangeLookup(possibleUpdate, oldVersionSplit);
                }
            } else {
                //This technically should not happen? Lets just ignore
                possibleUpdate.setVersion(possibleUpdate.getOldVersion());
            }

            if (InsaneVersionComparator.INSTANCE.compare(possibleUpdate.getVersion(), possibleUpdate.getOldVersion()) > 0) {
                logger.info("[VBE] {}:{}, possible update for dependency {}:{} {}->{}",
                        mavenProject.getGroupId(), mavenProject.getArtifactId(),
                        dependency.getGroupId(), dependency.getArtifactId(), possibleUpdate.getOldVersion(),
                        possibleUpdate.getVersion());
                versionConsumer.accept(possibleUpdate);
                return;
            } else {
                logger.info("[VBE] {}:{}, no viable version found for update {}:{} {}<->{}",
                        mavenProject.getGroupId(), mavenProject.getArtifactId(),
                        dependency.getGroupId(), dependency.getArtifactId(), possibleUpdate.getOldVersion(),
                        possibleUpdate.getVersion());
                return;
            }

        } catch (Exception e) {
            if(logger.isDebugEnabled()) {
                logger.error("[VBE] {}:{}, failed to fetch info for {}:{} -> {}", mavenProject.getGroupId(),
                        mavenProject.getArtifactId(), dependency.getGroupId(), dependency.getArtifactId(), e);
            } else {
                logger.error("[VBE] {}:{}, failed to fetch info for {}:{}", mavenProject.getGroupId(),
                        mavenProject.getArtifactId(), dependency.getGroupId(), dependency.getArtifactId());
            }
            return;
        }
    }

    private boolean prefixMatch(final String[] v1, final String[] v2) {
        for(int i=0;i<v1.length-1;i++) {
            if(!v1[i].equals(v2[i])) {
                return false;
            }
        }
        return true;
    }
    private String createVersionRangeUpdate(final String[] arr) {
        String[] copiedArray = Arrays.copyOfRange(arr, 0,arr.length-1);
        copiedArray[copiedArray.length-1] = Integer.toString(Integer.parseInt(copiedArray[copiedArray.length-1])+1);
        return String.join(".",copiedArray);
    }

    private void rangeLookup(final VBEVersionUpdate possibleUpdate, final String[] oldVErsionSplit) {
        final String lookUpRange = "["+possibleUpdate.getOldVersion()+","+createVersionRangeUpdate(oldVErsionSplit)+")";
        Artifact artifact = new DefaultArtifact(possibleUpdate.getGroupId(), possibleUpdate.getArtifactId(), null, possibleUpdate.getType(), lookUpRange);
        VersionRangeRequest versionRangeRequest = new VersionRangeRequest();
        versionRangeRequest.setArtifact(artifact);
        versionRangeRequest.setRepositories(repositories);

        try {
            VersionRangeResult versionRangeResult = repo.resolveVersionRange(session.getRepositorySession(),
                    versionRangeRequest);
            Optional<String> thePossibility = versionRangeResult.getVersions().stream().map(Version::toString)
                    .sorted(COMPARATOR.reversed())
                    .findFirst();
            if(thePossibility.isPresent()) {
                possibleUpdate.setVersion(thePossibility.get());
            }
        } catch (VersionRangeResolutionException e) {
            //Do nothing?
            if(logger.isDebugEnabled()) {
                logger.error("[VBE] {}:{}, failed to fetch range for {}:{} -> {}", session.getCurrentProject().getGroupId(),
                        session.getCurrentProject().getArtifactId(), possibleUpdate.getGroupId(), possibleUpdate.getArtifactId(), e);
            }
        }
    }

    private boolean shouldProcess(final Dependency dependency) {
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

    private void configure() throws MavenExecutionException {
        this.configureProperties();
        this.configureRepositories();
        this.configureChannels();
    }

    private void configureProperties() throws MavenExecutionException {
        Properties props = new Properties();
        try (InputStream is = getClass().getResourceAsStream("/plugin.properties")) {
            props.load(is);
        } catch (IOException e) {
            throw new MavenExecutionException("Can't load plugin.properties", session.getCurrentProject().getFile());
        }

        this.pluginGroupId = props.getProperty("plugin.groupId");
        this.pluginArtifactId = props.getProperty("plugin.artifactId");
    }

    private void configureRepositories() throws MavenExecutionException {
        final String repositoryNames = System.getProperty(VBE_REPOSITORY_NAMES);
        final Set<String> names = new HashSet<>();
        logger.info("[VBE] Repository names used for updates:");
        if (repositoryNames != null && repositoryNames.length() > 0) {
            for (String s : repositoryNames.split(",")) {
                logger.info("  - {}", s);
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

        this.repositories = repositories;
    }

    private void configureChannels() {
        final String channelList = System.getProperty(VBE_CHANNELS);
        final List<Channel> channels = new LinkedList<>();
        if (channelList != null && channelList.length() > 0) {

            for (String urlString : Arrays.asList(channelList.split(","))) {
                try {
                    final URL url = new URL(urlString);
                    final Channel c = ChannelMapper.from(url);
                    // TODO: vet repositories?
                    channels.add(c);
                } catch (MalformedURLException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }
        final MavenVersionsResolver.Factory factory = new MavenVersionsResolver.Factory() {
            // TODO: add acceptors?
            @Override
            public MavenVersionsResolver create() {
                return new MavenVersionsResolver() {

                    @Override
                    public Set<String> getAllVersions(String groupId, String artifactId, String extension, String classifier) {
                        requireNonNull(groupId);
                        requireNonNull(artifactId);
                        Artifact artifact = new DefaultArtifact(groupId, artifactId, classifier, extension, "[0,)");
                        VersionRangeRequest versionRangeRequest = new VersionRangeRequest();
                        versionRangeRequest.setArtifact(artifact);
                        versionRangeRequest.setRepositories(repositories);

                        try {
                            VersionRangeResult versionRangeResult = repo.resolveVersionRange(session.getRepositorySession(),
                                    versionRangeRequest);
                            Set<String> versions = versionRangeResult.getVersions().stream().map(Version::toString)
                                    .collect(Collectors.toSet());
                            logger.trace("All versions in the repositories: %s", versions);
                            return versions;
                        } catch (VersionRangeResolutionException e) {
                            return emptySet();
                        }
                    }

                    @Override
                    public File resolveArtifact(String groupId, String artifactId, String extension_aka_type, String classifier,
                            String version) throws UnresolvedMavenArtifactException {
                        Artifact artifact = new DefaultArtifact(groupId, artifactId, classifier, extension_aka_type, version);
                        ArtifactRequest request = new ArtifactRequest();
                        request.setArtifact(artifact);
                        request.setRepositories(repositories);
                        try {
                            ArtifactResult result = repo.resolveArtifact(session.getRepositorySession(), request);
                            return result.getArtifact().getFile();
                        } catch (ArtifactResolutionException e) {
                            throw new UnresolvedMavenArtifactException("Unable to resolve artifact " + artifact, e);
                        }
                    }

                };
            }

        };
        this.channelSession = new ChannelSession(channels, factory);
    }
}
