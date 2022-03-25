package org.jboss.set.maven.release.extension.version;

import java.util.regex.Pattern;

public class RedhatVersionAcceptor implements VersionAcceptanceCriteria {

    // redhat prod version "-redhat-" and ".redhat-"
    public static Pattern redhatPattern = Pattern.compile("[\\.|-]redhat-");

    @Override
    public boolean accept(final String current, final String proposed) {
        return proposed != null && redhatPattern.matcher(proposed).find();
    }

}
