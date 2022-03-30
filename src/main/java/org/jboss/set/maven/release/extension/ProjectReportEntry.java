package org.jboss.set.maven.release.extension;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

import org.apache.maven.project.MavenProject;

import org.slf4j.Logger;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({ "moduleGroupId", "moduleArtifactId", "artifactUpdates" })
public class ProjectReportEntry {
    private final Comparator<String> comparator = Comparator.comparing(String::toString);
    private final Map<String, VBEVersionUpdate> reportMaterial = new TreeMap<>(comparator);
    //GAV - per module gav
    private final String moduleGroupId;
    private final String moduleArtifactId;
    private final Logger logger;

    public void report() {
        //TODO: add file output
        logger.info("[VBE][REPORT]     project {}:{}", getModuleGroupId(),getModuleArtifactId());
        this.reportMaterial.values().stream().forEach(v->{
            logger.info("[VBE]{}                {}:{}  {}->{}  from {}",v.hasViolations()?"V":" ", v.getGroupId(),v.getArtifactId(), v.getOldVersion(), v.getVersion(), v.getRepositoryUrl());
            if(v.hasViolations()) {
                v.getViolations().stream().forEach(vv->{
                    logger.info("[VBE]Violation                  {}",vv.getVersion());
                });
            }
        });
        
    }

    public VBEVersionUpdate get(String id) {
        return this.reportMaterial.get(id);
    }

    public boolean hasEntry(VBEVersionUpdate nextVersion) {
        return this.reportMaterial.containsKey(VBEVersionUpdate.generateKey(nextVersion));
    }

    public void addReportArtifact(VBEVersionUpdate nextVersion) {
        this.reportMaterial.put(nextVersion.generateKey(nextVersion), nextVersion);
    }

    public ProjectReportEntry(final MavenProject mavenProject, final Logger logger) {
        super();
        this.logger = logger;
        this.moduleGroupId = mavenProject.getGroupId();
        this.moduleArtifactId = mavenProject.getArtifactId();
    }

    public String getModuleGroupId() {
        return moduleGroupId;
    }

    public String getModuleArtifactId() {
        return moduleArtifactId;
    }

    public Collection<VBEVersionUpdate> getArtifactUpdates(){
        //TODO: check if linked list wont be better?
        return this.reportMaterial.values();
    }

    public static final String generateKey(final ProjectReportEntry entry) {
        return entry.getModuleGroupId() + ":" + entry.getModuleArtifactId();
    }

    public static final String generateKey(final MavenProject entry) {
        return entry.getGroupId() + ":" + entry.getArtifactId();
    }

    /*
    @Override
    public int compareTo(ProjectReportEntry o) {
        if (o == null) {
            return 1;
        } else {
            return comparator.compare(generateKey(this), generateKey(o));
        }
    }
    */
}