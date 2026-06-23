package com.github.seregamorph.maven.timeline;

import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Thread-safe cumulative counters of artifact resolver I/O (bytes downloaded from
 * and uploaded to remote repositories), fed by {@link TimelineEventSpy} and sampled
 * periodically by {@link MetricsCollector}.
 *
 * @author Sergey Chernov
 */
@Singleton
public class ResolverIoStats {

    private final AtomicLong downloadedBytes = new AtomicLong();
    private final AtomicLong uploadedBytes = new AtomicLong();

    void reset() {
        downloadedBytes.set(0L);
        uploadedBytes.set(0L);
    }

    void addDownloaded(long bytes) {
        if (bytes > 0) {
            downloadedBytes.addAndGet(bytes);
        }
    }

    void addUploaded(long bytes) {
        if (bytes > 0) {
            uploadedBytes.addAndGet(bytes);
        }
    }

    long getDownloadedBytes() {
        return downloadedBytes.get();
    }

    long getUploadedBytes() {
        return uploadedBytes.get();
    }
}
