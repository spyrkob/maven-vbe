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
