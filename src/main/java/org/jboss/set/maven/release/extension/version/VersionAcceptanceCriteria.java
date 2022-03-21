package org.jboss.set.maven.release.extension.version;

public interface VersionAcceptanceCriteria {
    String VBE_VERSION_ACCEPTOR_PROPERTY = "vbe.version.acceptor";
    String VBE_VERSION_ACCEPTOR_CONF_PROPERTY = "vbe.version.acceptor.conf";
    /**
     * Determine if version can be accepted. Possibly vet major/minor upgrades.
     * 
     * @param current
     * @param proposed
     * @return
     */
    default boolean accept(final String current,final String proposed) {
        return true;
    }
}
