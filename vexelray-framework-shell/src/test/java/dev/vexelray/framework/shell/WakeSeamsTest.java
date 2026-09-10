package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.FrameStage;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.harness.HarnessWindow;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.kronometer.Dur;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.MouseButton;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The framework's own wake wiring, asserted end to end against a real parked loop.
 *
 * <p><b>What could not be tested before this.</b> {@code VexelApplication} registers two wakes and two
 * deadlines on the application's behalf — {@code gui::onWork}, {@code krono.kron()::onWork},
 * {@code kron().sleepTimeout()} and {@code memory::nanosUntilSettle} — and they are the lines
 * {@link dev.vexelray.framework.core.WakeSource} describes as <i>"unremarkable to write and catastrophic to
 * omit"</i>. {@code -core}'s {@code PacingTest} proves the arithmetic that combines them and cannot prove that
 * they are connected to anything. Nothing else could: a test that draws its own frames passes whether or not a
 * wake arrives, which
 * is how the GUI's five missing wakes shipped past a green suite.
 *
 * <p>So the loop here is real and parked, the windows are genuinely created and never shown, and the only
 * thing that can produce a frame is something actually asking for one. The seam that makes it possible is
 * {@link VexelApplication#run(Wiring, String[], java.util.function.Function)}.
 *
 * <p><b>What this suite actually catches</b>, established by deleting each seam in turn and watching which
 * assertion goes red. Recorded because a wake test that has not been falsified is exactly the thing this
 * repo keeps finding: green, and proving nothing.
 *
 * <table border="1">
 *   <caption>Each seam deleted from {@code VexelApplication}, one at a time</caption>
 *   <tr><th>Seam</th><th>Caught by</th></tr>
 *   <tr><td>{@code hooks().add(FrameStage.CLOCK, krono::tick)}</td><td>both the cue and the idle test</td></tr>
 *   <tr><td>{@code wake(krono.kron()::onWork)}</td><td>the idle test — an unwired clock answers {@code ZERO}
 *       to {@code sleepTimeout}, so the loop spins</td></tr>
 *   <tr><td>{@code deadline(memory::nanosUntilSettle)}</td><td>the idle test — it never parks at all</td></tr>
 *   <tr><td>{@code wake(gui::onWork)}</td><td><b>nothing</b>, and the reason is that it is redundant:
 *       {@code GuiApp.wireAllWakes} already does {@code gui.onWork(this::postWake)} for every tree it
 *       presents, re-checked every iteration. See {@code docs/TODO.md}</td></tr>
 *   <tr><td>{@code deadline(() -> kron().sleepTimeout().nanos())}</td><td><b>nothing</b>. Its contribution is
 *       precision rather than liveness — the clock's wake already brings the loop back whenever the timeline
 *       has work, so what this deadline buys is parking <em>until</em> the next moment instead of being
 *       nudged to it. See {@code docs/TODO.md}</td></tr>
 * </table>
 *
 * <p><b>Needs a Vulkan device.</b> This is an integration test in the module that is allowed to have one —
 * {@code -api} and {@code -core} stay JDK-only precisely so the container is testable without a GPU, and this
 * is not in either of them.
 */
final class WakeSeamsTest {

    /** Long enough to outlast a slow first frame, short enough that a hang is a failed test and not a hung CI. */
    private static final long TIMEOUT_MILLIS = 5_000L;

    @Test
    void aClickOnAFrameworkBuiltApplicationProducesAFrameOfItsOwnAccord(@TempDir Path home) throws Exception {
        ProbeWiring wiring = new ProbeWiring();
        Windows windows = new Windows();

        try (Run run = Run.start(wiring, windows, home)) {
            assertTrue(wiring.attached.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                    "the application never reached ATTACH");
            HarnessWindow window = windows.main();
            assertNotNull(window, "the window factory was never called, so the seam is not connected");

            run.settle(wiring);
            // The framework's 5 Hz idle floor has to come off, or this test cannot fail. GuiApp says why in
            // as many words: the floor exists so that "a missing wake then costs latency instead of a hang,
            // which is a different kind of defect: bounded, uniform, and survivable". Survivable in
            // production is invisible in a test — with the floor on, a frame arrives within 200 ms whether
            // or not anything asked for one, and this assertion passes against a framework with its wakes
            // deleted. Verified by deleting them.
            run.parkIndefinitely(wiring);
            run.settle(wiring);
            long before = wiring.frames.get();

            // A touchpad tap: press and release at one point, with no pointer motion. Motion would be an OS
            // event and would have woken the loop by itself, which is why the shape that broke carries none.
            Gui gui = wiring.shell.gui();
            gui.bus().publish(InputTopics.INPUT, new InputEvent.ButtonPressed(MouseButton.LEFT, 20, 20, 0));
            gui.bus().publish(InputTopics.INPUT, new InputEvent.ButtonReleased(MouseButton.LEFT, 20, 20, 0));
            // Stands in for the OS message that a real click would also have delivered. Everything after this
            // wake is what is under test: the handler runs, and something has to ask for the frame that shows it.
            window.postWake();

            assertTrue(run.await(() -> wiring.frames.get() > before, TIMEOUT_MILLIS),
                    "a click has to produce a frame: the loop is parked and nothing else will draw one");
            assertTrue(run.await(() -> wiring.clicks.get() == 1, TIMEOUT_MILLIS),
                    "and the handler has to have run: " + wiring.clicks.get());
        }
    }

    @Test
    void anIdleFrameworkApplicationParksRatherThanSpinning(@TempDir Path home) throws Exception {
        ProbeWiring wiring = new ProbeWiring();
        Windows windows = new Windows();

        try (Run run = Run.start(wiring, windows, home)) {
            assertTrue(wiring.attached.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                    "the application never reached ATTACH");
            run.settle(wiring);
            // Floor off, so what is left is the framework's own composition: Pacing reducing over the two
            // DeadlineSources it registered. With nothing animating and the placement written, both say
            // "never", so a correct application parks and stays parked.
            run.parkIndefinitely(wiring);
            run.settle(wiring);

            HarnessWindow window = windows.main();
            long waitsBefore = window.waits();
            long framesBefore = wiring.frames.get();
            Thread.sleep(400L);

            long frames = wiring.frames.get() - framesBefore;
            long waits = window.waits() - waitsBefore;
            assertTrue(waits > 0, "an idle application must park; it never waited");
            // This is the assertion that fails if the clock's wake is missing: Kron.sleepTimeout answers
            // FOREVER only once onWork is wired, and "unwired, the answer is ZERO — the loop keeps redrawing
            // exactly as it did before it asked". A spin is what an unwired clock looks like from out here.
            assertTrue(frames <= 2, "an idle application must not spin: " + frames + " frames in 400ms");
        }
    }

    @Test
    void aRepeatingCueKeepsRunningOnAParkedLoop(@TempDir Path home) throws Exception {
        ProbeWiring wiring = new ProbeWiring();
        Windows windows = new Windows();

        try (Run run = Run.start(wiring, windows, home)) {
            assertTrue(wiring.attached.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                    "the application never reached ATTACH");
            run.parkIndefinitely(wiring);
            run.settle(wiring);

            // A metronome on the timeline, with the idle floor off: after the first post there is nothing
            // external to nudge the loop, so this keeps running only if the clock is both woken and ticked
            // every frame. It is the liveness half — that {@code FrameStage.CLOCK} is registered and the
            // clock's wake reaches the loop — and it fails flat, at zero, if either is missing.
            AtomicInteger fired = new AtomicInteger();
            wiring.shell.krono().every(Dur.ms(50), fired::incrementAndGet);

            assertTrue(run.await(() -> fired.get() >= 5, TIMEOUT_MILLIS),
                    "a repeating cue must keep running on a parked loop; it stalled at " + fired.get());
        }
    }

    /**
     * The application under test: one clickable box, a frame counter, and the {@link Shell} kept so the test
     * can reach the bus the framework built.
     *
     * <p>The counter goes in {@link FrameStage#APP} because that is the stage an application writes hooks in,
     * so what is counted is a frame the framework's own loop decided to run.
     */
    private static final class ProbeWiring extends Wiring {

        final CountDownLatch attached = new CountDownLatch(1);
        final AtomicLong frames = new AtomicLong();
        final AtomicInteger clicks = new AtomicInteger();

        volatile Shell shell;

        @Override
        public AppInfo info() {
            return new AppInfo("vexelray-framework-waketest", "Wake Seams", 400, 300);
        }

        @Override
        public void tree(Shell shell) {
            Gui gui = shell.gui();
            Node box = gui.box().size(Length.dp(200), Length.dp(120));
            gui.root().append(box);
            gui.onClick(gui.root(), clicks::incrementAndGet);
        }

        @Override
        public void attach(Shell shell) {
            this.shell = shell;
            shell.hooks().add(FrameStage.APP, frames::incrementAndGet);
            attached.countDown();
        }
    }

    /**
     * The window factory handed to {@code VexelApplication}, and the record of what it made.
     *
     * <p>{@link WindowConfig#hidden} is the load-bearing word rather than the wrapper: a window is on screen
     * from the instant the platform makes it, so asking for it hidden is what makes "created and never shown"
     * literally true. This is {@code HarnessApp}'s own factory, which the framework can now be given.
     */
    private static final class Windows {

        private final List<HarnessWindow> opened = new CopyOnWriteArrayList<>();

        NativeWindow create(WindowConfig config) {
            HarnessWindow wrapped = new HarnessWindow(NativePlatform.current().createWindow(config.hidden(true)));
            opened.add(wrapped);
            return wrapped;
        }

        HarnessWindow main() {
            return opened.isEmpty() ? null : opened.get(0);
        }
    }

    /**
     * One run of {@code VexelApplication} on a thread of its own, ended by asking its window to stop.
     *
     * <p>{@code run} owns the loop and returns only when it ends, which is the whole reason this is a thread
     * and not a call: the assertions have to happen while the application is still running.
     */
    private static final class Run implements AutoCloseable {

        private final Thread loop;
        private final Windows windows;
        private final String homeProperty;

        private volatile RuntimeException failure;

        private Run(ProbeWiring wiring, Windows windows, Path home) {
            this.windows = windows;
            // Every application on this stack keeps its state in $HOME/.{name}; a test that used the real one
            // would write a window placement into the developer's home and read one back next time.
            this.homeProperty = wiring.info().name() + ".home";
            System.setProperty(homeProperty, home.toString());
            this.loop = Thread.ofPlatform().name("waketest-loop").unstarted(() -> {
                try {
                    VexelApplication.run(wiring, new String[0], windows::create);
                } catch (RuntimeException e) {
                    failure = e;
                }
            });
        }

        static Run start(ProbeWiring wiring, Windows windows, Path home) {
            Run run = new Run(wiring, windows, home);
            run.loop.start();
            return run;
        }

        /**
         * Take the framework's idle floor off, so that only a wake can produce a frame.
         *
         * <p>{@code idleRefresh(0)} parks indefinitely. Set from the test's thread rather than the loop's,
         * as {@code HarnessApp}'s own idle test does, and followed by a wake so the loop returns from its
         * current park and reads the new budget rather than sitting out the last 200 ms of the old one.
         */
        void parkIndefinitely(ProbeWiring wiring) {
            wiring.shell.app().idleRefresh(0L);
            windows.main().postWake();
        }

        /** Block until {@code condition} holds or the timeout elapses, failing early if the loop died. */
        boolean await(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
            long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
            while (System.nanoTime() < deadline) {
                rethrow();
                if (condition.getAsBoolean()) {
                    return true;
                }
                Thread.sleep(2L);
            }
            rethrow();
            return condition.getAsBoolean();
        }

        /**
         * Wait until the application stops producing frames of its own accord, so a test measures its
         * interaction rather than the tail of start-up.
         *
         * <p>Gives up rather than failing, for {@code HarnessApp.settle}'s reason: an application with a caret
         * never goes fully quiet, and insisting on it would be untestable against the applications most worth
         * testing.
         */
        void settle(ProbeWiring wiring) throws InterruptedException {
            long deadline = System.nanoTime() + 3_000L * 1_000_000L;
            long last = -1;
            long stableSince = System.nanoTime();
            while (System.nanoTime() < deadline) {
                rethrow();
                long now = wiring.frames.get();
                if (now != last) {
                    last = now;
                    stableSince = System.nanoTime();
                } else if (System.nanoTime() - stableSince >= 150L * 1_000_000L) {
                    return;
                }
                Thread.sleep(5L);
            }
        }

        private void rethrow() {
            RuntimeException e = failure;
            if (e != null) {
                throw new IllegalStateException("the application's frame loop died", e);
            }
        }

        @Override
        public void close() throws InterruptedException {
            HarnessWindow window = windows.main();
            if (window != null) {
                window.requestStop();
                window.postWake();
            }
            loop.join(TIMEOUT_MILLIS);
            System.clearProperty(homeProperty);
            rethrow();
            assertTrue(!loop.isAlive(), "the frame loop did not end when its window was asked to stop");
        }
    }
}
