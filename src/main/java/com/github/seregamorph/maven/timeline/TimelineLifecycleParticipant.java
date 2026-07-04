package com.github.seregamorph.maven.timeline;

import java.io.File;
import java.nio.charset.StandardCharsets;
import javax.inject.Inject;
import javax.inject.Named;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.SessionScoped;
import org.apache.maven.execution.MavenSession;

/**
 * @author Sergey Chernov
 */
@SessionScoped
@Named
public class TimelineLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    /**
     * When set to {@code true} (as a system property, user property, or root project property),
     * the report is emitted as a {@code build-report.html} plus a separate {@code build-data.json}
     * that the HTML fetches at runtime. By default a single self-contained {@code build-report.html}
     * is produced with the build data inlined.
     */
    static final String JSON_REPORT_PROPERTY = "timelineJsonReport";

    // token in static/build-report.html replaced with the inlined JSON for the self-contained report
    private static final String BUILD_DATA_PLACEHOLDER = "__TIMELINE_BUILD_DATA__";

    private final TimelineHelper timelineHelper;

    @Inject
    public TimelineLifecycleParticipant(TimelineHelper timelineHelper) {
        this.timelineHelper = timelineHelper;
    }

    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public void afterSessionStart(MavenSession session) {
        // Implementation notice: this method is called once in CLI execution, once in Maven execution via IDEA
        // (specifies "idea.version" property) and once in IDEA import (IDEA also specifies "idea.version" and
        // "idea.maven.embedder.version" properties)
        if (IdeaImportSupport.isIdeaImport(session)) {
            // Skip init as the afterSessionEnd(MavenSession) is never called,
            // so should not start the daemon threads
        } else {
            this.timelineHelper.init();
        }
    }

    @Override
    public void afterProjectsRead(MavenSession session) {
        // Implementation notice: this method is called once in CLI execution with the whole set of modules,
        // same for run Maven from IDEA (specifies "idea.version" property), but
        // runs once PER EACH module during the IDEA import
    }

    @Override
    public void afterSessionEnd(MavenSession session) {
        // Implementation notice: this method is called once in CLI execution, same while running Maven from IDEA
        // and never during the IDEA reimport
        if (!timelineHelper.isInitialized()) {
            // Return to reinsure avoiding failure
            return;
        }
        BuildData buildData = timelineHelper.complete(session);
        File targetDir = new File(session.getExecutionRootDirectory(), "target");
        File timelineDir = new File(targetDir, "timeline");
        timelineDir.mkdirs();

        String json = new String(JsonSerializers.serialize(buildData), StandardCharsets.UTF_8);
        String html = new String(ClasspathResources.readBytes("static/build-report.html"), StandardCharsets.UTF_8);
        File buildReportHtmlFile = new File(timelineDir, "build-report.html");

        boolean jsonReport = MavenPropertyUtils.isTrue(
            MavenPropertyUtils.getProperty(session, session.getTopLevelProject(), JSON_REPORT_PROPERTY));
        if (jsonReport) {
            // separate JSON file; the HTML fetches build-data.json at runtime
            File buildDataFile = new File(timelineDir, "build-data.json");
            MoreFileUtils.write(buildDataFile, json.getBytes(StandardCharsets.UTF_8));
            MoreFileUtils.write(buildReportHtmlFile, html.getBytes(StandardCharsets.UTF_8));
        } else {
            // single self-contained report with the build data inlined
            String inlinedHtml = html.replace(BUILD_DATA_PLACEHOLDER, json);
            MoreFileUtils.write(buildReportHtmlFile, inlinedHtml.getBytes(StandardCharsets.UTF_8));
        }
    }
}
