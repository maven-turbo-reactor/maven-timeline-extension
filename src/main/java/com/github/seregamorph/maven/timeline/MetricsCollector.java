package com.github.seregamorph.maven.timeline;

import com.sun.management.OperatingSystemMXBean;
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
    private final OperatingSystemMXBean operatingSystemMXBean =
        (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private final Instant startTime;
    private final Thread worker;

    private boolean active = true;

    public MetricsCollector(Instant startTime) {
        this.startTime = startTime;
        // first metric
        BuildData.Metric firstMetric = scrapeMetric();
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
                        BuildData.Metric metric = scrapeMetric();
                        metrics.add(metric);
                    }
                }
            }
        });
    }

    private BuildData.Metric scrapeMetric() {
        int threads = threadMXBean.getThreadCount();
        int daemonThreads = threadMXBean.getDaemonThreadCount();
        long heapUsedBytes = memoryMXBean.getHeapMemoryUsage().getUsed();
        long heapCommittedBytes = memoryMXBean.getHeapMemoryUsage().getCommitted();
        // recent CPU usage as a fraction [0.0, 1.0]; both return a negative
        // value when not yet available (e.g. first sample)
        double processCpuLoad = operatingSystemMXBean.getProcessCpuLoad();
        double systemCpuLoad = operatingSystemMXBean.getSystemCpuLoad();
        return new BuildData.Metric(
            fromStart(Instant.now()),
            activeTasks.get(),
            megabytes(heapUsedBytes),
            megabytes(heapCommittedBytes),
            false,
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
            BuildData.Metric lastMetric = scrapeMetric();
            result.add(lastMetric);
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
