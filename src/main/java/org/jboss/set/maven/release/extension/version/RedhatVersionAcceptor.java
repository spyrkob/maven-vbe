package org.jboss.set.maven.release.extension.version;

public class RedhatVersionAcceptor implements VersionAcceptanceCriteria {

    @Override
    public boolean accept(final String current,final String proposed) {
        return proposed!=null && proposed.contains(".redhat-");
    }

}
