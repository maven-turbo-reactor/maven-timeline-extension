package com.github.seregamorph.maven.timeline;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically counts the running docker containers via the {@code docker ps} CLI, distinguishing the ones
 * created by Testcontainers. Polling runs on its own daemon thread, as a process spawn is too slow for the
 * {@link MetricsCollector} cycle, which only reads the latest {@link #getSnapshot() snapshot}.
 *
 * @author Sergey Chernov
 */
public class DockerContainersCollector {

    private static final Logger LOGGER = LoggerFactory.getLogger(DockerContainersCollector.class);

    private static final long CYCLE_INTERVAL_MS = 1000L;
    private static final long PROCESS_TIMEOUT_MS = 5000L;

    // Testcontainers puts DockerClientFactory.DEFAULT_LABELS ("org.testcontainers=true",
    // "org.testcontainers.lang", "org.testcontainers.version") on every container it creates, including Ryuk
    // (which also gets "org.testcontainers.ryuk=true", but no session id); the containers cleaned up by Ryuk
    // additionally get "org.testcontainers.sessionId"
    private static final String TESTCONTAINERS_LABEL = "org.testcontainers";
    // fallback for Ryuk, which is always named "testcontainers-ryuk-${sessionId}"
    private static final String RYUK_NAME_PREFIX = "testcontainers-ryuk-";

    private static final File NULL_FILE = new File(
        System.getProperty("os.name", "").startsWith("Windows") ? "NUL" : "/dev/null");

    private final Thread worker;

    @Nullable
    private volatile Snapshot snapshot = null;
    private volatile boolean active = true;

    public DockerContainersCollector() {
        worker = new Thread(this::run, "timeline-docker");
        worker.setDaemon(true);
    }

    private void run() {
        boolean first = true;
        while (active) {
            try {
                snapshot = poll();
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                if (first) {
                    // docker CLI is missing or the daemon is not running - give up for this build
                    LOGGER.debug("Docker containers are not tracked: {}", e.toString());
                    return;
                }
                LOGGER.debug("Error while listing docker containers: {}", e.toString());
            }
            first = false;
            try {
                Thread.sleep(CYCLE_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static Snapshot poll() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("docker", "ps", "--no-trunc",
            "--format", "{{.ID}}\t{{.Names}}\t{{.Labels}}")
            // ProcessBuilder.Redirect.DISCARD is Java 9+
            .redirectError(NULL_FILE)
            .start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } finally {
            if (!process.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        }
        if (process.isAlive()) {
            throw new IOException("docker ps timed out");
        }
        if (process.exitValue() != 0) {
            throw new IOException("docker ps exited with " + process.exitValue());
        }
        return parse(lines);
    }

    /**
     * Parses {@code docker ps} lines formatted as {@code ID<tab>Names<tab>Labels}, where labels are
     * comma-separated {@code key=value} pairs.
     */
    static Snapshot parse(List<String> lines) {
        int total = 0;
        int testcontainers = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            total++;
            String[] columns = line.split("\t", -1);
            String names = columns.length > 1 ? columns[1] : "";
            String labels = columns.length > 2 ? columns[2] : "";
            if (isTestcontainers(names, labels)) {
                testcontainers++;
            }
        }
        return new Snapshot(total, testcontainers);
    }

    private static boolean isTestcontainers(String names, String labels) {
        for (String name : names.split(",")) {
            if (name.startsWith(RYUK_NAME_PREFIX)) {
                return true;
            }
        }
        for (String label : labels.split(",")) {
            int eq = label.indexOf('=');
            String key = eq < 0 ? label : label.substring(0, eq);
            if (key.equals(TESTCONTAINERS_LABEL) || key.startsWith(TESTCONTAINERS_LABEL + ".")) {
                return true;
            }
        }
        return false;
    }

    public void start() {
        worker.start();
    }

    public void stop() {
        active = false;
        worker.interrupt();
    }

    /**
     * Latest container counts, null until the first successful poll or if docker is not available.
     */
    @Nullable
    public Snapshot getSnapshot() {
        return snapshot;
    }

    static final class Snapshot {

        final int total;
        final int testcontainers;

        Snapshot(int total, int testcontainers) {
            this.total = total;
            this.testcontainers = testcontainers;
        }
    }
}
