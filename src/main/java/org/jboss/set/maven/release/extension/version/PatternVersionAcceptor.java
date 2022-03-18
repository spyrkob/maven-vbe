package org.jboss.set.maven.release.extension.version;

import java.util.regex.Pattern;

public class PatternVersionAcceptor implements VersionAcceptanceCriteria {

    private final Pattern pattern;

    public PatternVersionAcceptor() {
        super();
        final String stringPattern = System.getProperty(VBE_VERSION_ACCEPTOR_CONF_PROPERTY);
        if (stringPattern != null) {
            this.pattern = Pattern.compile(stringPattern);
        } else {
            this.pattern = null;
        }
    }

    @Override
    public boolean accept(String v) {
        return pattern != null && v != null && pattern.matcher(v).find();
    }
}
