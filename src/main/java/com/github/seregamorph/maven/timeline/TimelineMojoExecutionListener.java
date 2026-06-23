package com.github.seregamorph.maven.timeline;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.execution.MojoExecutionEvent;
import org.apache.maven.execution.MojoExecutionListener;

/**
 * @author Sergey Chernov
 */
@Named
@Singleton
public class TimelineMojoExecutionListener implements MojoExecutionListener {

    private final TimelineHelper timelineHelper;

    @Inject
    public TimelineMojoExecutionListener(TimelineHelper timelineHelper) {
        this.timelineHelper = timelineHelper;
    }

    @Override
    public void beforeMojoExecution(MojoExecutionEvent event) {
        timelineHelper.onStart(event);
    }

    @Override
    public void afterMojoExecutionSuccess(MojoExecutionEvent event) {
        timelineHelper.onSuccess(event);
    }

    @Override
    public void afterExecutionFailure(MojoExecutionEvent event) {
        timelineHelper.onFailure(event);
    }
}
