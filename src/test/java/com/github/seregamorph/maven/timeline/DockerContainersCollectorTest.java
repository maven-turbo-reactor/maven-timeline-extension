package com.github.seregamorph.maven.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class DockerContainersCollectorTest {

    @Test
    public void shouldParseEmptyOutput() {
        DockerContainersCollector.Snapshot snapshot = DockerContainersCollector.parse(Collections.emptyList());
        assertEquals(0, snapshot.total);
        assertEquals(0, snapshot.testcontainers);
    }

    @Test
    public void shouldDistinguishTestcontainers() {
        DockerContainersCollector.Snapshot snapshot = DockerContainersCollector.parse(Arrays.asList(
            // unrelated docker compose container
            "a1\tdevelop-otel-collector-1\tcom.docker.compose.project=develop,com.docker.compose.service=otel",
            // no labels at all
            "a2\tnginx\t",
            // label value containing "=" and a key only prefixed with "org.testcontainers"
            "a3\tother\tjdbc=url=x,org.testcontainersfoo=true",
            "",
            // Testcontainers DEFAULT_LABELS
            "b1\tadoring_euler\torg.testcontainers=true,org.testcontainers.lang=java,org.testcontainers.version=2.0.5",
            // only the session id label (JVM hook reaper)
            "b2\tsharp_hopper\tfoo=bar,org.testcontainers.sessionId=1234",
            // Ryuk, matched by name even without labels
            "b3\ttestcontainers-ryuk-1234\t"
        ));
        assertEquals(6, snapshot.total);
        assertEquals(3, snapshot.testcontainers);
    }
}
