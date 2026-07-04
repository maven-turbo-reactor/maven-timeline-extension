package com.github.seregamorph.maven.timeline;

import org.apache.maven.execution.MavenSession;

final class IdeaImportSupport {

    static boolean isIdeaImport(MavenSession session) {
        // Implementation hint: check "idea.maven.embedder.version" as it's used during the IDEA reimport,
        // while the "idea.version" is specified in both IDEA import and run maven from IDEA.

        // Sample value: "3.9.16"
        return MavenPropertyUtils.getProperty(session, "idea.maven.embedder.version") != null;
    }

    private IdeaImportSupport() {
    }
}
