package com.github.seregamorph.maven.timeline;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Singleton;

/**
 * Thread-safe recorder of artifact resolver I/O (bytes downloaded from and uploaded
 * to remote repositories), fed by {@link TimelineEventSpy} and consumed by
 * {@link MetricsCollector}.
 *
 * <p>Rather than accumulating a single running byte counter, each transfer is recorded
 * as an interval {@code [start, finish]} with the number of bytes transferred. This lets
 * {@link MetricsCollector} spread the throughput of a transfer over its actual duration
 * instead of attributing the whole payload to the single sampling window in which the
 * transfer happened to complete (which would render as a misleading momentary spike).
 *
 * @author Sergey Chernov
 */
@Singleton
public class ResolverIoStats {

    /**
     * A completed transfer: {@code bytes} moved over {@code [start, finish]}.
     */
    static final class Transfer {

        final Instant start;
        final Instant finish;
        final long bytes;
        final boolean upload;

        Transfer(Instant start, Instant finish, long bytes, boolean upload) {
            this.start = start;
            this.finish = finish;
            this.bytes = bytes;
            this.upload = upload;
        }
    }

    // transfers in progress, keyed by a caller-provided identity (artifact/metadata + direction)
    private final Map<String, Instant> inFlight = new ConcurrentHashMap<>();
    private final List<Transfer> transfers = Collections.synchronizedList(new ArrayList<>());

    void reset() {
        inFlight.clear();
        transfers.clear();
    }

    /**
     * Marks the start of a transfer identified by {@code key}. Overlapping starts with the
     * same key keep the earliest timestamp.
     */
    void startTransfer(String key) {
        inFlight.putIfAbsent(key, Instant.now());
    }

    /**
     * Marks the completion of the transfer identified by {@code key}, recording {@code bytes}
     * moved over the interval since {@link #startTransfer}. If no matching start was seen the
     * transfer is recorded as instantaneous.
     */
    void finishTransfer(String key, long bytes, boolean upload) {
        Instant start = inFlight.remove(key);
        if (bytes <= 0) {
            return;
        }
        Instant finish = Instant.now();
        transfers.add(new Transfer(start == null ? finish : start, finish, bytes, upload));
    }

    List<Transfer> getTransfers() {
        synchronized (transfers) {
            return new ArrayList<>(transfers);
        }
    }
}