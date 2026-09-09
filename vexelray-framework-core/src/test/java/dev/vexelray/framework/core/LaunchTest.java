package dev.vexelray.framework.core;

import dev.vexelray.framework.api.RunMode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The command line each application currently parses for itself, parsed once. */
final class LaunchTest {

    private static final Set<String> KEYS = Set.of("theme", "zoom");

    private static Launch parse(String... argv) {
        return Launch.parse(argv, "demo", KEYS);
    }

    @Test
    void noArgumentsIsASession() {
        Launch launch = parse();
        assertEquals(RunMode.WINDOWED, launch.mode());
        assertEquals(0, launch.frames());
    }

    @Test
    void aBareCountIsAFixedFrameRun() {
        Launch launch = parse("240");
        assertEquals(RunMode.FRAMES, launch.mode());
        assertEquals(240, launch.frames());
    }

    @Test
    void zeroFramesIsASessionBecauseThatIsWhatRunZeroMeans() {
        Launch launch = parse("0");
        assertEquals(RunMode.WINDOWED, launch.mode());
        assertEquals(0, launch.frames());
    }

    /**
     * The framework's one-frame capture mode is gone — it photographed the chrome correctly and the content
     * silently wrongly. So {@code --capture} is now just an unknown flag, and an application with its own
     * richer capture tooling is expected to intercept it before handing the rest here. Getting a clear
     * "unknown option" is the right outcome for one that forgets.
     */
    @Test
    void captureIsNoLongerAFrameworkFlag() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> parse("--capture"));
        assertTrue(e.getMessage().contains("--capture"), e.getMessage());
    }

    @Test
    void aBareFlagIsTheValueTrue() {
        assertTrue(parse("--profile").flag("profile"));
        assertFalse(parse().flag("profile"));
    }

    @Test
    void settingsTakeAValue() {
        assertEquals("7000", parse("--automation=7000").override("automation"));
        assertEquals("dark", parse("--theme=dark").override("theme"));
    }

    /**
     * The typo the scaffold documents throwing {@code NumberFormatException} over. It is named, and the
     * alternatives are listed — a list the processor generates, so it cannot fall out of date.
     */
    @Test
    void anUnknownOptionIsRefusedByNameAndListsTheKnownOnes() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> parse("--verbse"));
        assertTrue(e.getMessage().contains("--verbse"), e.getMessage());
        assertTrue(e.getMessage().contains("theme"), e.getMessage());
        assertTrue(e.getMessage().contains("profile"), e.getMessage());
    }

    @Test
    void aSingleDashIsRefusedToo() {
        assertThrows(IllegalArgumentException.class, () -> parse("-x"));
    }

    @Test
    void emptyOptionNameIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> parse("--"));
    }

    /** An IDE run configuration or a shell expanding an empty variable produces these, and never meant one. */
    @Test
    void blankArgumentsAreIgnored() {
        Launch launch = parse("", "  ", "240");
        assertEquals(RunMode.FRAMES, launch.mode());
        assertEquals(240, launch.frames());
    }

    @Test
    void positionalArgumentsSurviveAsRest() {
        assertEquals(java.util.List.of("notes.txt"), parse("notes.txt").rest());
    }

    @Test
    void usageNamesEveryKnownSetting() {
        String usage = Launch.usage("demo", KEYS);
        assertTrue(usage.contains("demo"), usage);
        assertTrue(usage.contains("theme"), usage);
        assertFalse(usage.contains("--capture"), usage);
        assertTrue(usage.contains("automation"), usage);
    }

    /**
     * The application's keys and the framework's reserved ones are different promises — one is read by this
     * application's own code, the other by whichever module happens to be linked in — so the usage text does
     * not present them as one list. See {@code Launch.FRAMEWORK_KEYS} for why that gap is open at all.
     */
    @Test
    void usageKeepsTheFrameworksReservedKeysOnTheirOwnLine() {
        String[] lines = Launch.usage("demo", KEYS).split("\\R");

        assertEquals(3, lines.length, Launch.usage("demo", KEYS));
        assertEquals("settings: theme, zoom", lines[1]);
        assertEquals("framework: automation, profile", lines[2]);
    }

    /** An application with no settings of its own still gets told about the reserved keys, and gets no blank
     *  "settings:" line inviting it to look for some. */
    @Test
    void anApplicationWithNoSettingsGetsNoEmptySettingsLine() {
        String[] lines = Launch.usage("demo", Set.of()).split("\\R");

        assertEquals(2, lines.length, Launch.usage("demo", Set.of()));
        assertEquals("framework: automation, profile", lines[1]);
    }
}
