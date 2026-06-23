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

    private final ResolverIoStats resolverIoStats;

    private final Instant startTime;
    private final Thread worker;

    // @GuardedBy("metrics")
    private boolean active = true;

    // @GuardedBy("metrics")
    private Long gcCount = null;

    // @GuardedBy("metrics")
    private Instant lastSampleTime = null;
    // @GuardedBy("metrics")
    private long lastDownloadedBytes = 0L;
    // @GuardedBy("metrics")
    private long lastUploadedBytes = 0L;

    public MetricsCollector(ResolverIoStats resolverIoStats, Instant startTime) {
        this.startTime = startTime;
        this.resolverIoStats = resolverIoStats;
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
        Instant now = Instant.now();
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
        // resolver I/O rate (MB/s) over the actual elapsed interval since the previous sample
        long downloadedBytes = resolverIoStats.getDownloadedBytes();
        long uploadedBytes = resolverIoStats.getUploadedBytes();
        double elapsedSeconds = lastSampleTime == null
            ? 0d : Duration.between(lastSampleTime, now).toNanos() / 1_000_000_000d;
        BigDecimal resolverDownload = megabytesPerSecond(downloadedBytes - lastDownloadedBytes, elapsedSeconds);
        BigDecimal resolverUpload = megabytesPerSecond(uploadedBytes - lastUploadedBytes, elapsedSeconds);
        lastSampleTime = now;
        lastDownloadedBytes = downloadedBytes;
        lastUploadedBytes = uploadedBytes;
        return new BuildData.Metric(
            fromStart(now),
            activeTasks.get(),
            megabytes(heapUsedBytes),
            megabytes(heapCommittedBytes),
            gc,
            cpuPercent(processCpuLoad),
            cpuPercent(systemCpuLoad),
            threads + daemonThreads,
            resolverDownload,
            resolverUpload
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

    private static BigDecimal megabytesPerSecond(long deltaBytes, double seconds) {
        if (deltaBytes <= 0 || seconds <= 0d) {
            return BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
        }
        double megabytesPerSecond = deltaBytes / (1024d * 1024d) / seconds;
        return BigDecimal.valueOf(megabytesPerSecond).setScale(3, RoundingMode.HALF_UP);
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
