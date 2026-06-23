package com.github.seregamorph.maven.timeline;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Sergey Chernov
 */
public class MetricsCollector {

    private static final long CYCLE_INTERVAL_MS = 1_000L;

    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicInteger activeTasks = new AtomicInteger(0);

    private final Thread worker;

    private final List<BuildData.Metric> metrics = new ArrayList<>();

    public MetricsCollector(Instant startTime) {
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                while (active.get()) {
                    int threads = threadMXBean.getThreadCount();
                    int daemonThreads = threadMXBean.getDaemonThreadCount();
                    long heapUsedBytes = memoryMXBean.getHeapMemoryUsage().getUsed();
                    long heapCommittedBytes = memoryMXBean.getHeapMemoryUsage().getCommitted();
                    BuildData.Metric metric = new BuildData.Metric(
                        fromStart(Instant.now()),
                        activeTasks.get(),
                        megabytes(heapUsedBytes),
                        megabytes(heapCommittedBytes),
                        false,
                        BigDecimal.ZERO,
                        threads + daemonThreads,
                        BigDecimal.ZERO
                    );
                    synchronized (metrics) {
                        metrics.add(metric);
                    }
                    try {
                        Thread.sleep(CYCLE_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }

            private BigDecimal fromStart(Instant time) {
                Duration duration = Duration.between(startTime, time);
                return TimeFormatUtils.toSeconds(duration);
            }
        });
    }

    public List<BuildData.Metric> getMetrics() {
        synchronized (metrics) {
            return new ArrayList<>(metrics);
        }
    }

    private static BigDecimal megabytes(long bytes) {
        return BigDecimal.valueOf(bytes / 1024 / 1024);
    }

    public void start() {
        worker.start();
    }

    public void interrupt() {
        active.set(false);
        worker.interrupt();
    }

    public void incActiveTasks() {
        activeTasks.incrementAndGet();
    }

    public void decActiveTasks() {
        activeTasks.decrementAndGet();
    }
}
