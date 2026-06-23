package com.github.seregamorph.maven.timeline;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * Read-only entity model for {@code build-data.json}, consumed by the D3 timeline
 * report. Bound by Jackson 3 via annotated constructors; JDK 8 compatible.
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

        @JsonCreator
        public Meta(
            @JsonProperty("threads") int threads,
            @JsonProperty("modules") int modules,
            @JsonProperty("totalGoals") int totalGoals,
            @JsonProperty("duration") BigDecimal duration
        ) {
            this.threads = threads;
            this.modules = modules;
            this.totalGoals = totalGoals;
            this.duration = duration;
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
        private final BigDecimal start;
        private final BigDecimal end;
        private final BigDecimal dur;

        @JsonCreator
        public Goal(
            @JsonProperty("name") String name,
            @JsonProperty("start") BigDecimal start,
            @JsonProperty("end") BigDecimal end,
            @JsonProperty("dur") BigDecimal dur
        ) {
            this.name = name;
            this.start = start;
            this.end = end;
            this.dur = dur;
        }

        public String getName() {
            return name;
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
        private final int threads;
        private final BigDecimal disk;

        @JsonCreator
        public Metric(
            @JsonProperty("t") BigDecimal t,
            @JsonProperty("active") int active,
            @JsonProperty("heapUsed") BigDecimal heapUsed,
            @JsonProperty("heapCommitted") BigDecimal heapCommitted,
            @JsonProperty("gc") boolean gc,
            @JsonProperty("processCpu") BigDecimal processCpu,
            @JsonProperty("systemCpu") BigDecimal systemCpu,
            @JsonProperty("threads") int threads,
            @JsonProperty("disk") BigDecimal disk
        ) {
            this.t = t;
            this.active = active;
            this.heapUsed = heapUsed;
            this.heapCommitted = heapCommitted;
            this.gc = gc;
            this.processCpu = processCpu;
            this.systemCpu = systemCpu;
            this.threads = threads;
            this.disk = disk;
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

        public int getThreads() {
            return threads;
        }

        public BigDecimal getDisk() {
            return disk;
        }
    }

    private static <T> List<T> unmodifiable(List<T> list) {
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
    }
}
