package org.jboss.set.maven.release.extension.version;

import java.util.Comparator;

public class VBEVersionComparator implements Comparator<VBEVersion> {
    public static final Comparator<VBEVersion> INSTANCE = new VBEVersionComparator();

    private VBEVersionComparator() {
    }

    // TODO: switch to
    // https://github.com/jmesnil/wildfly-channel/blob/main/core/src/main/java/org/wildfly/channel/version/VersionMatcher.java
    // once we get to channels.
    @Override
    public int compare(VBEVersion v1, VBEVersion v2) {
     // TODO: throw execption on different types?
        return InsaneVersionComparator.INSTANCE.compare(v1.getVersion(), v2.getVersion());
    }

}
