package com.github.seregamorph.maven.timeline;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

/**
 * @author Sergey Chernov
 */
public final class MavenPropertyUtils {

    // @Nullable
    public static String getProperty(MavenSession session, String propertyName) {
        String propertyValue = session.getSystemProperties().getProperty(propertyName);
        if (propertyValue == null) {
            propertyValue = session.getUserProperties().getProperty(propertyName);
        }
        return propertyValue;
    }

    // @Nullable
    public static String getProperty(MavenSession session, MavenProject project, String propertyName) {
        String propertyValue = getProperty(session, propertyName);
        if (propertyValue == null) {
            if ("project.version".equals(propertyName)) {
                return project.getVersion();
            }
            propertyValue = project.getProperties().getProperty(propertyName);
        }
        return propertyValue;
    }

    /**
     * Return true if and only if value is "true" (case-sensitive), otherwise false.
     *
     * @param value
     * @return
     */
    public static boolean isTrue(String value) {
        return "true".equals(value);
    }

    private MavenPropertyUtils() {
    }
}
