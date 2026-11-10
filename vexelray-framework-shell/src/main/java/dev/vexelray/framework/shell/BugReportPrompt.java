package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Answer;
import dev.vexelray.framework.core.PickFile;
import dev.vexelray.framework.core.Picked;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.os.BackgroundRun;
import sibarum.probe.Log;
import sibarum.probe.Mode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * <b>The last run did not close: offer to save a bug report.</b> Asked once, at the start after a run that crashed, was
 * halted or was killed ({@link UncleanExits}), when this window is healthy and a person is there to answer.
 *
 * <p><b>At the next start, and not at the crash.</b> The crash is the one moment nothing can be drawn: the window and
 * present live on the main thread, a native fault takes every thread, and the watchdog does not watch the main thread
 * at all (a native dialog blocks it legitimately). The cost is that someone who never starts the application again is
 * never asked, which is the price of covering every way a run can end.
 *
 * <p>As every framework prompt is: an {@code Ask} through {@link OwnAsks}, so whichever {@link Dialogs} answer this
 * application answer it, then a {@code PickFile} for where to save, each answer heard in the main window's frame. The
 * zip is written on a thread of its own, since it reads megabytes of log and the frame must not wait for a disk; that
 * thread says how it went with a second {@code Ask}.
 *
 * <p><b>Not asked when nobody is there.</b> In an automation run, a test, or a run an agent started in the background,
 * a modal would only stand in front of whatever is driving the window; there the run is logged and not asked about.
 * {@code -Dvexelray.bugReport=ask} asks regardless, which is how the prompt itself is driven, and {@code =log} never
 * asks.
 */
final class BugReportPrompt {

    private static final Log LOG = Log.of("framework.crash");

    /** The property that overrides when to ask: {@code ask} or {@code log}. */
    static final String PROPERTY = "vexelray.bugReport";

    /** Saving is the default; not now is the cancel that Escape and the close box press. */
    static final List<String> BUTTONS = List.of("Save bug report…", "Not now");

    static final List<PickFile.Filter> ZIP = List.of(new PickFile.Filter("Zip archive", List.of("zip")));

    /** What the report is made of, gathered when it is saved: everything but the place. */
    record Contents(String app, String name, String about, String issues, String mode, Path logFile,
                    List<UncleanExits.Ended> ended) {
    }

    private final OwnAsks asks;
    private final Contents contents;
    /** Where the report is written: a thread of its own in the application, at once in a test. */
    private final Consumer<Runnable> writer;

    private long offered;
    private long picking;

    /** Heard in {@code main}'s frame, the main window's. Main thread. */
    BugReportPrompt(OwnAsks asks, Gui main, Contents contents) {
        this(asks, contents, run -> {
            Thread t = new Thread(run, "vexel-bug-report");
            t.setDaemon(true);
            t.start();
        });
        main.mailbox(Answer.TOPIC, this::answered, 4);
        main.mailbox(Picked.TOPIC, this::picked, 4);
    }

    /** With the writing thread supplied, and heard by whoever calls {@link #answered} and {@link #picked}. */
    BugReportPrompt(OwnAsks asks, Contents contents, Consumer<Runnable> writer) {
        this.asks = asks;
        this.contents = contents;
        this.writer = writer;
    }

    /** Whether to ask in a run in {@code mode}, with {@code property} as {@link #PROPERTY} says, or null. */
    static boolean asks(Mode mode, String property, boolean background) {
        if ("ask".equalsIgnoreCase(property)) {
            return true;
        }
        if ("log".equalsIgnoreCase(property)) {
            return false;
        }
        return mode != Mode.AUTOMATION && mode != Mode.TEST && !background;
    }

    /** For this run. */
    static boolean asks(Mode mode) {
        return asks(mode, System.getProperty(PROPERTY), BackgroundRun.active());
    }

    /** Ask. Main thread. */
    void offer() {
        offered = asks.ask(title(contents.name()), message(contents.name()), BUTTONS);
    }

    static String title(String name) {
        return name + " closed unexpectedly";
    }

    static String message(String name) {
        return "The last time " + name + " ran, it stopped without closing normally. A bug report puts what it"
                + " logged and a description of this computer (its operating system, processors and memory) into one"
                + " file you can attach to a report.\n\nYour user name, computer name and home folder are taken out of"
                + " it, and nothing is sent anywhere.";
    }

    /** The first button picks a place; any other answer, or none, is not now. */
    void answered(Answer a) {
        if (a.id() != offered || offered == 0) {
            return;
        }
        offered = 0;
        if (a.button() == 0) {
            picking = asks.save(BugReport.fileName(contents.app(), Instant.now()), ZIP);
        }
    }

    /** Where to save: written there, off the main thread, and said. Cancelled is the end of it. */
    void picked(Picked p) {
        if (p.id() != picking || picking == 0) {
            return;
        }
        picking = 0;
        if (p.isCancelled()) {
            return;
        }
        Path out = Path.of(p.path());
        Path target = out.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
                ? out : out.resolveSibling(out.getFileName() + ".zip");
        writer.accept(() -> save(target));
    }

    /** On the writing thread. */
    void save(Path out) {
        List<Path> logs = BugReport.logs(contents.logFile());
        try {
            String readme = BugReport.readme(contents.name(), contents.issues(),
                    logs.stream().map(l -> l.getFileName().toString()).toList());
            String system = BugReport.system(contents.about(), BugReport.machine(contents.mode()), contents.ended());
            BugReport.write(out, readme, system, logs, BugReport.Scrub.current());
            LOG.info("bug report saved to {}", out);
            asks.ask("Bug report saved", "Saved to " + out + "." + (contents.issues().isEmpty() ? ""
                    : "\n\nReports for " + contents.name() + " go to " + contents.issues() + "."), List.of("OK"));
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("bug report not saved to {}: {}", out, e.toString());
            asks.ask("The bug report was not saved", "It could not be written to " + out + ": " + e.getMessage(),
                    List.of("OK"));
        }
    }
}
