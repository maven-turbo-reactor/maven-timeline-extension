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
    /**
     * If there is more than 1 module with the same artifactId, distinguish it with "${groupId}:" prefix
     */
    private Set<String> duplicateArtifactIds;
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

    void init(MavenSession session) {
        resolverIoStats.reset();

        startTime = Instant.now();
        metricsCollector = new MetricsCollector(resolverIoStats, startTime);
        modulesNumber = session.getAllProjects().size();
        duplicateArtifactIds = session.getAllProjects().stream()
            .map(MavenProject::getArtifactId)
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
            .entrySet()
            .stream()
            .filter(p -> p.getValue() > 1)
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
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
        String type = goalType(pluginArtifactId, goal);
        CompleteGoal completeGoal = new CompleteGoal(
            goalName, type, moduleData.startedGoal.startedGoal, Instant.now());
        moduleData.completeGoals.add(completeGoal);
        moduleData.startedGoal = null;
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
     * Coarse classification of a goal, used by the report to color timeline atoms.
     * Returns one of {@code "compile"}, {@code "test-compile"}, {@code "test"},
     * {@code "deploy"}, or {@code "other"} for everything else
     * (the synthetic {@code "<prepare>"} type is assigned separately).
     */
    static String goalType(String pluginArtifactId, String goal) {
        switch (pluginArtifactId) {
            case "maven-compiler-plugin":
                if ("compile".equals(goal)) {
                    return "compile";
                }
                if ("testCompile".equals(goal)) {
                    return "test-compile";
                }
                break;
            case "kotlin-maven-plugin":
                if ("compile".equals(goal)) {
                    return "compile";
                }
                if ("test-compile".equals(goal)) {
                    return "test-compile";
                }
                break;
            case "maven-surefire-plugin":
                if ("test".equals(goal)) {
                    return "test";
                }
                break;
            case "maven-failsafe-plugin":
                if ("integration-test".equals(goal)) {
                    return "test";
                }
                break;
            case "maven-deploy-plugin":
                if ("deploy".equals(goal)) {
                    return "deploy";
                }
                break;
            default:
                break;
        }
        return "other";
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
                        completeGoal.type,
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
                String moduleName = duplicateArtifactIds.contains(groupArtifactId.artifactId()) ?
                    groupArtifactId.toString() : groupArtifactId.artifactId();
                tasks.add(new BuildData.Task(
                    moduleName,
                    threadId,
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
