package dev.vexelray.framework.shell;

import dev.vexelray.os.Icon;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the annotation compiles to.
 *
 * <p>No GPU and no window: an {@code AppInfo} is a record of facts, which is the whole reason the framework
 * reads one instead of an annotation.
 */
final class AppInfoTest {

    /** One 1×1 pixel is enough: nothing here looks at the artwork, only at whether it travels. */
    private static Icon mark() {
        return new Icon(List.of(new Icon.Image(1, 1, new int[] {0xFF00FF00})));
    }

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
    void noSettingsMeansAnEmptySetAndNoMark() {
        AppInfo info = new AppInfo("demo", "Demo", 800, 600);
        assertTrue(info.settingKeys().isEmpty());
        assertNull(info.icon());
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

    /**
     * The mark is added afterwards rather than passed in, so that an application's {@code INFO} can be a
     * constant without decoding six PNGs in a static initialiser it cannot report a failure from.
     */
    @Test
    void withIconKeepsEverythingElse() {
        AppInfo bare = new AppInfo("demo", "Demo", 800, 600, Set.of("theme"));
        Icon icon = mark();
        AppInfo worn = bare.withIcon(icon);

        assertSame(icon, worn.icon());
        assertEquals(bare.name(), worn.name());
        assertEquals(bare.title(), worn.title());
        assertEquals(bare.width(), worn.width());
        assertEquals(bare.height(), worn.height());
        assertEquals(bare.settingKeys(), worn.settingKeys());
        assertNull(bare.icon(), "withIcon returns a new record rather than mutating one");
    }

    /** No mark is a state and not a failure: the framework skips both of its calls and the window is still a
     *  window, wearing the OS default. */
    @Test
    void aMarkCanBeTakenBackOff() {
        assertNull(new AppInfo("demo", "Demo", 800, 600).withIcon(mark()).withIcon(null).icon());
    }
}
