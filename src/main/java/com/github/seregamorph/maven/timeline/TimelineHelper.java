package com.github.seregamorph.maven.timeline;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
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

        @Nullable
        private StartedGoal startedGoal;

        private Instant finishedProject;

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
        /**
         * "${pluginName}:${goalName}@${executionId}"
         */
        private final String name;
        /**
         * Coarse goal classification used to color the timeline, see {@link #goalType}.
         */
        private final String type;
        private final Instant started;
        private final Instant finished;

        private CompleteGoal(String name, String type, Instant started, Instant finished) {
            this.name = name;
            this.type = type;
            this.started = started;
            this.finished = finished;
        }
    }

    void init() {
        resolverIoStats.reset();

        startTime = Instant.now();
        metricsCollector = new MetricsCollector(resolverIoStats, startTime);
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
            moduleData.completeGoals.add(new CompleteGoal(
                PREPARE_GOAL, PREPARE_GOAL, moduleData.startedProject, Instant.now()));
        }
        moduleData.startedGoal = new StartedGoal(Instant.now());
    }

    void onComplete(MojoExecutionEvent event, boolean success) {
        // todo distinguish failure
        ModuleData moduleData = getModuleData(event.getProject());

        String pluginArtifactId = event.getExecution().getArtifactId();
        String pluginName = getPluginName(pluginArtifactId);
        String goal = event.getExecution().getGoal();
        String executionId = event.getExecution().getExecutionId();
        String goalName = (pluginName.equals(goal) ? pluginName : pluginName + ":" + goal)
            + (goal.equals(executionId) ? "" : "@" + executionId);
        String type = goalType(event.getExecution().getLifecyclePhase());
        // may be null in case of failed execution
        if (moduleData.startedGoal != null) {
            CompleteGoal completeGoal = new CompleteGoal(
                goalName, type, moduleData.startedGoal.startedGoal, Instant.now());
            moduleData.completeGoals.add(completeGoal);
            moduleData.startedGoal = null;
        } else {
            // it may happen, that success is false
        }
    }

    static String getPluginName(String pluginArtifactId) {
        if (pluginArtifactId.startsWith("maven-") && pluginArtifactId.endsWith("-plugin")) {
            return pluginArtifactId.substring(6, pluginArtifactId.length() - 7);
        }
        if (pluginArtifactId.endsWith("-maven-plugin")) {
            return pluginArtifactId.substring(0, pluginArtifactId.length() - 13);
        }
        return pluginArtifactId;
    }

    /**
     * Coarse classification of a goal based on the lifecycle phase it is bound to, used by the report to color
     * timeline atoms. Returns one of {@code "generate-sources"}, {@code "compile"}, {@code "generate-test-sources"},
     * {@code "test-compile"}, {@code "test"}, {@code "deploy"}, or {@code "other"} for everything else (the synthetic
     * {@code "<prepare>"} type is assigned separately).
     *
     * @param phase the lifecycle phase the mojo is bound to, may be {@code null} for directly invoked goals
     */
    static String goalType(@Nullable String phase) {
        if (phase == null) {
            return "other";
        }
        switch (phase) {
            case "generate-sources":
            case "process-sources":
            case "generate-resources":
            case "process-resources":
                return "generate-sources";
            case "compile":
                return "compile";
            case "generate-test-sources":
            case "process-test-sources":
            case "generate-test-resources":
            case "process-test-resources":
                return "generate-test-sources";
            case "test-compile":
                return "test-compile";
            case "test":
            case "integration-test":
                return "test";
            case "deploy":
                return "deploy";
            default:
                return "other";
        }
    }

    void onComplete(ProjectExecutionEvent event, boolean success) {
        metricsCollector.decActiveTasks();
        ModuleData moduleData = getModuleData(event.getProject());
        moduleData.finishedProject = Instant.now();
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

    BuildData complete(MavenSession session) {
        Instant finished = Instant.now();
        BigDecimal durationSec = fromStart(finished);
        List<BuildData.Task> tasks = new ArrayList<>();
        int totalGoals = 0;
        Duration totalSerialTime = Duration.ZERO;
        Set<String> duplicateArtifactIds = getDuplicateArtifactIds(session);
        for (Map.Entry<Integer, Map<GroupArtifactId, ModuleData>> entry : threadModules.entrySet()) {
            int threadId = entry.getKey();
            for (Map.Entry<GroupArtifactId, ModuleData> moduleDataEntry : entry.getValue().entrySet()) {
                GroupArtifactId groupArtifactId = moduleDataEntry.getKey();
                ModuleData moduleData = moduleDataEntry.getValue();
                List<BuildData.Goal> goals = new ArrayList<>();
                for (CompleteGoal completeGoal : moduleData.completeGoals) {
                    if (!PREPARE_GOAL.equals(completeGoal.name)) {
                        totalGoals++;
                    }
                    totalSerialTime = totalSerialTime.plus(
                        Duration.between(completeGoal.started, completeGoal.finished));
                    goals.add(new BuildData.Goal(
                        completeGoal.name,
                        completeGoal.type,
                        fromStart(completeGoal.started),
                        fromStart(completeGoal.finished),
                        TimeFormatUtils.toSeconds(Duration.between(completeGoal.started, completeGoal.finished))
                    ));
                }
                String moduleName = duplicateArtifactIds.contains(groupArtifactId.artifactId()) ?
                    groupArtifactId.toString() : groupArtifactId.artifactId();
                tasks.add(new BuildData.Task(
                    moduleName,
                    threadId,
                    fromStart(moduleData.startedProject), fromStart(moduleData.finishedProject),
                    TimeFormatUtils.toSeconds(Duration.between(moduleData.startedProject, moduleData.finishedProject)),
                    goals
                ));
            }
        }
        List<BuildData.Metric> metrics = metricsCollector.getMetrics();
        int modulesNumber = session.getAllProjects().size();
        BuildData.Meta meta = new BuildData.Meta(
            workerThreadCounter.get(),
            modulesNumber,
            totalGoals,
            durationSec,
            TimeFormatUtils.toSeconds(totalSerialTime));
        return new BuildData(
            meta,
            tasks,
            metrics
        );
    }

    /**
     * If there is more than 1 module with the same artifactId, distinguish it with "${groupId}:" prefix
     */
    private static Set<String> getDuplicateArtifactIds(MavenSession session) {
        return session.getAllProjects().stream()
            .map(MavenProject::getArtifactId)
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
            .entrySet()
            .stream()
            .filter(p -> p.getValue() > 1)
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }

    private static GroupArtifactId groupArtifactId(MavenProject project) {
        return new GroupArtifactId(project.getGroupId(), project.getArtifactId());
    }
}
