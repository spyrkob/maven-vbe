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
package org.jboss.set.maven.release.extension.log;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import org.jboss.set.maven.release.extension.ProjectReportEntry;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * POJO which will be serialize into yaml log
 * @author baranowb
 *
 */
@JsonPropertyOrder({ "parentGroupId", "parentAritifactId", "presentRepositories", "activeRepositories", "moduleArtifactUpates"})
public class ReportFile {

    //parent == main project
    private Supplier<String> parentGroupId;
    private Supplier<String> parentAritifactId;
    private List<String> presentRepositories;
    private List<String> activeRepositories;
    private Supplier<Collection<ProjectReportEntry>> moduleArtifactUpates;
    //TODO: channels?

    public ReportFile(Supplier<String> parentGroupId, Supplier<String> parentAritifactId, List<String> presentRepositories,
            List<String> activeRepositories, Supplier<Collection<ProjectReportEntry>> s) {
        super();
        this.parentGroupId = parentGroupId;
        this.parentAritifactId = parentAritifactId;
        this.presentRepositories = presentRepositories;
        this.activeRepositories = activeRepositories;
        this.moduleArtifactUpates = s;
    }

    public String getParentGroupId() {
        return parentGroupId.get();
    }

    public String getParentAritifactId() {
        return parentAritifactId.get();
    }

    public List<String> getPresentRepositories() {
        return presentRepositories;
    }

    public List<String> getActiveRepositories() {
        return activeRepositories;
    }

    public Collection<ProjectReportEntry> getModuleArtifactUpates() {
        return moduleArtifactUpates.get();
    }
}
