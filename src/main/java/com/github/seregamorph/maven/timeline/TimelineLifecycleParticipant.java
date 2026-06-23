package com.github.seregamorph.maven.timeline;

import java.io.File;
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

    private final TimelineHelper timelineHelper;

    @Inject
    public TimelineLifecycleParticipant(TimelineHelper timelineHelper) {
        this.timelineHelper = timelineHelper;
    }

    @Override
    public void afterProjectsRead(MavenSession session) {
        this.timelineHelper.init(session);
    }

    @Override
    public void afterSessionEnd(MavenSession session) {
        BuildData buildData = timelineHelper.complete();
        File targetDir = new File(session.getExecutionRootDirectory(), "target");
        File timelineDir = new File(targetDir, "timeline");
        timelineDir.mkdirs();
        File buildDataFile = new File(timelineDir, "build-data.json");
        MoreFileUtils.write(buildDataFile, JsonSerializers.serialize(buildData));

        byte[] buildReportHtmlBytes = ClasspathResources.readBytes("static/build-report.html");
        File buildReportHtmlFile = new File(timelineDir, "build-report.html");
        MoreFileUtils.write(buildReportHtmlFile, buildReportHtmlBytes);
    }
}
