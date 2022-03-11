package org.jboss.set.maven.release.extension.version;

import java.util.Comparator;

public class InsaneVersionComparator implements Comparator<String> {
    public static final Comparator<String> INSTANCE = new InsaneVersionComparator();

    private InsaneVersionComparator() {
    }

    // TODO: switch to
    // https://github.com/jmesnil/wildfly-channel/blob/main/core/src/main/java/org/wildfly/channel/version/VersionMatcher.java
    // once we get to channels.
    @Override
    public int compare(String v1, String v2) {
        int i1 = 0, i2 = 0;
        final int epoch1;
        int i = v1.indexOf(":");
        if (i != -1) {
            epoch1 = Integer.valueOf(v1.substring(0, i));
            i1 = i;
        } else
            epoch1 = 0;
        final int epoch2;
        i = v2.indexOf(":");
        if (i != -1) {
            epoch2 = Integer.valueOf(v2.substring(0, i));
            i2 = i;
        } else
            epoch2 = 0;
        if (epoch1 != epoch2)
            return epoch1 - epoch2;

        final int lim1 = v1.length(), lim2 = v2.length();
        while (i1 < lim1 && i2 < lim2) {
            final char c1 = v1.charAt(i1);
            final char c2 = v2.charAt(i2);
            if (Character.isDigit(c1) || Character.isDigit(c2)) {
                int ei1 = i1, ei2 = i2;
                while (ei1 < lim1 && Character.isDigit(v1.charAt(ei1)))
                    ei1++;
                while (ei2 < lim2 && Character.isDigit(v2.charAt(ei2)))
                    ei2++;
                final int n1 = ei1 == i1 ? 0 : Integer.valueOf(v1.substring(i1, ei1));
                final int n2 = ei2 == i2 ? 0 : Integer.valueOf(v2.substring(i2, ei2));
                if (n1 != n2)
                    return n1 - n2;
                i1 = ei1;
                i2 = ei2;
                if (i1 != i2)
                    return i2 - i1;
            } else if (c1 == c2) {
                i1++;
                i2++;
            } else {
                return c1 - c2;
            }
        }
        return lim1 - lim2;
    }

}
