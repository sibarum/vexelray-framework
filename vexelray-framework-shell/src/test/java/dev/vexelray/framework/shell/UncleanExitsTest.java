package dev.vexelray.framework.shell;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A run that does not reach its close leaves a marker, and the next start reads it as a run that ended badly, once. */
class UncleanExitsTest {

    private static final Instant T0 = Instant.parse("2026-11-10T09:00:00Z");
    private static final UncleanExits.Running NOTHING_RUNS = (pid, started) -> false;

    @Test
    void aRunThatClosedLeavesNothingToOffer(@TempDir Path dir) {
        UncleanExits.begin(dir, "app", 10, T0, NOTHING_RUNS).closed();
        assertTrue(UncleanExits.begin(dir, "app", 11, T0, NOTHING_RUNS).ended().isEmpty());
    }

    @Test
    void aRunThatDidNotCloseIsOfferedOnceAtTheNextStart(@TempDir Path dir) {
        UncleanExits.begin(dir, "app", 10, T0, NOTHING_RUNS);   // crashed: never closed
        UncleanExits next = UncleanExits.begin(dir, "app", 11, T0.plusSeconds(60), NOTHING_RUNS);
        assertEquals(List.of(new UncleanExits.Ended(10, T0)), next.ended());
        next.closed();
        assertTrue(UncleanExits.begin(dir, "app", 12, T0, NOTHING_RUNS).ended().isEmpty(),
                "claimed by the start that offered it, so the one after does not offer it again");
    }

    @Test
    void aSecondWindowStillRunningIsNotACrash(@TempDir Path dir) {
        UncleanExits.begin(dir, "app", 10, T0, NOTHING_RUNS);
        UncleanExits.Running tenRuns = (pid, started) -> pid == 10 && started.equals(T0);
        assertTrue(UncleanExits.begin(dir, "app", 11, T0, tenRuns).ended().isEmpty());
        assertTrue(Files.exists(dir.resolve("app-running-10.marker")), "left for its own process to remove");
    }

    @Test
    void anotherApplicationsMarkersAreNotThisOnes(@TempDir Path dir) {
        UncleanExits.begin(dir, "other", 10, T0, NOTHING_RUNS);
        assertTrue(UncleanExits.begin(dir, "app", 11, T0, NOTHING_RUNS).ended().isEmpty());
    }

    @Test
    void aFileThatIsNotAMarkerIsLeftAlone(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("app-running-x.marker"), "not a pid");
        Files.writeString(dir.resolve("app-running-12.marker"), "");   // being written by a start right now
        assertTrue(UncleanExits.begin(dir, "app", 11, T0, NOTHING_RUNS).ended().isEmpty());
        assertTrue(Files.exists(dir.resolve("app-running-12.marker")));
    }

    @Test
    void severalAreOfferedOldestFirst(@TempDir Path dir) throws Exception {
        // Two windows that crashed together; a begin each would have had the second claim the first.
        Files.writeString(dir.resolve("app-running-20.marker"), Long.toString(T0.plusSeconds(5).toEpochMilli()));
        Files.writeString(dir.resolve("app-running-10.marker"), Long.toString(T0.toEpochMilli()));
        List<UncleanExits.Ended> ended = UncleanExits.begin(dir, "app", 30, T0, NOTHING_RUNS).ended();
        assertEquals(List.of(10L, 20L), ended.stream().map(UncleanExits.Ended::pid).toList());
    }

    @Test
    void theLogDirectoryIsMadeWhenItIsNotThereYet(@TempDir Path dir) {
        Path logs = dir.resolve("not-yet");
        UncleanExits mine = UncleanExits.begin(logs, "app", 10, T0, NOTHING_RUNS);
        assertTrue(Files.exists(logs.resolve("app-running-10.marker")));
        mine.closed();
        assertFalse(Files.exists(logs.resolve("app-running-10.marker")));
    }
}
