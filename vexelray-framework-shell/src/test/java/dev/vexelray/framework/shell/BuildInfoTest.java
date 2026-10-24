package dev.vexelray.framework.shell;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What an application was built from, as the automation socket's discovery file and hello carry it. */
class BuildInfoTest {

    @Test
    void factsNameTheBuildAndEveryStampedPartInOrder() {
        BuildInfo b = new BuildInfo("dev.example", "demo", "0.1.0-SNAPSHOT", "ef816d9a+dirty.0a1b2c3d4e5f",
                List.of("dev.vexelray.gui:vexelray-gui-core:0.1.1-SNAPSHOT@1e057d6",
                        "sibarum.atchung:atchung-probe:1.1-SNAPSHOT@4612f44"));
        assertTrue(b.known());
        assertEquals("dev.example:demo:0.1.0-SNAPSHOT@ef816d9a+dirty.0a1b2c3d4e5f", b.line());
        assertEquals(Map.of(
                "app.group", "dev.example",
                "app.artifact", "demo",
                "app.version", "0.1.0-SNAPSHOT",
                "app.source", "ef816d9a+dirty.0a1b2c3d4e5f",
                "stack.count", "2",
                "stack.1", "dev.vexelray.gui:vexelray-gui-core:0.1.1-SNAPSHOT@1e057d6",
                "stack.2", "sibarum.atchung:atchung-probe:1.1-SNAPSHOT@4612f44"), b.facts());
        assertEquals(List.of("app.group", "app.artifact", "app.version", "app.source", "stack.count", "stack.1",
                "stack.2"), List.copyOf(b.facts().keySet()), "in the order the file is written");
    }

    @Test
    void anApplicationBuiltWithoutThePluginSaysItsSourceIsUnknown() {
        assertFalse(BuildInfo.UNKNOWN.known());
        assertEquals("unknown", BuildInfo.UNKNOWN.facts().get("app.source"));
        assertEquals("0", BuildInfo.UNKNOWN.facts().get("stack.count"));
    }

    /** What the About window reads off a bill: the parts of the source, the time, and the framework's tracker. */
    @Test
    void theAboutWindowsFactsAreReadOffTheBill() {
        BuildInfo b = new BuildInfo("dev.sibarum", "pix", "0.1.2-SNAPSHOT", "ef816d9a0123456789+dirty.0a1b2c3d4e5f",
                List.of("dev.vexelray.framework:vexelray-framework-shell:0.1.2-SNAPSHOT@1e057d6"), "Pix",
                "2026-10-24T10:00:00Z", "https://example.dev/NOTICES.txt",
                Map.of(BuildInfo.FRAMEWORK, "https://github.com/sibarum/vexelray-framework/issues"));
        assertEquals("dev.sibarum.pix", b.coordinates());
        assertEquals("ef816d9a0123456789", b.commit());
        assertTrue(b.dirty());
        assertEquals(java.time.Instant.parse("2026-10-24T10:00:00Z"), b.builtAt().orElseThrow());
        assertEquals("https://github.com/sibarum/vexelray-framework/issues", b.frameworkIssues().orElseThrow());

        BuildInfo old = new BuildInfo("dev.sibarum", "pix", "0.1.2-SNAPSHOT", "ef816d9a", List.of());
        assertFalse(old.dirty());
        assertTrue(old.builtAt().isEmpty(), "a bill from before the plugin wrote the time has none");
        assertTrue(old.frameworkIssues().isEmpty());
        assertEquals("", old.name());
        assertEquals("-", BuildInfo.UNKNOWN.coordinates());
    }
}
