package com.github.seregamorph.maven.timeline;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Read-only entity model for {@code build-data.json}, consumed by the D3 timeline
 * report.
 *
 * @author Sergey Chernov
 */
public final class BuildData {

    private final Meta meta;
    private final List<Task> tasks;
    private final List<Metric> metrics;

    @JsonCreator
    public BuildData(
        @JsonProperty("meta") Meta meta,
        @JsonProperty("tasks") List<Task> tasks,
        @JsonProperty("metrics") List<Metric> metrics
    ) {
        this.meta = meta;
        this.tasks = unmodifiable(tasks);
        this.metrics = unmodifiable(metrics);
    }

    public Meta getMeta() {
        return meta;
    }

    public List<Task> getTasks() {
        return tasks;
    }

    public List<Metric> getMetrics() {
        return metrics;
    }

    /**
     * Build-wide summary.
     */
    public static final class Meta {

        private final int threads;
        private final int modules;
        private final int totalGoals;
        private final BigDecimal duration;
        private final BigDecimal serialDuration;

        @JsonCreator
        public Meta(
            @JsonProperty("threads") int threads,
            @JsonProperty("modules") int modules,
            @JsonProperty("totalGoals") int totalGoals,
            @JsonProperty("duration") BigDecimal duration,
            @JsonProperty("serialDuration") BigDecimal serialDuration
        ) {
            this.threads = threads;
            this.modules = modules;
            this.totalGoals = totalGoals;
            this.duration = duration;
            this.serialDuration = serialDuration;
        }

        public int getThreads() {
            return threads;
        }

        public int getModules() {
            return modules;
        }

        public int getTotalGoals() {
            return totalGoals;
        }

        public BigDecimal getDuration() {
            return duration;
        }

        public BigDecimal getSerialDuration() {
            return serialDuration;
        }
    }

    /**
     * A single module build, occupying one worker thread for {@code [start, end]}.
     */
    public static final class Task {

        private final String module;
        private final int thread;
        private final BigDecimal start;
        private final BigDecimal end;
        private final BigDecimal dur;
        private final List<Goal> goals;

        @JsonCreator
        public Task(
            @JsonProperty("module") String module,
            @JsonProperty("thread") int thread,
            @JsonProperty("start") BigDecimal start,
            @JsonProperty("end") BigDecimal end,
            @JsonProperty("dur") BigDecimal dur,
            @JsonProperty("goals") List<Goal> goals
        ) {
            this.module = module;
            this.thread = thread;
            this.start = start;
            this.end = end;
            this.dur = dur;
            this.goals = unmodifiable(goals);
        }

        public String getModule() {
            return module;
        }

        public int getThread() {
            return thread;
        }

        public BigDecimal getStart() {
            return start;
        }

        public BigDecimal getEnd() {
            return end;
        }

        public BigDecimal getDur() {
            return dur;
        }

        public List<Goal> getGoals() {
            return goals;
        }
    }

    /**
     * A single maven goal execution within a {@link Task} — the timeline atom.
     */
    public static final class Goal {

        private final String name;
        // coarse classification used to color the timeline atom (see TimelineHelper.goalType)
        private final String type;
        // lifecycle phase of the executed goal
        @Nullable
        private final String phase;
        private final BigDecimal start;
        private final BigDecimal end;
        private final BigDecimal prepareDur;
        private final BigDecimal execDur;
        // true if the mojo threw, rendered as a failure highlight by the report
        private final boolean failed;

        @JsonCreator
        public Goal(
            @JsonProperty("name") String name,
            @JsonProperty("type") String type,
            @JsonProperty("phase") @Nullable String phase,
            @JsonProperty("start") BigDecimal start,
            @JsonProperty("end") BigDecimal end,
            @JsonProperty("prepareDur") BigDecimal prepareDur,
            @JsonProperty("execDur") BigDecimal execDur,
            @JsonProperty("failed") boolean failed
        ) {
            this.name = name;
            this.type = type;
            this.phase = phase;
            this.start = start;
            this.end = end;
            this.prepareDur = prepareDur;
            this.execDur = execDur;
            this.failed = failed;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        @Nullable
        public String getPhase() {
            return phase;
        }

        public BigDecimal getStart() {
            return start;
        }

        public BigDecimal getEnd() {
            return end;
        }

        public BigDecimal getPrepareDur() {
            return prepareDur;
        }

        public BigDecimal getExecDur() {
            return execDur;
        }

        /**
         * Serialized only for the (rare) failed goals to keep {@code build-data.json} compact.
         */
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        public boolean isFailed() {
            return failed;
        }
    }

    /**
     * A sampled metrics point at time {@code t} (seconds since build start).
     */
    public static final class Metric {

        private final BigDecimal t;
        private final int active;
        private final BigDecimal heapUsed;
        private final BigDecimal heapCommitted;
        private boolean gc;
        private final BigDecimal processCpu;
        private final BigDecimal systemCpu;
        // all live threads (daemon and non-daemon), so daemonThreads <= totalThreads
        private final int totalThreads;
        private final int daemonThreads;
        // computed as a post-processing step from recorded transfer intervals, hence mutable
        private BigDecimal resolverDownload;
        private BigDecimal resolverUpload;

        @JsonCreator
        public Metric(
            @JsonProperty("t") BigDecimal t,
            @JsonProperty("active") int active,
            @JsonProperty("heapUsed") BigDecimal heapUsed,
            @JsonProperty("heapCommitted") BigDecimal heapCommitted,
            @JsonProperty("gc") boolean gc,
            @JsonProperty("processCpu") BigDecimal processCpu,
            @JsonProperty("systemCpu") BigDecimal systemCpu,
            @JsonProperty("totalThreads") int totalThreads,
            @JsonProperty("daemonThreads") int daemonThreads,
            @JsonProperty("resolverDownload") BigDecimal resolverDownload,
            @JsonProperty("resolverUpload") BigDecimal resolverUpload
        ) {
            this.t = t;
            this.active = active;
            this.heapUsed = heapUsed;
            this.heapCommitted = heapCommitted;
            this.gc = gc;
            this.processCpu = processCpu;
            this.systemCpu = systemCpu;
            this.totalThreads = totalThreads;
            this.daemonThreads = daemonThreads;
            this.resolverDownload = resolverDownload;
            this.resolverUpload = resolverUpload;
        }

        public BigDecimal getT() {
            return t;
        }

        public int getActive() {
            return active;
        }

        public BigDecimal getHeapUsed() {
            return heapUsed;
        }

        public BigDecimal getHeapCommitted() {
            return heapCommitted;
        }

        public void setGc(boolean gc) {
            this.gc = gc;
        }

        public boolean isGc() {
            return gc;
        }

        public BigDecimal getProcessCpu() {
            return processCpu;
        }

        public BigDecimal getSystemCpu() {
            return systemCpu;
        }

        public int getTotalThreads() {
            return totalThreads;
        }

        public int getDaemonThreads() {
            return daemonThreads;
        }

        public BigDecimal getResolverDownload() {
            return resolverDownload;
        }

        public void setResolverDownload(BigDecimal resolverDownload) {
            this.resolverDownload = resolverDownload;
        }

        public BigDecimal getResolverUpload() {
            return resolverUpload;
        }

        public void setResolverUpload(BigDecimal resolverUpload) {
            this.resolverUpload = resolverUpload;
        }
    }

    private static <T> List<T> unmodifiable(List<T> list) {
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
    }
}
