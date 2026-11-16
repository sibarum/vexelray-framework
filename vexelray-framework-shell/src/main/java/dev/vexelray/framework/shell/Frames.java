package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;
import dev.vexelray.gui.core.spi.Seams;
import sibarum.atchung.Subscription;
import sibarum.atchung.Topic;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * <b>The frames a headless test runs</b>, for a tree built by {@link VexelApplication#tree}: what lets a test hear
 * the application's messages and wait for what it asked for, on its own thread, with no sleep, no lock and no
 * concurrent collection of its own. Made by {@link VexelApplication#frames}.
 *
 * {@snippet :
 * Shell shell = VexelApplication.tree(new MyAppWiring(), new String[0]);
 * Frames frames = VexelApplication.frames(shell);
 * Heard<Caption> captions = frames.hear(Messages.CAPTION_TOPIC);   // before what sends it
 *
 * shell.bus().publish(Messages.OPEN_TOPIC, new Open(file, true));
 * frames.untilHeard(captions, new Caption("a.txt"));               // a message, as data
 * frames.until("the tab to open", () -> ws.find(file) != null);    // anything the test can read
 * }
 *
 * <p><b>This thread is the GUI thread.</b> There is no loop in a headless run, so the test runs the frames, and a view
 * hears only in a frame: whatever the test reads of a view, it reads where the view writes it. So every method here
 * belongs to the thread that made this, the test's own, and is refused from any other.
 *
 * <p><b>Hearing.</b> {@link #hear} subscribes for the test, and what arrives, on whichever lane sent it, is handed to its
 * {@link Heard} as the next frame runs, on this thread: every message, in the order sent. Hear before sending what you
 * wait for, or the answer may come before anything listens.
 *
 * <p><b>Waiting.</b> {@link #until} runs frames until its condition holds. Between frames it waits for the next
 * message to the tree or to a {@code Heard}, so it runs a frame when there is something to run it for, and never more
 * than a frame's length apart, so the clock's motion moves as it does in the window. It runs at least one frame. It
 * gives up after {@link #BOUND} unless told otherwise, failing with what it waited for, what was heard, and what the
 * application still owes ({@link Shell#owed}), which is usually where the answer is.
 *
 * <p><b>Not a callback</b> (T7.2). The condition {@code until} takes runs on the calling thread, between frames, and is
 * never kept: it is the test asking a question of its own state, not code handed to the framework to run elsewhere. A
 * condition on a message is better said as data, with {@link #untilHeard}, whose failure can then say what came
 * instead.
 *
 * <p><b>Why this class may use {@code java.util.concurrent}</b> (T7.3): it is where messages sent on the application's
 * lanes are handed to the test's thread, which is the same thing the framework's mailboxes are, and nothing an
 * application writes ever needs it again.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Frames implements AutoCloseable {

    /** How long {@link #until} waits by default: far longer than anything a test waits for takes on a slow machine. */
    public static final Duration BOUND = Duration.ofSeconds(10);

    /**
     * The longest wait between two frames when nothing has arrived: a frame at 60 Hz, the window's ceiling. What moves
     * on the tree's clock, a tween, a fade, sends nothing as it moves, and in the window the clock wakes the loop.
     */
    private static final long IDLE_NANOS = 16_666_666L;

    private final Shell shell;
    /** The thread that made this, and the only one that runs the frames: the GUI thread of this run. */
    private final Thread owner;
    /** Messages a {@code Heard} was sent, from any lane, on their way to this thread. */
    private final ConcurrentLinkedQueue<Arrival<?>> arriving = new ConcurrentLinkedQueue<>();
    /** Whether anything was sent to the tree or to a {@code Heard} since the last wait. Set from any lane. */
    private final AtomicBoolean arrived = new AtomicBoolean();
    // This thread's, from here down.
    private final List<Subscription> subscriptions = new ArrayList<>();
    private final List<Heard<?>> heard = new ArrayList<>();
    private long frames;
    private boolean closed;

    Frames(Shell shell) {
        this.shell = shell;
        this.owner = Thread.currentThread();
        // The tree's wake: what the window's loop is woken by, and so what a frame is due for. A headless tree has no
        // loop, so nothing else listens (one listener per tree, Seams.onWork).
        Seams.of(shell.gui()).onWork(this::wake);
        shell.disposer().register(this);
    }

    /**
     * Hear {@code topic} from now on: every message sent on it is in the {@code Heard} once the next frame has run.
     * Before sending what will answer, so the answer is not missed.
     */
    public <T> Heard<T> hear(Topic<T> topic) {
        require("hear");
        Heard<T> h = new Heard<>(topic);
        subscriptions.add(shell.bus().subscribe(topic, message -> {
            arriving.add(new Arrival<>(h, message));
            wake();
        }));
        heard.add(h);
        return h;
    }

    /**
     * Run one frame, as the window's loop would ({@link VexelApplication#frame}), and then hand every {@code Heard}
     * what was sent to it since the last.
     */
    public void step() {
        require("step");
        VexelApplication.frame(shell);
        frames++;
        for (Arrival<?> a = arriving.poll(); a != null; a = arriving.poll()) {
            a.deliver();
        }
    }

    /**
     * Run frames until {@code holds} is true, at least one, for at most {@link #BOUND}.
     *
     * @param what what is waited for, in words: what a failure says never happened
     * @param holds asked on this thread after each frame, and not kept
     * @throws AssertionError if it does not hold in time, saying what was heard and what is still owed
     */
    public void until(String what, BooleanSupplier holds) {
        until(what, BOUND, holds);
    }

    /** As {@link #until(String, BooleanSupplier)}, for at most {@code within}. */
    public void until(String what, Duration within, BooleanSupplier holds) {
        require("until");
        if (!waitFor(holds, within)) {
            throw new AssertionError(what + " did not happen within " + seconds(within) + " (" + frames
                    + " frames run in all). " + state() + hint());
        }
    }

    /**
     * Run frames until {@code heard} holds {@code message}, by {@code equals}, at least one, for at most {@link #BOUND}.
     *
     * @throws AssertionError if it does not arrive in time, saying what came instead and what is still owed
     */
    public <T> void untilHeard(Heard<T> heard, T message) {
        untilHeard(heard, message, BOUND);
    }

    /** As {@link #untilHeard(Heard, Object)}, for at most {@code within}. */
    public <T> void untilHeard(Heard<T> heard, T message, Duration within) {
        require("untilHeard");
        if (!waitFor(() -> heard.contains(message), within)) {
            throw new AssertionError(message + " was never heard on " + heard.topic().name() + " within "
                    + seconds(within) + " (" + frames + " frames run in all). " + state() + hint());
        }
    }

    /** Stop hearing. The tree's disposer does it as it closes, so a test need not. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Subscription s : subscriptions) {
            s.close();
        }
        subscriptions.clear();
    }

    // ------------------------------------------------------------------------------------------------------------

    private boolean waitFor(BooleanSupplier holds, Duration within) {
        long deadline = System.nanoTime() + within.toNanos();
        step();
        while (!holds.getAsBoolean()) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            idle(Math.min(left, IDLE_NANOS));
            step();
        }
        return true;
    }

    /** Wait for something to arrive, or {@code nanos}, whichever is first. */
    private void idle(long nanos) {
        if (!arrived.getAndSet(false)) {
            // An arrival between the read and the park unparks first, and the park then returns at once.
            LockSupport.parkNanos(this, nanos);
            arrived.set(false);
        }
    }

    /** Something was sent that a frame is due for. Any lane; after {@link #close}, a spare unpark nobody waits on. */
    private void wake() {
        arrived.set(true);
        LockSupport.unpark(owner);
    }

    private void require(String method) {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Frames." + method + " was called on " + Thread.currentThread().getName()
                    + ", and these frames are run by " + owner.getName() + ", the thread that made them: a headless"
                    + " test's frames run on one thread, as the window's do, so call it from the test's own thread");
        }
        if (closed) {
            throw new IllegalStateException("Frames." + method + " after the tree was closed (shell.disposer().close()):"
                    + " a test's frames end with its tree");
        }
    }

    /** What every recorder heard, and what the application still owes: where the answer to a failed wait usually is. */
    private String state() {
        StringBuilder s = new StringBuilder();
        if (!heard.isEmpty()) {
            s.append("Heard: ");
            for (int i = 0; i < heard.size(); i++) {
                s.append(i == 0 ? "" : "; ").append(heard.get(i));
            }
            s.append(". ");
        }
        List<String> owed = shell.owed();
        s.append(owed.isEmpty()
                ? "Nothing is owed: no job is running and every message to the logic lane is delivered, so what was"
                + " waited for was never sent, or went where nothing here hears it."
                : "Still owed: " + String.join("; ", owed) + ".");
        return s.toString();
    }

    private static String hint() {
        return " If it is only slow, give it longer with until(what, Duration, holds); to see what was sent, hear its"
                + " topic (frames.hear) and read the Heard.";
    }

    private static String seconds(Duration d) {
        long ms = d.toMillis();
        return ms % 1000 == 0 ? ms / 1000 + " s" : ms + " ms";
    }

    /** One message on its way to its {@code Heard}. */
    private record Arrival<T>(Heard<T> heard, T message) {
        void deliver() {
            heard.add(message);
        }
    }
}
