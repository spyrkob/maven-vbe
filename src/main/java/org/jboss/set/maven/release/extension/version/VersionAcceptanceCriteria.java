package org.jboss.set.maven.release.extension.version;

public interface VersionAcceptanceCriteria {
    String VBE_VERSION_ACCEPTOR_PROPERTY = "vbe.version.acceptor";
    String VBE_VERSION_ACCEPTOR_CONF_PROPERTY = "vbe.version.acceptor.conf";
    /**
     * Determine if version can be accepted
     * 
     * @param v
     * @return
     */
    default boolean accept(final String v) {
        return true;
    }
}
