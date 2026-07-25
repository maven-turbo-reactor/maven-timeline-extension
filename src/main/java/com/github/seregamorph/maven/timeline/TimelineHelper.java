package com.github.seregamorph.maven.timeline;

import java.math.BigDecimal;
import java.time.Duration;
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

    private boolean initialized;
    private long startNanos;
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

        private final long startedNanosProject;

        @Nullable
        private StartedGoal startedGoal;

        private long finishedNanosProject;

        private ModuleData(long startedNanosProject) {
            this.startedNanosProject = startedNanosProject;
        }
    }

    private static class StartedGoal {
        private final long startedNanosGoal;

        private StartedGoal(long startedNanosGoal) {
            this.startedNanosGoal = startedNanosGoal;
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
        private final long startedNanos;
        private final long finishedNanos;

        private CompleteGoal(String name, String type, long startedNanos, long finishedNanos) {
            this.name = name;
            this.type = type;
            this.startedNanos = startedNanos;
            this.finishedNanos = finishedNanos;
        }
    }

    boolean isInitialized() {
        return initialized;
    }

    void init() {
        resolverIoStats.reset();

        startNanos = System.nanoTime();
        metricsCollector = new MetricsCollector(resolverIoStats, startNanos);
        // reset state to be maven daemon compatible
        workerThreadCounter = new AtomicInteger();
        // start with 0
        currentWorkerThreadId = ThreadLocal.withInitial(workerThreadCounter::getAndIncrement);
        threadModules = Collections.synchronizedMap(new LinkedHashMap<>());
        metricsCollector.start();
        initialized = true;
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
                PREPARE_GOAL, PREPARE_GOAL, moduleData.startedNanosProject, System.nanoTime()));
        }
        moduleData.startedGoal = new StartedGoal(System.nanoTime());
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
        String phase = MojoUtils.getMojoPhase(event.getExecution());
        String type = goalType(phase);
        // may be null in case of failed execution
        if (moduleData.startedGoal != null) {
            CompleteGoal completeGoal = new CompleteGoal(
                goalName, type, moduleData.startedGoal.startedNanosGoal, System.nanoTime());
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
        moduleData.finishedNanosProject = System.nanoTime();
    }

    private ModuleData getModuleData(MavenProject project) {
        Map<GroupArtifactId, ModuleData> modules = threadModules.computeIfAbsent(currentWorkerThreadId.get(),
            $ -> Collections.synchronizedMap(new LinkedHashMap<>()));
        GroupArtifactId groupArtifactId = groupArtifactId(project);
        // there should be no contention on moduleData as reactor builds them in a single thread
        return modules.computeIfAbsent(groupArtifactId, $ -> new ModuleData(System.nanoTime()));
    }

    private BigDecimal fromStart(long timeNanos) {
        long durationNanos = timeNanos - startNanos;
        return TimeFormatUtils.toSeconds(durationNanos);
    }

    BuildData complete(MavenSession session) {
        long finishedNanos = System.nanoTime();
        BigDecimal durationSec = fromStart(finishedNanos);
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
                        Duration.ofNanos(completeGoal.finishedNanos - completeGoal.startedNanos));
                    goals.add(new BuildData.Goal(
                        completeGoal.name,
                        completeGoal.type,
                        fromStart(completeGoal.startedNanos),
                        fromStart(completeGoal.finishedNanos),
                        TimeFormatUtils.toSeconds(completeGoal.finishedNanos - completeGoal.startedNanos)
                    ));
                }
                String moduleName = duplicateArtifactIds.contains(groupArtifactId.artifactId()) ?
                    groupArtifactId.toString() : groupArtifactId.artifactId();
                tasks.add(new BuildData.Task(
                    moduleName,
                    threadId,
                    fromStart(moduleData.startedNanosProject), fromStart(moduleData.finishedNanosProject),
                    TimeFormatUtils.toSeconds(moduleData.finishedNanosProject - moduleData.startedNanosProject),
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
