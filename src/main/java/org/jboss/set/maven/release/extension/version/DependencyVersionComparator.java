package org.jboss.set.maven.release.extension.version;

import java.util.Comparator;

import org.apache.maven.model.Dependency;

public class DependencyVersionComparator implements Comparator<Dependency> {

    public static final Comparator<Dependency> INSTANCE = new DependencyVersionComparator();

    private DependencyVersionComparator() {
    }

    @Override
    public int compare(Dependency v1, Dependency v2) {
        // TODO: throw execption on different types?
        return InsaneVersionComparator.INSTANCE.compare(v1.getVersion(), v2.getVersion());
    }

}
