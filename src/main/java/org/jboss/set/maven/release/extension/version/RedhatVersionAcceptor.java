package org.jboss.set.maven.release.extension.version;

public class RedhatVersionAcceptor implements VersionAcceptanceCriteria {

    @Override
    public boolean accept(String v) {
        return v!=null && v.contains("-redhat-");
    }

}
