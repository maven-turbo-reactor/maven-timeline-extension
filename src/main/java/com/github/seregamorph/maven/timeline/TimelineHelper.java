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
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.MojoExecutionEvent;
import org.apache.maven.execution.ProjectExecutionEvent;
import org.apache.maven.project.MavenProject;

/**
 * @author Sergey Chernov
 */
@Singleton
public class TimelineHelper {

    public static final String PREPARE_GOAL = "<prepare>";
    private final ResolverIoStats resolverIoStats;

    private Instant startTime;
    private int modulesNumber;
    private AtomicInteger workerThreadCounter;
    private ThreadLocal<Integer> currentWorkerThreadId;
    private Map<Integer, Map<GroupArtifactId, ModuleData>> threadModules;
    private MetricsCollector metricsCollector;

    @Inject
    public TimelineHelper(ResolverIoStats resolverIoStats) {
        this.resolverIoStats = resolverIoStats;
    }

    private static class ModuleData {

        private final List<CompleteGoal> completeGoals = new ArrayList<>();

        private final Instant startedProject;

        private StartedGoal startedGoal;

        private ModuleData(Instant startedProject) {
            this.startedProject = startedProject;
        }
    }

    private static class StartedGoal {
        private final Instant startedGoal;

        private StartedGoal(Instant startedGoal) {
            this.startedGoal = startedGoal;
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
        resolverIoStats.reset();

        startTime = Instant.now();
        metricsCollector = new MetricsCollector(resolverIoStats, startTime);
        modulesNumber = session.getAllProjects().size();
        // reset state to be maven daemon compatible
        workerThreadCounter = new AtomicInteger();
        // start with 0
        currentWorkerThreadId = ThreadLocal.withInitial(workerThreadCounter::getAndIncrement);
        threadModules = Collections.synchronizedMap(new LinkedHashMap<>());
        metricsCollector.start();
    }

    void onStart(ProjectExecutionEvent event) {
        metricsCollector.incActiveTasks();
        // initializes ModuleData.startedProject
        getModuleData(event.getProject());
    }

    void onStart(MojoExecutionEvent event) {
        ModuleData moduleData = getModuleData(event.getProject());
        if (moduleData.completeGoals.isEmpty()) {
            // add fake "pre-execution" phase
            moduleData.completeGoals.add(new CompleteGoal(PREPARE_GOAL, moduleData.startedProject, Instant.now()));
        }
        moduleData.startedGoal = new StartedGoal(Instant.now());
    }

    void onComplete(MojoExecutionEvent event, boolean success) {
        // todo distinguish failure
        ModuleData moduleData = getModuleData(event.getProject());

        String goalName = event.getExecution().getGoal();
        CompleteGoal completeGoal = new CompleteGoal(goalName, moduleData.startedGoal.startedGoal, Instant.now());
        moduleData.completeGoals.add(completeGoal);
        moduleData.startedGoal = null;
    }

    void onComplete(ProjectExecutionEvent event, boolean success) {
        metricsCollector.decActiveTasks();
    }

    private ModuleData getModuleData(MavenProject project) {
        Map<GroupArtifactId, ModuleData> modules = threadModules.computeIfAbsent(currentWorkerThreadId.get(),
            $ -> Collections.synchronizedMap(new LinkedHashMap<>()));
        GroupArtifactId groupArtifactId = groupArtifactId(project);
        // there should be no contention on moduleData as reactor builds them in a single thread
        return modules.computeIfAbsent(groupArtifactId, $ -> new ModuleData(Instant.now()));
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
                    if (!PREPARE_GOAL.equals(completeGoal.name)) {
                        totalGoals++;
                    }
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
