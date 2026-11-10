package dev.vexelray.framework.shell;

import sibarum.probe.Log;
import sibarum.probe.LogConfig;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * <b>Which earlier runs of this application ended without closing</b>: a marker file each run writes as it starts and
 * deletes when it has closed, so a marker whose process is gone is a run that crashed, was halted or was killed.
 *
 * <p><b>Why a marker, and not the crash itself.</b> Nothing inside a process sees every way it can end. A native fault (a
 * GPU driver's, say) takes every thread with it, the watchdog's included; the watchdog's own halt and an exception
 * out of the frame loop leave no time for anything that draws, since the window and present live on the main thread.
 * A marker left behind is the one record every one of those leaves, power cuts and Task Manager included, and the next
 * start is the first moment anything healthy can read it. What the run said on its way out is already in the log: its
 * records, and its native standard error, which {@code Logging.recoverStandardError} reads in at that start.
 *
 * <p><b>Several at once.</b> Each running process has its own marker, named by its pid, so a second window of the same
 * application never takes a live one for a crash. A pid can be reused, so the marker holds its process's start time
 * and a different process under the same pid counts as gone. Two starts that find the same leftover race to delete it,
 * and only the one whose delete succeeds reports it, so it is offered once.
 *
 * <p>Beside the log, in its directory: where the log goes is where its evidence is, and a run with no log file (a
 * test) has nothing to report and writes no marker.
 */
final class UncleanExits {

    private static final Log LOG = Log.of("framework.crash");

    /** A run that did not close: its process id and when it started. */
    record Ended(long pid, Instant started) {
    }

    /** Whether a process is still the one that wrote a marker: {@code pid}, started at {@code started}. */
    interface Running {
        boolean is(long pid, Instant started);
    }

    /** No log directory, so nothing to read and nothing written. */
    static final UncleanExits NONE = new UncleanExits(null, List.of());

    private final Path marker;
    private final List<Ended> ended;

    private UncleanExits(Path marker, List<Ended> ended) {
        this.marker = marker;
        this.ended = List.copyOf(ended);
    }

    /** For this process, in {@code config}'s log directory. */
    static UncleanExits begin(LogConfig config) {
        if (!config.fileEnabled() || config.dir() == null) {
            return NONE;
        }
        ProcessHandle self = ProcessHandle.current();
        return begin(config.dir(), config.app(), self.pid(), self.info().startInstant().orElse(Instant.now()),
                UncleanExits::running);
    }

    /** Claim the leftovers in {@code dir}, then write this run's marker. */
    static UncleanExits begin(Path dir, String app, long pid, Instant started, Running running) {
        String prefix = prefix(app);
        List<Ended> ended = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, prefix + "*.marker")) {
                for (Path file : files) {
                    Ended e = read(file, prefix);
                    if (e == null || e.pid() == pid || running.is(e.pid(), e.started())) {
                        continue;
                    }
                    try {
                        Files.delete(file);
                        ended.add(e);
                    } catch (NoSuchFileException claimed) {
                        // another start of this application took it first, and offers it
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("earlier runs' markers in {} not read: {}", dir, e.toString());
            }
        }
        ended.sort(Comparator.comparing(Ended::started));
        Path mine = dir.resolve(prefix + pid + ".marker");
        try {
            Files.createDirectories(dir);
            Files.writeString(mine, Long.toString(started.toEpochMilli()));
        } catch (IOException | RuntimeException e) {
            LOG.warn("this run's marker {} not written, so a crash will not be offered as a report next time: {}",
                    mine, e.toString());
            mine = null;
        }
        return new UncleanExits(mine, ended);
    }

    /** The runs that ended without closing, oldest first. */
    List<Ended> ended() {
        return ended;
    }

    /** This run has closed: take its marker away. */
    void closed() {
        if (marker == null) {
            return;
        }
        try {
            Files.deleteIfExists(marker);
        } catch (IOException | RuntimeException e) {
            LOG.warn("this run's marker {} not removed, so the next start will take it for a crash: {}", marker,
                    e.toString());
        }
    }

    private static String prefix(String app) {
        return app + "-running-";
    }

    /** The marker's run, or null for a file that is not one. */
    private static Ended read(Path file, String prefix) {
        String name = file.getFileName().toString();
        try {
            long pid = Long.parseLong(name.substring(prefix.length(), name.length() - ".marker".length()));
            String text = Files.readString(file).trim();
            return new Ended(pid, Instant.ofEpochMilli(Long.parseLong(text)));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Whether {@code pid} is running and is the process that started at {@code started}, to the second. */
    private static boolean running(long pid, Instant started) {
        return ProcessHandle.of(pid)
                .map(h -> h.info().startInstant()
                        .map(s -> Math.abs(s.toEpochMilli() - started.toEpochMilli()) < 2_000L)
                        .orElse(true))
                .orElse(false);
    }
}
