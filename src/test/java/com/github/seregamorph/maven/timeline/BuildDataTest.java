package com.github.seregamorph.maven.timeline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BuildDataTest {

    @Test
    public void shouldSerializeFailedFlagOnlyForFailedGoal() {
        assertTrue(serializeGoal(true).contains("\"failed\""));
        // goals are the bulk of build-data.json and failures are rare, so the flag is omitted when false
        assertFalse(serializeGoal(false).contains("\"failed\""));
    }

    @Test
    public void shouldSerializeDockerContainersOnlyWhenTracked() {
        String untracked = serializeMetric(null, null);
        assertFalse(untracked.contains("\"dockerContainers\""));
        assertFalse(untracked.contains("\"testcontainersContainers\""));
        String tracked = serializeMetric(3, 2);
        assertTrue(tracked.contains("\"dockerContainers\" : 3"));
        assertTrue(tracked.contains("\"testcontainersContainers\" : 2"));
    }

    private static String serializeMetric(Integer dockerContainers, Integer testcontainersContainers) {
        BuildData.Metric metric = new BuildData.Metric(new BigDecimal("1.0"), 1, BigDecimal.TEN, BigDecimal.TEN,
            false, BigDecimal.ONE, BigDecimal.ONE, 10, 5, BigDecimal.ZERO, BigDecimal.ZERO,
            dockerContainers, testcontainersContainers);
        return new String(JsonSerializers.serialize(metric), StandardCharsets.UTF_8);
    }

    private static String serializeGoal(boolean failed) {
        BuildData.Goal goal = new BuildData.Goal("compiler:compile@default-compile", "compile", "compile",
            new BigDecimal("1.0"), new BigDecimal("2.0"), new BigDecimal("0.1"), new BigDecimal("0.9"), failed);
        return new String(JsonSerializers.serialize(goal), StandardCharsets.UTF_8);
    }
}