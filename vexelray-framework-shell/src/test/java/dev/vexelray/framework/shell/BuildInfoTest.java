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
}
