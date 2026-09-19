package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Lanes;
import dev.vexelray.framework.core.WakeSource;
import sibarum.atchung.Atchung;
import sibarum.atchung.Backpressure;
import sibarum.atchung.Fold;
import sibarum.atchung.Pump;
import sibarum.atchung.Subscriber;
import sibarum.atchung.Subscription;
import sibarum.atchung.Topic;

import java.util.ArrayList;
import java.util.List;

/**
 * A component placed on a thread of its own, with its mailboxes and its wake.
 *
 * <p><b>What this owns is the thread</b>, and that is the whole of the division. The bus owns the queue, the
 * loss policy, the waiting and the coalescing — {@code Pump.drain(long)} parks holding no mailbox lock, and
 * {@code Pump.wake()} ends that park for shutdown. What was left over after atchung grew those two was
 * <i>something has to own the platform thread that does the waiting, and the drain-then-stop around it</i>.
 * This is that, and it is deliberately not more: a mailbox here is an ordinary subscription on an ordinary
 * topic, so a debug port can watch it and a script can publish to it, which a private queue could never be.
 *
 * <p><b>One component, one thread.</b> Which component gets which thread is decided in the wiring and never at
 * runtime: there is no work stealing, no placement decision and nothing to tune while running. That one
 * restriction is what the rest of the model is bought with — it is what lets the colour of a value be the
 * thread it was placed on, known while compiling, and what will let the processor emit thread construction and
 * the component-to-thread mapping as generated code with no scheduler in the binary.
 *
 * <p><b>Construction and start are separate, and the framework keeps them apart.</b> A mailbox must not pump
 * before its publishers exist, so {@link Shell#place} builds this and nothing runs on it; every placement is
 * started together, after the wiring's {@code ATTACH} has returned and before the loop begins. The designer's
 * hand-written component had to say this for itself — <i>"last, so nothing the component touches is still
 * half-built when its thread starts"</i> — which is a real ordering rule enforced by where one line happened to
 * sit in a constructor.
 *
 * <p><b>The wake is not optional and is not the application's to remember.</b> A component that finishes early
 * and publishes has produced work the loop cannot predict, which is {@code WakeSource}'s definition exactly;
 * omitting it gives a window that is responsive except for the interactions that happened to arrive that way.
 * So a placement <em>is</em> a {@code WakeSource}, registered by the framework when it is started, and the
 * component calls {@link #published()} when it has something for the loop to show. The designer met this
 * obligation by accident — announcing a phase wrote to a node, and a node mutation wakes the loop — which is a
 * real wake hanging on an unrelated line of reporting.
 *
 * <p>Usage, which is the hand-written component with the thread taken out of it:
 *
 * {@snippet :
 * Placement compose = shell.place("compose")
 *         .subscribe(EDITS, this::composeNow, 1, Backpressure.COALESCE_LATEST);
 * // ... and when a compose lands:
 * compose.published();
 * }
 */
public final class Placement implements WakeSource, AutoCloseable {

    /**
     * How long the thread parks before looking again — a ceiling on how long a stop waits, not a poll
     * interval. Nothing is polled: an arriving message ends the park immediately, and so does {@link #close}.
     */
    private static final long PARK_NANOS = 500_000_000L;

    /** How long {@link #close} waits for the component's own thread to return before giving up on it. */
    private static final long JOIN_MILLIS = 1_000L;

    private final String name;
    private final Lanes lanes;
    private final Pump pump;
    private final List<Subscription> mailboxes = new ArrayList<>(2);

    private volatile Runnable wake = () -> { };
    private volatile boolean running;
    private volatile boolean stopped;
    private Thread thread;

    /**
     * A placement outside a container — one component, a bus and somewhere to get a thread.
     *
     * <p>For a test that wants to exercise a component rather than an application, and it is public for the
     * same reason {@code -core} is JDK-only: a seam that could only be reached through {@code VexelApplication}
     * would make every component test an application test, which is the cost this framework charges elsewhere
     * precisely so it does not have to be paid here. The designer's compose component is tested this way, with
     * a real bus, a real thread and no window.
     *
     * <p>An application does not call this. {@link Shell#place} is the same object with the container keeping
     * the ordering rules that make it correct — registered for shutdown, started when every publisher exists,
     * and connected to the loop's wake.
     */
    public Placement(String name, Atchung bus, Lanes lanes) {
        this.name = name;
        this.lanes = lanes;
        this.pump = bus.pump();
    }

    /** What this component is called — on its thread, in a report, and in whatever names it next. */
    public String name() {
        return name;
    }

    /**
     * Give this component a mailbox: {@code topic} drained on its thread, {@code capacity} deep, with
     * {@code policy} deciding what a publisher meets when it is full.
     *
     * <p><b>One loss class per mailbox, not one mailbox per component.</b> A channel that carries both a
     * sample and an edge cannot be given a correct policy, because every choice is wrong for half the traffic
     * — so a component that receives both asks for two. A <i>sample</i> (a pointer position, a window size, a
     * scene edit) is {@code COALESCE_LATEST} at capacity one, because the next one supersedes it. An
     * <i>edge</i> (a keystroke, a command, a tree mutation) is {@code FAIL}, or {@code BLOCK} where the
     * publisher can safely wait, because nothing supersedes it and nothing downstream can reconstruct it.
     *
     * <p>{@code FAIL} needs no justification and loss does: a lost event is not an error anywhere, it is an
     * absence, and the bug is then looked for in the consumer, which is working perfectly.
     *
     * <p>Before {@link #start} only. A mailbox added to a running component would begin delivering on a thread
     * already in a drain, which is the ordering this class exists to make structural.
     */
    public <T> Placement subscribe(Topic<T> topic, Subscriber<T> subscriber, int capacity,
                                   Backpressure policy) {
        return subscribe(topic, subscriber, capacity, policy, null);
    }

    /**
     * As {@link #subscribe(Topic, Subscriber, int, Backpressure)}, with a {@link Fold}: the mailbox holds cells
     * rather than events, so a write supersedes the queued write to the same cell instead of queueing beside
     * it, and the bound counts what has changed rather than how many times the producer said so.
     *
     * <p>Read {@code Fold} before reaching for this. It carries a condition on the consumer that makes folding
     * lossless, and a channel that does not meet it must not fold.
     */
    public <T> Placement subscribe(Topic<T> topic, Subscriber<T> subscriber, int capacity,
                                   Backpressure policy, Fold<T> fold) {
        if (running) {
            throw new IllegalStateException(
                    "component " + name + " is already running; a mailbox is added before it starts, not after");
        }
        mailboxes.add(pump.subscribe(topic, subscriber, capacity, policy, fold));
        return this;
    }

    /**
     * Whether something is already queued for this component — in a word, <em>have I been superseded?</em>
     *
     * <p>For the case coalescing alone cannot answer. A {@code COALESCE_LATEST} mailbox drops the message
     * that was waiting, but it cannot cancel work already started: an expensive job that began before a newer
     * request arrived will finish, and the useful move is then not to <em>apply</em> it. Asking this before
     * publishing makes <i>superseded</i> a third outcome beside done and refused, and it is the difference
     * between a stale picture flashing on screen and one that never appears.
     *
     * <p>Only meaningful on the component's own thread, and only between a delivery and its result. Anywhere
     * else it is a race dressed up as a question.
     */
    public boolean superseded() {
        return pump.hasPending();
    }

    /**
     * Tell the loop that work it could not predict has arrived — call it after publishing a result.
     *
     * <p>Cheap and safe from this component's thread, which is the only thread that should be calling it: the
     * runnable behind it is the loop's own wake, whose contract is that it is <i>"safe to call from any
     * thread — that is its purpose"</i>.
     */
    public void published() {
        wake.run();
    }

    /**
     * {@code WakeSource}: the framework connects this to the loop when the component starts.
     *
     * <p>Not called by an application. A placement is registered as a wake source by whoever started it, which
     * is what makes the obligation structural rather than a line to remember per component.
     */
    @Override
    public void onWake(Runnable onWake) {
        this.wake = onWake == null ? () -> { } : onWake;
    }

    /**
     * Start the thread, once every publisher this component might hear from exists.
     *
     * <p><b>An application does not call this.</b> For a placement from {@link Shell#place}, the container
     * calls it — after the wiring's {@code ATTACH} has returned, for every component together — and calling it
     * from a wiring would start a mailbox pumping before the wiring has finished making the things that
     * publish to it. It is public for a component constructed standalone, which has no container to do it.
     *
     * <p>Idempotent, and returns {@code this} so a standalone component reads as one expression.
     */
    public Placement start() {
        if (running) {
            return this;
        }
        running = true;
        // A platform thread, from the application's lanes rather than minted here, so that what a component
        // runs on is one decision for the whole application rather than one per component that wanted a thread.
        thread = lanes.thread(name, this::pumpUntilStopped);
        return this;
    }

    /**
     * The whole of the component's thread: park on the mailbox, deliver what arrives, and return when asked.
     *
     * <p>No work of its own. What runs here is a subscriber the component registered, on the thread that owns
     * its state, which is what "a component's state is its own thread's" means in practice.
     */
    private void pumpUntilStopped() {
        while (running) {
            try {
                pump.drain(PARK_NANOS);
            } catch (RuntimeException e) {
                // A subscriber threw. The component is the unit of failure, so this thread keeps its mailbox
                // draining rather than dying silently and leaving publishers to fill a queue nobody reads --
                // which is the wedged component the model answers with backpressure, arrived at by accident.
                // Nothing here decides the application's policy: supervision is what would, and it does not
                // exist yet, so the honest thing is to say so where somebody will see it.
                dev.vexelray.diag.Diagnostics.dropped("component " + name, "one delivery",
                        e + "; the component is still draining");
            }
        }
        // The last drain, after the flag went down: a stop that left a queued message undelivered would lose
        // an edge nothing downstream can reconstruct. A coalescing mailbox holds a sample instead, and the
        // sample it holds at shutdown is a picture nobody will see -- so this costs that one nothing.
        pump.drain();
    }

    /**
     * Stop the component: say so, end its park, wait for it, and close its mailboxes in that order.
     *
     * <p>Drain then stop, and the order is the point. Closing the mailboxes first would drop whatever is
     * queued on the floor; interrupting first would abandon a delivery mid-flight. So the flag goes down, the
     * park ends, the thread does its last drain and returns, and only then is anything closed.
     *
     * <p>Bounded at {@link #JOIN_MILLIS}. A component that will not return has to be left behind, because a
     * process that refuses to quit is worse than one that quits having abandoned a message — the same trade the
     * framework's fault policy makes, for the same reason.
     *
     * <p><b>Idempotent, and that is what makes it callable early.</b> The container closes every placement at
     * shutdown, in reverse placement order — but a component holding a resource its own thread touches has to
     * stop <em>before</em> that resource goes, and only the component knows which resource that is. So it
     * closes its own placement first and the container's later close is a no-op. The designer's viewport is
     * the case: a compose landing after its pipeline is gone raises dirty flags for a frame that is never
     * coming.
     */
    @Override
    public void close() {
        if (stopped) {
            return;
        }
        stopped = true;
        running = false;
        // Without this, the wait below is however long a park this thread happened to be in.
        pump.wake();
        if (thread != null) {
            try {
                thread.join(JOIN_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (int i = mailboxes.size() - 1; i >= 0; i--) {
            mailboxes.get(i).close();
        }
        mailboxes.clear();
    }
}
