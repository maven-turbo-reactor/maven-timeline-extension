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
        assertEquals("compile", TimelineHelper.goalType("maven-compiler-plugin", "compile"));
        assertEquals("test-compile", TimelineHelper.goalType("maven-compiler-plugin", "testCompile"));
        assertEquals("compile", TimelineHelper.goalType("kotlin-maven-plugin", "compile"));
        assertEquals("test-compile", TimelineHelper.goalType("kotlin-maven-plugin", "test-compile"));
        assertEquals("test", TimelineHelper.goalType("maven-surefire-plugin", "test"));
        assertEquals("test", TimelineHelper.goalType("maven-failsafe-plugin", "integration-test"));
        assertEquals("deploy", TimelineHelper.goalType("maven-deploy-plugin", "deploy"));

        assertEquals("other", TimelineHelper.goalType("maven-jar-plugin", "jar"));
        assertEquals("other", TimelineHelper.goalType("maven-install-plugin", "install"));
        // failsafe "verify" is not a test execution
        assertEquals("other", TimelineHelper.goalType("maven-failsafe-plugin", "verify"));
    }
}
