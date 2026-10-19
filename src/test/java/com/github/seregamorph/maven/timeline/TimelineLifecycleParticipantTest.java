package com.github.seregamorph.maven.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TimelineLifecycleParticipantTest {

    private static final String D3 = new String(ClasspathResources.readBytes("static/d3.min.js"),
        StandardCharsets.UTF_8);

    @Test
    public void shouldRenderSelfContainedReport() {
        String json = "{\"meta\" : {\"project\" : \"__TIMELINE_D3_JS__ $1 \\\\\"}}";
        String html = TimelineLifecycleParticipant.renderReport(json);

        assertTrue(html.contains("<script>" + D3 + "</script>"));
        assertTrue(html.contains(">" + json + "</script>"));
        assertEquals(1, countOccurrences(html, D3));
        assertFalse(html.contains("src=\"http"));
        assertFalse(html.contains("__TIMELINE_BUILD_DATA__"));
    }

    @Test
    public void shouldKeepBuildDataPlaceholderForJsonReport() {
        String html = TimelineLifecycleParticipant.renderReport(null);

        assertTrue(html.contains("<script>" + D3 + "</script>"));
        assertTrue(html.contains(">__TIMELINE_BUILD_DATA__</script>"));
        assertFalse(html.contains("src=\"http"));
        assertFalse(html.contains("__TIMELINE_D3_JS__"));
    }

    @Test
    public void shouldRejectScriptThatBreaksOutOfScriptElement() {
        assertEquals("var a = 1 < 2;", TimelineLifecycleParticipant.requireInlineable("var a = 1 < 2;", "a.js"));
        assertThrows(IllegalStateException.class,
            () -> TimelineLifecycleParticipant.requireInlineable("var s = '</SCRIPT>';", "a.js"));
        assertThrows(IllegalStateException.class,
            () -> TimelineLifecycleParticipant.requireInlineable("var s = '<script>';", "a.js"));
        assertThrows(IllegalStateException.class,
            () -> TimelineLifecycleParticipant.requireInlineable("var s = '<!--';", "a.js"));
    }

    private static int countOccurrences(String text, String part) {
        int count = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            count++;
        }
        return count;
    }
}
