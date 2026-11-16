package dev.vexelray.framework.shell;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the annotation compiles to.
 *
 * <p>No GPU and no window: an {@code AppInfo} is a record of facts, which is the whole reason the framework
 * reads one instead of an annotation.
 */
final class AppInfoTest {

    /**
     * The name is the settings directory, so an application without one would lose every user's window
     * placement to a directory nobody can name. A compile error is not available here — the processor will
     * reject it — so this is the backstop for hand-written wiring.
     */
    @Test
    void aNameIsRequiredBecauseItIsTheSettingsDirectory() {
        assertThrows(IllegalArgumentException.class, () -> new AppInfo("", "Demo", 800, 600));
        assertThrows(IllegalArgumentException.class, () -> new AppInfo(null, "Demo", 800, 600));
        assertThrows(IllegalArgumentException.class, () -> new AppInfo("  ", "Demo", 800, 600));
    }

    /** An application that declares no settings gets an empty set rather than a null to test for. */
    @Test
    void noSettingsMeansAnEmptySet() {
        AppInfo info = new AppInfo("demo", "Demo", 800, 600);
        assertTrue(info.settingKeys().isEmpty());
    }

    /**
     * The keys are copied, not held. They reach {@code Launch} as the list an unknown flag is refused against,
     * and a caller that could still add to it afterwards could make a flag legal after the fact.
     */
    @Test
    void theSettingKeysAreCopied() {
        Set<String> mutable = new HashSet<>(Set.of("theme"));
        AppInfo info = new AppInfo("demo", "Demo", 800, 600, mutable);
        mutable.add("smuggled");

        assertEquals(Set.of("theme"), info.settingKeys());
        assertThrows(UnsupportedOperationException.class, () -> info.settingKeys().add("also-smuggled"));
    }
}
