package com.github.seregamorph.maven.timeline;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.execution.ProjectExecutionEvent;
import org.apache.maven.execution.ProjectExecutionListener;

/**
 * @author Sergey Chernov
 */
@Named
@Singleton
public class TimelineProjectExecutionListener implements ProjectExecutionListener {

    private final TimelineHelper timelineHelper;

    @Inject
    public TimelineProjectExecutionListener(TimelineHelper timelineHelper) {
        this.timelineHelper = timelineHelper;
    }

    @Override
    public void beforeProjectExecution(ProjectExecutionEvent event) {
        timelineHelper.onStart(event);
    }

    @Override
    public void beforeProjectLifecycleExecution(ProjectExecutionEvent event) {
    }

    @Override
    public void afterProjectExecutionSuccess(ProjectExecutionEvent event) {
        timelineHelper.onComplete(event, true);
    }

    @Override
    public void afterProjectExecutionFailure(ProjectExecutionEvent event) {
        timelineHelper.onComplete(event, false);
    }
}
