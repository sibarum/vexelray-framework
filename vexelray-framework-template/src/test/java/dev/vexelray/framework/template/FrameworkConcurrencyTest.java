package dev.vexelray.framework.template;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Threading rule T7.3, held: {@code synchronized}, {@code volatile}, {@code java.util.concurrent} and {@code Thread}
 * appear in the framework's own sources only in the classes that implement the model ({@code docs/threading.md} T7.3,
 * ROADMAP §3 <i>What is infrastructure, not an offence</i>, and <i>Done means</i> 1).
 *
 * <p>It reads the main sources of every framework module an application runs ({@code -api}, {@code -core},
 * {@code -shell}, {@code -automation}) through javac's parser ({@link ConcurrencyScan}), and fails naming file and line
 * for a construct in a class not on {@link #INFRASTRUCTURE}. It fails too for an entry whose class no longer has any, so
 * the list only shrinks, and only honestly. The processor is not read: it runs in javac, never in an application.
 *
 * <p><b>Here rather than in {@code -shell}</b> because this module builds with no sibling installed and no GPU, so the
 * check runs in the shortest build there is, and because this module already reads the shell's files by path
 * ({@code VexelDesktopTest}). The template's own check is {@link TemplateConcurrencyTest}, with the same scanner.
 *
 * <p><b>The list is per class</b> (a file's top-level class, its nested ones with it), and every entry says why. An
 * entry whose reason starts {@code VIOLATION} is not infrastructure: it is a known defect, listed so the build stays
 * green while the debt stays in sight, and it comes off when the code is fixed. There are none today (2026-11-15:
 * {@code BugReportPrompt} writes on the job lane and hears how it went as a message, so {@code OwnAsks} is the main
 * thread's alone).
 */
class FrameworkConcurrencyTest {

    private static final String CORE = "dev.vexelray.framework.core.";
    private static final String SHELL = "dev.vexelray.framework.shell.";

    /** Every class allowed the constructs, with its reason. */
    static final Map<String, String> INFRASTRUCTURE = Map.ofEntries(
            // -core
            entry(CORE + "Lanes", "the lanes themselves: a thread per component lane, the job pool, the lane groups, "
                    + "and each lane's idle stamp the watchdog reads from outside"),
            entry(CORE + "Timer", "the timer: one scheduler thread whose firings publish (T7.3's list)"),
            entry(CORE + "MainThreadBeat", "the watchdog's view of the main thread: written by the frame loop alone, "
                    + "read from the watchdog's thread, so each field is a single-writer volatile and nothing locks"),
            entry(CORE + "Watchdog","the watchdog: it must stand outside what it watches, on a thread of its own "
                    + "(ROADMAP §3), and it reads every thread's stack to report a halt"),
            // -automation
            entry("dev.vexelray.framework.automation.Driver", "the automation driver's socket threads (ROADMAP §3)"),
            // -shell: the tick and its lanes
            entry(SHELL + "Logic", "the logic lane, which runs the ticks: its mailbox arrivals, park and wake"),
            entry(SHELL + "Crew", "the tick scheduler's barrier (ROADMAP §3): the lanes a tick's work is shared "
                    + "across, and the Phaser they meet at"),
            entry(SHELL + "Jobs", "the job lane: whether its subscription is closed, read from the job threads"),
            entry(SHELL + "InputRecording", "the tick's recorder: written by the tick, read whole-tick by a replay on "
                    + "another thread, fingerprinted by a hasher thread of its own so the tick does not pay for it"),
            entry(SHELL + "Sources", "the sources' runs: a thread per running key (T7.3's list)"),
            entry(SHELL + "Destinations", "a destination's connections: a writer thread and a reader thread per key, "
                    + "the sources' runs for a part that also writes (components.md §12)"),
            // -shell: the process and its seams
            entry(SHELL + "Shell", "the process's hub: it owns the lanes, counts what the job lane owes, runs a "
                    + "replay on a lane of its own, and publishes the look in force to every lane"),
            entry(SHELL + "VexelApplication", "the process lifecycle: the default uncaught-exception handler, the "
                    + "shutdown hook, and the fault it reports once from whichever thread failed"),
            entry(SHELL + "Relaunch", "the process lifecycle: a restart waits for its predecessor's process to exit, "
                    + "within a bound"),
            entry(SHELL + "NativeDialogs", "an operating-system seam (T7.3's list): a native picker answers on its "
                    + "own thread, as a future posted back into the frame"),
            entry(SHELL + "Windows", "the automation seam: the claimed GuiApp is published to views(), which the "
                    + "automation driver reads from its socket thread"),
            entry(SHELL + "Stalls", "the watchdog's liveness policy, beside it; its only use is TimeUnit, for "
                    + "converting units"),
            // -shell: test support
            entry(SHELL + "Frames", "a headless test's frames: what the application's lanes send a Heard is handed "
                    + "to the test's thread as a frame runs, as a mailbox hands it to a view, and the test's thread "
                    + "parks between frames until the tree's wake or a Heard's arrival unparks it, so an application's "
                    + "tests hear and wait with none of the constructs themselves"));

    private static final List<Path> ROOTS = List.of(
            Path.of("../vexelray-framework-api/src/main/java"),
            Path.of("../vexelray-framework-core/src/main/java"),
            Path.of("../vexelray-framework-shell/src/main/java"),
            Path.of("../vexelray-framework-automation/src/main/java"));

    @Test
    void theConstructsAppearOnlyInTheInfrastructureClasses() {
        List<ConcurrencyScan.Hit> hits = ConcurrencyScan.scan(ROOTS);
        List<String> outside = new ArrayList<>();
        for (ConcurrencyScan.Hit h : hits) {
            if (!INFRASTRUCTURE.containsKey(h.type())) {
                outside.add(h.toString());
            }
        }
        assertEquals(List.of(), outside, "T7.3: synchronized, volatile, java.util.concurrent and Thread belong only "
                + "to the classes that implement the model. Move the work onto a lane and talk by messages; if this "
                + "class really is infrastructure (ROADMAP §3), add it to INFRASTRUCTURE with its reason");
    }

    @Test
    void everyEntryStillNeedsItsPlace() {
        Set<String> using = new TreeSet<>();
        for (ConcurrencyScan.Hit h : ConcurrencyScan.scan(ROOTS)) {
            using.add(h.type());
        }
        Set<String> stale = new TreeSet<>(INFRASTRUCTURE.keySet());
        stale.removeAll(using);
        assertEquals(Set.of(), stale, "these classes use none of the constructs any more (or are gone): take them "
                + "off INFRASTRUCTURE, so the list says only what is true");
    }
}
