package dev.vexelray.framework.core;

import dev.vexelray.framework.api.RunMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertNull(launch.captureOut());
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

    @Test
    void captureDefaultsItsOutputToTheApplicationName() {
        Launch launch = parse("--capture");
        assertEquals(RunMode.CAPTURE, launch.mode());
        assertEquals(Path.of("demo.png"), launch.captureOut());
    }

    @Test
    void captureTakesAFollowingPath() {
        assertEquals(Path.of("out.png"), parse("--capture", "out.png").captureOut());
    }

    /**
     * A frame count after {@code --capture} is not a filename. Capture renders exactly one frame, so the count
     * is already meaningless here; reading it as a path would create the file {@code 30}.
     */
    @Test
    void captureDoesNotTakeANumberAsItsPath() {
        Launch launch = parse("--capture", "30");
        assertEquals(RunMode.CAPTURE, launch.mode());
        assertEquals(Path.of("demo.png"), launch.captureOut());
        assertEquals(0, launch.frames());
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
        assertTrue(usage.contains("automation"), usage);
    }
}
