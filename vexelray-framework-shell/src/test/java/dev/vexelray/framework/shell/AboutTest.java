package dev.vexelray.framework.shell;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the About window says, from the bill and nothing else. */
class AboutTest {

    private static final AppInfo INFO = new AppInfo("pix", "Pix viewer", 800, 600);

    @Test
    void theNameIsTheBillsAndTheTitleOnlyWhenTheBillHasNone() {
        BuildInfo named = new BuildInfo("dev.sibarum", "pix", "0.1", "c", List.of(), "Pix", "", "", Map.of());
        assertEquals("Pix", About.name(INFO, named));
        assertEquals("Pix viewer", About.name(INFO, BuildInfo.UNKNOWN));
    }

    @Test
    void theRowsSayWhatTheBillKnowsAndUnknownForTheRest() {
        BuildInfo b = new BuildInfo("dev.sibarum", "pix", "0.1.2", "0123456789abcdef0123+dirty.0a1b2c3d4e5f",
                List.of(), "Pix", "2026-10-24T10:00:00Z", "", Map.of());
        Map<String, String> facts = About.facts(b);
        assertEquals(List.of("Version", "Build", "Built"), List.copyOf(facts.keySet()));
        assertEquals("0.1.2", facts.get("Version"));
        assertEquals("0123456789ab + uncommitted changes", facts.get("Build"), "the short commit, and that it was not all");
        assertTrue(facts.get("Built").startsWith("2026-10-2"), facts.get("Built") + ": in the local zone, so the date may move");

        Map<String, String> unknown = About.facts(BuildInfo.UNKNOWN);
        assertEquals(Map.of("Version", "unknown", "Build", "unknown", "Built", "unknown"), unknown);
    }

    @Test
    void theFrameworksVersionIsItsLineInTheStack() {
        BuildInfo b = new BuildInfo("dev.sibarum", "pix", "0.1.2", "c", List.of(
                "dev.vexelray.gui:vexelray-gui-core:0.1.2-SNAPSHOT@1e057d6",
                "dev.vexelray.framework:vexelray-framework-shell:0.1.2-SNAPSHOT@4612f44"));
        assertEquals("0.1.2-SNAPSHOT", About.frameworkVersion(b));
        assertNull(About.frameworkVersion(BuildInfo.UNKNOWN), "not stamped, not said");
    }

    @Test
    void theNoticesAreTheAddressWhenNoneLieBesideTheExecutable() {
        // A test runs on java, and the JDK keeps no notices of ours beside it.
        assertEquals("https://example.dev/NOTICES.txt", About.notices("https://example.dev/NOTICES.txt"));
    }
}
