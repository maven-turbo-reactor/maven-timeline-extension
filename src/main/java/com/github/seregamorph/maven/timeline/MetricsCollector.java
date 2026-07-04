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

    private static final long CYCLE_INTERVAL_MS = 250L;

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

    // last valid CPU readings, carried forward whenever the bean reports a negative
    // @GuardedBy("metrics")
    private double lastSystemCpuLoad = 0d;

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
        double processCpuLoad = operatingSystemMXBean.getProcessCpuLoad();
        double systemCpuLoad = operatingSystemMXBean.getSystemCpuLoad();
        // recent CPU usage as a fraction [0.0, 1.0]; both return a negative value when
        // not available (the first sample, but also intermittently mid-run — especially
        // the system-wide reading on macOS / in containers). Carry the last valid value
        // forward in that case, otherwise a momentary gap would read as an impossible 0%.
        systemCpuLoad = lastSystemCpuLoad = systemCpuLoad <= 0 ? lastSystemCpuLoad : systemCpuLoad;
        long gcCount = garbageCollectorMXBean.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
        boolean gc = this.gcCount != null && this.gcCount < gcCount;
        this.gcCount = gcCount;
        // resolver I/O rates are filled in as a post-processing step (see fillResolverRates),
        // since a transfer's throughput must be spread across the sampling windows it spans -
        // including ones already emitted before the transfer completed
        return new BuildData.Metric(
            fromStart(now),
            activeTasks.get(),
            megabytes(heapUsedBytes),
            megabytes(heapCommittedBytes),
            gc,
            cpuPercent(processCpuLoad),
            cpuPercent(systemCpuLoad),
            threads + daemonThreads,
            BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP),
            BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP)
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

            fillResolverRates(result, resolverIoStats.getTransfers());

            metrics.notify();
            return result;
        }
    }

    /**
     * Fills the resolver download/upload MB/s of each metric sample by spreading every
     * recorded transfer's bytes uniformly across its {@code [start, finish]} interval. A
     * sample at time {@code t[i]} covers the window {@code (t[i-1], t[i]]}; a transfer
     * contributes to that window in proportion to how much of its duration overlaps it, so
     * a long download reads as a sustained plateau at its average throughput rather than a
     * single momentary spike in the window where it completed.
     */
    private void fillResolverRates(List<BuildData.Metric> metrics, List<ResolverIoStats.Transfer> transfers) {
        int n = metrics.size();
        // window boundaries in seconds since build start (t[0] is the first sample)
        double[] times = new double[n];
        for (int i = 0; i < n; i++) {
            times[i] = metrics.get(i).getT().doubleValue();
        }
        double[] downloadBytes = new double[n];
        double[] uploadBytes = new double[n];
        for (ResolverIoStats.Transfer transfer : transfers) {
            double start = fromStart(transfer.start).doubleValue();
            double finish = fromStart(transfer.finish).doubleValue();
            double[] bucket = transfer.upload ? uploadBytes : downloadBytes;
            distribute(times, bucket, start, finish, transfer.bytes);
        }
        for (int i = 0; i < n; i++) {
            double windowSeconds = i == 0 ? 0d : times[i] - times[i - 1];
            metrics.get(i).setResolverDownload(megabytesPerSecond(downloadBytes[i], windowSeconds));
            metrics.get(i).setResolverUpload(megabytesPerSecond(uploadBytes[i], windowSeconds));
        }
    }

    /**
     * Distributes {@code bytes} transferred over {@code [start, finish]} into the sample
     * windows {@code (times[i-1], times[i]]}, proportionally to the overlap of each window
     * with the transfer interval.
     */
    private static void distribute(double[] times, double[] bucket, double start, double finish, long bytes) {
        int n = times.length;
        double duration = finish - start;
        if (duration <= 1e-6) {
            // instantaneous (or missing start): attribute everything to the window containing finish
            for (int i = 1; i < n; i++) {
                if (finish <= times[i] || i == n - 1) {
                    bucket[i] += bytes;
                    return;
                }
            }
            return;
        }
        double bytesPerSecond = bytes / duration;
        for (int i = 1; i < n; i++) {
            double overlap = Math.min(finish, times[i]) - Math.max(start, times[i - 1]);
            if (overlap > 0d) {
                bucket[i] += bytesPerSecond * overlap;
            }
        }
    }

    private BigDecimal fromStart(Instant time) {
        Duration duration = Duration.between(startTime, time);
        return TimeFormatUtils.toSeconds(duration);
    }

    private static BigDecimal megabytes(long bytes) {
        return BigDecimal.valueOf(bytes / 1024 / 1024);
    }

    private static BigDecimal megabytesPerSecond(double bytes, double seconds) {
        if (bytes <= 0d || seconds <= 0d) {
            return BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
        }
        double megabytesPerSecond = bytes / (1024d * 1024d) / seconds;
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
