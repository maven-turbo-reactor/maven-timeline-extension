package com.github.seregamorph.maven.timeline;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Singleton;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.MojoExecutionEvent;
import org.apache.maven.project.MavenProject;

/**
 * @author Sergey Chernov
 */
@Singleton
public class TimelineHelper {

    private Instant startTime;
    private int modulesNumber;
    private AtomicInteger workerThreadCounter;
    private ThreadLocal<Integer> currentWorkerThreadId;
    private Map<Integer, Map<GroupArtifactId, ModuleData>> threadModules;
    private MetricsCollector metricsCollector;

    private static class ModuleData {

        private final List<CompleteGoal> completeGoals = new ArrayList<>();

        private StartedGoal startedGoal;
    }

    private static class StartedGoal {
        private final Instant started;

        private StartedGoal(Instant started) {
            this.started = started;
        }
    }

    private static class CompleteGoal {
        private final String name;
        private final Instant started;
        private final Instant finished;

        private CompleteGoal(String name, Instant started, Instant finished) {
            this.name = name;
            this.started = started;
            this.finished = finished;
        }
    }

    void init(MavenSession session) {
        startTime = Instant.now();
        metricsCollector = new MetricsCollector(startTime);
        modulesNumber = session.getAllProjects().size();
        // reset state to be maven daemon compatible
        workerThreadCounter = new AtomicInteger();
        // start with 0
        currentWorkerThreadId = ThreadLocal.withInitial(workerThreadCounter::getAndIncrement);
        threadModules = Collections.synchronizedMap(new LinkedHashMap<>());
        metricsCollector.start();
    }

    void onStart(MojoExecutionEvent event) {
        metricsCollector.incActiveTasks();
        ModuleData moduleData = getModuleData(event);
        moduleData.startedGoal = new StartedGoal(Instant.now());
    }

    void onSuccess(MojoExecutionEvent event) {
        onComplete(event, true);
    }

    void onFailure(MojoExecutionEvent event) {
        onComplete(event, false);
    }

    private void onComplete(MojoExecutionEvent event, boolean success) {
        // todo distinguish failure
        metricsCollector.decActiveTasks();
        ModuleData moduleData = getModuleData(event);
        String goalName = event.getExecution().getGoal();
        CompleteGoal completeGoal = new CompleteGoal(goalName, moduleData.startedGoal.started, Instant.now());
        moduleData.completeGoals.add(completeGoal);
        moduleData.startedGoal = null;
    }

    private ModuleData getModuleData(MojoExecutionEvent event) {
        Map<GroupArtifactId, ModuleData> modules = threadModules.computeIfAbsent(currentWorkerThreadId.get(),
            $ -> Collections.synchronizedMap(new LinkedHashMap<>()));
        GroupArtifactId groupArtifactId = groupArtifactId(event.getProject());
        // there should be no contention on moduleData as reactor builds them in a single thread
        return modules.computeIfAbsent(groupArtifactId, $ -> new ModuleData());
    }

    private BigDecimal fromStart(Instant time) {
        Duration duration = Duration.between(startTime, time);
        return TimeFormatUtils.toSeconds(duration);
    }

    BuildData complete() {
        Instant finished = Instant.now();
        BigDecimal totalDurationSec = fromStart(finished);
        List<BuildData.Task> tasks = new ArrayList<>();
        int totalGoals = 0;
        for (Map.Entry<Integer, Map<GroupArtifactId, ModuleData>> entry : threadModules.entrySet()) {
            int threadId = entry.getKey();
            for (Map.Entry<GroupArtifactId, ModuleData> moduleDataEntry : entry.getValue().entrySet()) {
                GroupArtifactId groupArtifactId = moduleDataEntry.getKey();
                ModuleData moduleData = moduleDataEntry.getValue();
                List<BuildData.Goal> goals = new ArrayList<>();
                Instant earliestStarted = null;
                Instant latestFinished = null;
                for (CompleteGoal completeGoal : moduleData.completeGoals) {
                    totalGoals++;
                    goals.add(new BuildData.Goal(
                        completeGoal.name,
                        fromStart(completeGoal.started),
                        fromStart(completeGoal.finished),
                        TimeFormatUtils.toSeconds(Duration.between(completeGoal.started, completeGoal.finished))
                    ));
                    if (earliestStarted == null || earliestStarted.compareTo(completeGoal.started) < 0) {
                        earliestStarted = completeGoal.started;
                    }
                    if (latestFinished == null || latestFinished.compareTo(completeGoal.finished) > 0) {
                        latestFinished = completeGoal.finished;
                    }
                }
                tasks.add(new BuildData.Task(
                    groupArtifactId.toString(), threadId,
                    fromStart(earliestStarted), fromStart(latestFinished),
                    TimeFormatUtils.toSeconds(Duration.between(earliestStarted, latestFinished)),
                    goals
                ));
            }
        }
        List<BuildData.Metric> metrics = metricsCollector.getMetrics();
        BuildData.Meta meta = new BuildData.Meta(
            workerThreadCounter.get(),
            modulesNumber,
            totalGoals,
            totalDurationSec);
        return new BuildData(
            meta,
            tasks,
            metrics
        );
    }

    private static GroupArtifactId groupArtifactId(MavenProject project) {
        return new GroupArtifactId(project.getGroupId(), project.getArtifactId());
    }
}
