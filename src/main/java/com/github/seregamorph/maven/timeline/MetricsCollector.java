package com.github.seregamorph.maven.timeline;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Sergey Chernov
 */
public class MetricsCollector {

    private static final long CYCLE_INTERVAL_MS = 500L;

    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final List<BuildData.Metric> metrics = new ArrayList<>();

    /*
    TODO
    getClassLoadingMXBean
     */
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private final List<GarbageCollectorMXBean> garbageCollectorMXBean = ManagementFactory.getGarbageCollectorMXBeans();
    private final OperatingSystemMXBean operatingSystemMXBean =
        (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private final Instant startTime;
    private final Thread worker;

    // @GuardedBy("metrics")
    private boolean active = true;

    // @GuardedBy("metrics")
    private Long gcCount = null;

    public MetricsCollector(Instant startTime) {
        this.startTime = startTime;
        // first metric
        BuildData.Metric firstMetric;
        synchronized (metrics) {
            firstMetric = scrapeMetrics();
        }
        worker = new Thread(() -> {
            synchronized (metrics) {
                metrics.add(firstMetric);
                while (active) {
                    try {
                        metrics.wait(CYCLE_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (active) {
                        BuildData.Metric metric = scrapeMetrics();
                        metrics.add(metric);
                    }
                }
            }
        });
    }

    private BuildData.Metric scrapeMetrics() {
        int threads = threadMXBean.getThreadCount();
        int daemonThreads = threadMXBean.getDaemonThreadCount();
        long heapUsedBytes = memoryMXBean.getHeapMemoryUsage().getUsed();
        long heapCommittedBytes = memoryMXBean.getHeapMemoryUsage().getCommitted();
        // recent CPU usage as a fraction [0.0, 1.0]; both return a negative
        // value when not yet available (e.g. first sample)
        double processCpuLoad = operatingSystemMXBean.getProcessCpuLoad();
        double systemCpuLoad = operatingSystemMXBean.getSystemCpuLoad();
        long gcCount = garbageCollectorMXBean.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
        boolean gc = this.gcCount != null && this.gcCount < gcCount;
        this.gcCount = gcCount;
        return new BuildData.Metric(
            fromStart(Instant.now()),
            activeTasks.get(),
            megabytes(heapUsedBytes),
            megabytes(heapCommittedBytes),
            gc,
            cpuPercent(processCpuLoad),
            cpuPercent(systemCpuLoad),
            threads + daemonThreads,
            BigDecimal.ZERO
        );
    }

    public List<BuildData.Metric> getMetrics() {
        synchronized (metrics) {
            active = false;
            List<BuildData.Metric> result = new ArrayList<>(metrics);
            BuildData.Metric lastMetric = scrapeMetrics();
            result.add(lastMetric);

            // shift "gc" one metric left as we register this after,
            // but visually it's more reasonable to be shown as before
            BuildData.Metric prevMetric = null;
            for (BuildData.Metric metric : result) {
                if (prevMetric != null) {
                    prevMetric.setGc(metric.isGc());
                }
                prevMetric = metric;
            }
            lastMetric.setGc(false);

            metrics.notify();
            return result;
        }
    }

    private BigDecimal fromStart(Instant time) {
        Duration duration = Duration.between(startTime, time);
        return TimeFormatUtils.toSeconds(duration);
    }

    private static BigDecimal megabytes(long bytes) {
        return BigDecimal.valueOf(bytes / 1024 / 1024);
    }

    private static BigDecimal cpuPercent(double cpuLoad) {
        double percent = cpuLoad < 0 ? 0d : cpuLoad * 100d;
        return BigDecimal.valueOf(percent).setScale(3, RoundingMode.HALF_UP);
    }

    public void start() {
        worker.start();
    }

    public void incActiveTasks() {
        activeTasks.incrementAndGet();
    }

    public void decActiveTasks() {
        activeTasks.decrementAndGet();
    }
}
