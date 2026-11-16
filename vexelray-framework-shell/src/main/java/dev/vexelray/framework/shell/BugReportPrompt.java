package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Answer;
import dev.vexelray.framework.core.PickFile;
import dev.vexelray.framework.core.Picked;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.os.BackgroundRun;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;
import sibarum.probe.Log;
import sibarum.probe.Mode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

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
 * zip is written by a job on the framework's job lane, since it reads megabytes of log and the frame must not wait for
 * a disk (T5.8): the place goes to it as a {@link Write}, and how it went comes back as a {@link Written}, heard in the
 * same frame, which says so with a second {@code Ask}. So everything here but the job is the main thread's, the job
 * reads only the {@link Contents}, which is a value, and nothing crosses between them but those two messages.
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

    /** What the report is made of, gathered when it is saved: everything but the place. A value, read by the job. */
    record Contents(String app, String name, String about, String issues, String mode, Path logFile,
                    List<UncleanExits.Ended> ended) {
        Contents {
            ended = List.copyOf(ended);
        }
    }

    /**
     * Write the report to {@code out}: what the prompt asks its job once a place is picked. The framework's own
     * channel, carrying what only this class reads, so no application declares or hears it.
     */
    record Write(String out) {
        static final Topic<Write> TOPIC = Topic.edge(Write.class);
    }

    /** How the writing went: {@code failure} is empty when the report is saved at {@code out}. The job's answer. */
    record Written(String out, String failure) {
        static final Topic<Written> TOPIC = Topic.edge(Written.class);

        boolean saved() {
            return failure.isEmpty();
        }
    }

    /** Asked once a run, so one write at most is ever on its way; a few in case. */
    private static final int CAPACITY = 4;

    private final OwnAsks asks;
    private final Atchung bus;
    private final Contents contents;

    // The main thread's, from here down: asked at ATTACH, answered in the main window's frame.
    private long offered;
    private long picking;

    /** The job on {@code shell}'s job lane, and every answer heard in the main window's frame. Main thread. */
    BugReportPrompt(Shell shell, Contents contents) {
        this(shell.ownAsks(), shell.bus(), contents);
        Jobs.reply(shell, "BugReport.write", Write.TOPIC, w -> write(contents, w), Written.TOPIC);
        Gui main = shell.gui();
        main.mailbox(Answer.TOPIC, this::answered, CAPACITY, Set.of(OwnAsks.FROM));
        main.mailbox(Picked.TOPIC, this::picked, CAPACITY);
        main.mailbox(Written.TOPIC, this::written, CAPACITY);
    }

    /**
     * Asking on {@code asks} and sending the write on {@code bus}, with no job and no frame: heard by whoever calls
     * {@link #answered}, {@link #picked} and {@link #written}, and written by whoever calls {@link #write}.
     */
    BugReportPrompt(OwnAsks asks, Atchung bus, Contents contents) {
        this.asks = asks;
        this.bus = bus;
        this.contents = contents;
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

    /** Where to save: handed to the job, which writes it there off the main thread. Cancelled is the end of it. */
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
        bus.publish(Write.TOPIC, new Write(target.toString()));
    }

    /**
     * The job: write the report {@code contents} makes to where {@code request} says, and say how it went. On the job
     * lane. It reads nothing of the prompt's but {@code contents}, a value, and it expects its failures, so it answers
     * them rather than throwing: a throw from a job ends the application ({@link Jobs}).
     */
    static Written write(Contents contents, Write request) {
        Path out = Path.of(request.out());
        List<Path> logs = BugReport.logs(contents.logFile());
        try {
            String readme = BugReport.readme(contents.name(), contents.issues(),
                    logs.stream().map(l -> l.getFileName().toString()).toList());
            String system = BugReport.system(contents.about(), BugReport.machine(contents.mode()), contents.ended());
            BugReport.write(out, readme, system, logs, BugReport.Scrub.current());
            return new Written(request.out(), "");
        } catch (java.io.IOException | RuntimeException e) {
            String why = e.getMessage();
            return new Written(request.out(), why == null || why.isBlank() ? e.toString() : why);
        }
    }

    /** The job's answer: logged, and said with a second question. In the main window's frame. */
    void written(Written w) {
        if (w.saved()) {
            LOG.info("bug report saved to {}", w.out());
            asks.ask("Bug report saved", "Saved to " + w.out() + "." + (contents.issues().isEmpty() ? ""
                    : "\n\nReports for " + contents.name() + " go to " + contents.issues() + "."), List.of("OK"));
        } else {
            LOG.warn("bug report not saved to {}: {}", w.out(), w.failure());
            asks.ask("The bug report was not saved", "It could not be written to " + w.out() + ": " + w.failure(),
                    List.of("OK"));
        }
    }
}
