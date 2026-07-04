package com.github.seregamorph.maven.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TimelineHelperTest {

    @Test
    public void shouldGetPluginName() {
        assertEquals("compile", TimelineHelper.getPluginName("maven-compile-plugin"));
        assertEquals("kotlin", TimelineHelper.getPluginName("kotlin-maven-plugin"));
    }

    @Test
    public void shouldClassifyGoalType() {
        assertEquals("compile", TimelineHelper.goalType("compile"));
        assertEquals("test-compile", TimelineHelper.goalType("test-compile"));
        assertEquals("test", TimelineHelper.goalType("test"));
        assertEquals("test", TimelineHelper.goalType("integration-test"));
        assertEquals("deploy", TimelineHelper.goalType("deploy"));

        assertEquals("other", TimelineHelper.goalType("package"));
        assertEquals("other", TimelineHelper.goalType("install"));
        // failsafe "verify" is not a test execution
        assertEquals("other", TimelineHelper.goalType("verify"));
        // directly invoked goals have no bound phase
        assertEquals("other", TimelineHelper.goalType(null));
    }
}
