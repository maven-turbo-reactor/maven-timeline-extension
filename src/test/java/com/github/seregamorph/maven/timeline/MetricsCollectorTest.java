package com.github.seregamorph.maven.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetricsCollectorTest {

    @Test
    public void shouldBackFillContainersRunningBeforeFirstPoll() {
        List<BuildData.Metric> metrics = Arrays.asList(
            metric(null, null), metric(null, null), metric(2, 0), metric(4, 2));
        MetricsCollector.fillDockerContainers(metrics);
        assertEquals(2, metrics.get(0).getDockerContainers());
        assertEquals(0, metrics.get(0).getTestcontainersContainers());
        assertEquals(2, metrics.get(1).getDockerContainers());
        assertEquals(2, metrics.get(2).getDockerContainers());
        assertEquals(4, metrics.get(3).getDockerContainers());
        assertEquals(2, metrics.get(3).getTestcontainersContainers());
    }

    @Test
    public void shouldDropCountsWhenNoContainersSeen() {
        List<BuildData.Metric> metrics = Arrays.asList(metric(null, null), metric(0, 0), metric(0, 0));
        MetricsCollector.fillDockerContainers(metrics);
        metrics.forEach(metric -> {
            assertNull(metric.getDockerContainers());
            assertNull(metric.getTestcontainersContainers());
        });
    }

    @Test
    public void shouldKeepCountsAbsentWhenDockerNotTracked() {
        List<BuildData.Metric> metrics = Arrays.asList(metric(null, null), metric(null, null));
        MetricsCollector.fillDockerContainers(metrics);
        metrics.forEach(metric -> assertNull(metric.getDockerContainers()));
    }

    private static BuildData.Metric metric(Integer dockerContainers, Integer testcontainersContainers) {
        return new BuildData.Metric(BigDecimal.ONE, 1, BigDecimal.TEN, BigDecimal.TEN, false, BigDecimal.ONE,
            BigDecimal.ONE, 10, 5, BigDecimal.ZERO, BigDecimal.ZERO, dockerContainers, testcontainersContainers);
    }
}
