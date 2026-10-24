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

    /**
     * Longer than any real build has made yet: a long name, long coordinates, a long version, uncommitted changes. The
     * window shipped with a sideways scrollbar from "+ uncommitted changes" alone; laid out here with real fonts at its
     * opening size and narrower, nothing in it may scroll sideways (docs/reference/scrolling.md §5, in vexelray-gui).
     */
    @Test
    void nothingTheBillSaysCanMakeTheWindowScrollSideways() {
        BuildInfo worst = new BuildInfo("dev.example.a.rather.deep.group", "an-application-with-a-long-artifact-id",
                "12.345.6789-SNAPSHOT-with-a-qualifier", "0123456789abcdef0123456789abcdef01234567+dirty.0a1b2c3d4e5f",
                List.of("dev.vexelray.framework:vexelray-framework-shell:0.1.2-SNAPSHOT-with-a-qualifier@1e057d6"),
                "An Application Whose Proper Name Runs On For A While", "2026-10-24T10:00:00Z",
                "https://example.dev/NOTICES.txt",
                Map.of(BuildInfo.FRAMEWORK, "https://github.com/sibarum/vexelray-framework/issues"));
        // Its opening width and narrower, at zoom 1. Under the window's minimum the page is laid out at the minimum and
        // the window scrolls as a whole, which was asked for; nothing inside it may scroll sideways on its own. The
        // acceptance run adds a zoomed one.
        for (float[] size : new float[][] {{440f, 480f}, {300f, 480f}, {160f, 480f}}) {
            dev.vexelray.diag.Diagnostics.reset();
            try (dev.vexelray.gui.core.Gui g = new dev.vexelray.gui.core.Gui(sibarum.atchung.Atchung.create())) {
                dev.vexelray.gui.core.Node mark = g.box().size(About.MARK, About.MARK);
                About.minimum(g);
                About.Page page = About.content(g, About.name(INFO, worst), worst, mark);
                g.root().children(page.body(), page.foot());
                g.frame(size[0], size[1], dev.vexelray.gui.core.text.TextFaces.standard());
                List<String> unasked = dev.vexelray.diag.Diagnostics.recorded().stream()
                        .filter(m -> m.startsWith("a horizontal scrollbar nobody asked for")).toList();
                assertEquals(List.of(), unasked, "at " + (int) size[0] + "x" + (int) size[1]);
            }
        }
    }

    @Test
    void theNoticesAreTheAddressWhenNoneLieBesideTheExecutable() {
        // A test runs on java, and the JDK keeps no notices of ours beside it.
        assertEquals("https://example.dev/NOTICES.txt", About.notices("https://example.dev/NOTICES.txt"));
    }
}
