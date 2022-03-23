package org.jboss.set.maven.release.extension.version;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.aether.resolution.MetadataResult;

/**
 * Class to hold everything
 * 
 * @author baranowb
 *
 */
public class VBEVersion {

    private final String repositoryUrl;
    private final String version;
    private final String artifactId;
    private final String groupId;
    private String oldVersion;
    private List<VBEVersion> violations = new ArrayList<>();
    public VBEVersion(MetadataResult metadataResult, String v) {
        this.version = v;
        this.artifactId = metadataResult.getRequest().getMetadata().getArtifactId();
        this.groupId = metadataResult.getRequest().getMetadata().getGroupId();
        this.repositoryUrl = metadataResult.getRequest().getRepository().getUrl();
    }
    
    public static final String generateKey(final VBEVersion entry) {
        return entry.getGroupId() + ":" + entry.getArtifactId();
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public String getVersion() {
        return version;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void markViolation(VBEVersion nextVersion) {
        this.violations.add(nextVersion);
    }

    public boolean hasViolations() {
        return this.violations.size()>0;
    }

    public void setOldVersion(String version2) {
        this.oldVersion = version2;
    }

    public String getOldVersion() {
        return this.oldVersion;
    }

    public Collection<VBEVersion> getViolations() {
        return this.violations;
    }
    // NOTE: URL ?
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((artifactId == null) ? 0 : artifactId.hashCode());
        result = prime * result + ((groupId == null) ? 0 : groupId.hashCode());
        result = prime * result + ((version == null) ? 0 : version.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        VBEVersion other = (VBEVersion) obj;
        if (artifactId == null) {
            if (other.artifactId != null)
                return false;
        } else if (!artifactId.equals(other.artifactId))
            return false;
        if (groupId == null) {
            if (other.groupId != null)
                return false;
        } else if (!groupId.equals(other.groupId))
            return false;
        if (version == null) {
            if (other.version != null)
                return false;
        } else if (!version.equals(other.version))
            return false;
        return true;
    }

}
