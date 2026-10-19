package com.github.seregamorph.maven.timeline;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.SessionScoped;
import org.apache.maven.execution.MavenSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Sergey Chernov
 */
@SessionScoped
@Named
public class TimelineLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    private static final Logger logger = LoggerFactory.getLogger(TimelineLifecycleParticipant.class);

    /**
     * When set to {@code true} (as a system property, user property, or root project property),
     * the report is emitted as a {@code build-report.html} plus a separate {@code build-data.json}
     * that the HTML fetches at runtime. By default a single self-contained {@code build-report.html}
     * is produced with the build data inlined.
     */
    static final String JSON_REPORT_PROPERTY = "timelineJsonReport";

    /**
     * When set to {@code false} (as a system or user property), running docker containers are not tracked.
     * By default they are listed via the {@code docker ps} CLI, if available.
     */
    static final String DOCKER_PROPERTY = "timelineDocker";

    private static final String REPORT_TEMPLATE = "static/build-report.html";
    private static final String D3_SCRIPT = "static/d3.min.js";

    // token in static/build-report.html replaced with the inlined JSON for the self-contained report
    private static final String BUILD_DATA_PLACEHOLDER = "__TIMELINE_BUILD_DATA__";
    // token in static/build-report.html replaced with the bundled D3 library, so the report does not need a CDN
    private static final String D3_PLACEHOLDER = "__TIMELINE_D3_JS__";

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
            logger.info("Starting build timeline metrics gathering");
            boolean trackDocker = !"false".equals(MavenPropertyUtils.getProperty(session, DOCKER_PROPERTY));
            this.timelineHelper.init(trackDocker);
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
        File buildReportHtmlFile = new File(timelineDir, "build-report.html");

        boolean jsonReport = MavenPropertyUtils.isTrue(
            MavenPropertyUtils.getProperty(session, session.getTopLevelProject(), JSON_REPORT_PROPERTY));
        if (jsonReport) {
            // separate JSON file; the HTML fetches build-data.json at runtime
            File buildDataFile = new File(timelineDir, "build-data.json");
            MoreFileUtils.write(buildDataFile, json.getBytes(StandardCharsets.UTF_8));
            MoreFileUtils.write(buildReportHtmlFile, renderReport(null).getBytes(StandardCharsets.UTF_8));
        } else {
            // single self-contained report with the build data inlined
            MoreFileUtils.write(buildReportHtmlFile, renderReport(json).getBytes(StandardCharsets.UTF_8));
        }
        logger.info("Build timeline report generated at {}", buildReportHtmlFile);
    }

    /**
     * Renders the report page with the bundled D3 library inlined.
     *
     * @param json build data to inline, or {@code null} to keep the placeholder so that the page fetches
     *             {@code build-data.json}
     */
    static String renderReport(@Nullable String json) {
        String d3 = requireInlineable(readUtf8(D3_SCRIPT), D3_SCRIPT);
        // D3 goes in first, so that a D3 token inside the build data is not expanded
        String html = readUtf8(REPORT_TEMPLATE).replace(D3_PLACEHOLDER, d3);
        return json == null ? html : html.replace(BUILD_DATA_PLACEHOLDER, json);
    }

    /**
     * Rejects text that would end the enclosing {@code <script>} element early or change how the HTML parser
     * reads it.
     */
    static String requireInlineable(String script, String resource) {
        String lowerCase = script.toLowerCase(Locale.ROOT);
        for (String token : new String[]{"</script", "<script", "<!--"}) {
            if (lowerCase.contains(token)) {
                throw new IllegalStateException(String.format(
                    "Resource [%s] contains [%s] and cannot be inlined into a <script> element", resource, token));
            }
        }
        return script;
    }

    private static String readUtf8(String resource) {
        return new String(ClasspathResources.readBytes(resource), StandardCharsets.UTF_8);
    }
}
