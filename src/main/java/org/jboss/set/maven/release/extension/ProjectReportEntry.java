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

    public void report(final Logger logger) {
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

    public ProjectReportEntry(final MavenProject mavenProject) {
        super();
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