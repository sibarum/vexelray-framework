package dev.vexelray.framework.shell;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Topic;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A headless test's frames: what a lane sends is heard on the test's thread as frames run, and a wait runs frames until
 * what it waits for holds, or fails saying what never came.
 */
class FramesTest {

    private static final AppInfo DEMO = new AppInfo("demo", "Demo", 800, 600);

    record Ping(int n) {
        static final Topic<Ping> TOPIC = Topic.edge(Ping.class);
    }

    private Shell shell;
    private Frames frames;

    @BeforeEach
    void build(@TempDir Path home) {
        System.setProperty(DEMO.name() + ".home", home.toString());
        shell = VexelApplication.tree(new Wiring() {
            @Override
            public AppInfo info() {
                return DEMO;
            }
        }, new String[0]);
        frames = VexelApplication.frames(shell);
    }

    @AfterEach
    void close() {
        shell.disposer().close();
    }

    /** Sent from another thread, as a lane sends: heard here, in order, and only once a frame has run. */
    private void sendFromAnotherThread(int... ns) throws InterruptedException {
        Thread lane = new Thread(() -> {
            for (int n : ns) {
                shell.bus().publish(Ping.TOPIC, new Ping(n));
            }
        }, "a-lane");
        lane.start();
        lane.join();
    }

    @Test
    void whatALaneSendsIsHeardInTheNextFrameInOrder() throws Exception {
        Heard<Ping> pings = frames.hear(Ping.TOPIC);
        sendFromAnotherThread(1, 2, 3);
        assertTrue(pings.isEmpty(), "heard only as a frame runs, so it holds still between frames");
        frames.step();
        assertEquals(List.of(new Ping(1), new Ping(2), new Ping(3)), pings.all());
        assertEquals(new Ping(3), pings.last());
    }

    @Test
    void untilRunsFramesUntilItHolds() throws Exception {
        Heard<Ping> pings = frames.hear(Ping.TOPIC);
        Thread late = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return;
            }
            shell.bus().publish(Ping.TOPIC, new Ping(7));
        }, "a-late-lane");
        late.start();
        frames.untilHeard(pings, new Ping(7));
        frames.until("a ping", () -> !pings.isEmpty());
        late.join();
    }

    @Test
    void aWaitThatTimesOutSaysWhatNeverCameAndWhatWasHeard() throws Exception {
        Heard<Ping> pings = frames.hear(Ping.TOPIC);
        sendFromAnotherThread(1);
        AssertionError e = assertThrows(AssertionError.class,
                () -> frames.untilHeard(pings, new Ping(2), Duration.ofMillis(100)));
        assertTrue(e.getMessage().startsWith("Ping[n=2] was never heard on "), e.getMessage());
        assertTrue(e.getMessage().contains("[Ping[n=1]]"), "what came instead: " + e.getMessage());
        assertTrue(e.getMessage().contains("Nothing is owed"), e.getMessage());

        AssertionError f = assertThrows(AssertionError.class,
                () -> frames.until("the second ping", Duration.ofMillis(50), () -> pings.size() == 2));
        assertTrue(f.getMessage().startsWith("the second ping did not happen within 50 ms"), f.getMessage());
    }

    @Test
    void nothingHeardYetSaysSo() {
        Heard<Ping> pings = frames.hear(Ping.TOPIC);
        NoSuchElementException e = assertThrows(NoSuchElementException.class, pings::last);
        assertTrue(e.getMessage().contains(Ping.TOPIC.name()), e.getMessage());
    }

    @Test
    void theFramesAreTheMakingThreadsAlone() throws Exception {
        AtomicReference<Throwable> refused = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                frames.step();
            } catch (IllegalStateException e) {
                refused.set(e);
            }
        }, "another-thread");
        other.start();
        other.join();
        assertTrue(refused.get() != null && refused.get().getMessage().contains("from the test's own thread"),
                String.valueOf(refused.get()));
    }

    @Test
    void closedWithTheTree() {
        Heard<Ping> pings = frames.hear(Ping.TOPIC);
        shell.disposer().close();
        shell.bus().publish(Ping.TOPIC, new Ping(1));
        IllegalStateException e = assertThrows(IllegalStateException.class, frames::step);
        assertTrue(e.getMessage().contains("after the tree was closed"), e.getMessage());
        assertTrue(pings.isEmpty());
    }
}
