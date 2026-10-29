package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;
import dev.vexelray.framework.core.Lanes;
import dev.vexelray.framework.core.WakeSource;
import sibarum.atchung.Atchung;
import sibarum.atchung.Inbox;
import sibarum.atchung.Step;
import sibarum.atchung.Tick;
import sibarum.atchung.Topic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.LockSupport;

/**
 * The logic lane: the application's components' inboxes, drained together by one {@link Tick} on one thread of their
 * own (ROADMAP fork 2, *one logic lane by default*; {@code docs/threading.md} §0 and §5).
 *
 * <p>A component that takes an {@link Inbox} in its constructor is given its own here, named after it, and owns it:
 * every message it hears arrives there, and whatever it runs on them (a ledger's steps) runs on this thread, one
 * delivery at a time, in rounds whose order is fixed by the order the inboxes were made and by nothing that depends
 * on timing. Many components, one thread, no locks: what keeps them apart is that none holds another (T2.3), not
 * which thread each is on.
 *
 * <p><b>What this owns is the thread and its wake</b>, as {@link Placement} does for a component on a lane of its own;
 * the tick owns the order. A message published from outside the tick is staged by atchung and ends this thread's park;
 * the thread runs one tick to quiescence or to its deadline, and if that tick delivered anything it wakes the frame
 * loop, because a component that ran has produced work the loop could not predict ({@link WakeSource}). A tick that
 * stopped at its deadline with work left runs again at once, without parking.
 *
 * <p><b>Made when the first component asks for an inbox, started with the placements</b>, after the wiring's
 * {@code ATTACH} has returned, so every publisher exists before anything is delivered, and every inbox exists before
 * the tick first runs, which is when the tick fixes their order. An application has none of this to call: the
 * generated wiring hands a component its inbox through {@link Inboxes}.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Logic implements WakeSource, AutoCloseable {

    /** The lane's name: on its thread, in the watchdog's report, and in a trace. */
    static final String LANE = "logic";

    /**
     * How long one tick may run before it stops between rounds and lets the frame loop see what it has done. A chain
     * of messages longer than this carries over into the next tick in order, so nothing is lost by it; it bounds how
     * stale a frame can be while the logic is busy. Half a 60 Hz frame, until phase 7 measures it.
     */
    private static final long DEADLINE_NANOS = 8_000_000L;

    /** A ceiling on how long the thread parks before looking again, so a stop never waits on it: not a poll. */
    private static final long PARK_NANOS = 500_000_000L;

    /** How long {@link #close} waits for the thread to return before giving up on it. */
    private static final long JOIN_MILLIS = 1_000L;

    private final Tick tick;
    private final Lanes lanes;
    private final Map<String, Inbox> inboxes = new HashMap<>();
    /** What each inbox's component declared it hears, for the inboxes whose component declared. */
    private final Map<String, Set<String>> declared = new HashMap<>();

    private volatile Runnable wake = () -> { };
    private volatile boolean arrived;
    private volatile boolean running;
    private volatile boolean stopped;
    private volatile Thread thread;

    Logic(Atchung bus, Lanes lanes) {
        this.tick = bus.tick(this::arrived);
        this.lanes = lanes;
    }

    /**
     * The inbox named {@code name}, made the first time it is asked for. Before {@link #start} only: the tick fixes
     * the order of its inboxes when it first runs.
     */
    Inbox inbox(String name) {
        Inbox in = inboxes.get(name);
        if (in == null) {
            in = tick.inbox(name);
            inboxes.put(name, in);
        }
        return in;
    }

    /** A message was staged for the next tick: end the park. On the publisher's thread, holding no lock. */
    private void arrived() {
        arrived = true;
        Thread t = thread;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    @Override
    public void onWake(Runnable onWake) {
        this.wake = onWake == null ? () -> { } : onWake;
    }

    /** Start the thread. Called by the container with the placements; idempotent. */
    void start() {
        if (running || stopped) {
            return;
        }
        checkDeclarations();
        running = true;
        thread = lanes.logic(this::runUntilStopped);
    }

    /** Record what the component owning the inbox {@code name} declared it hears ({@code @Hears}). */
    void declare(String name, List<String> hears) {
        declared.put(name, Set.copyOf(hears));
    }

    /**
     * Every declaring inbox is subscribed to exactly what it declared, besides its ledgers' own topics: a startup
     * error, not a compile one, because the subscriptions are calls in a constructor, which the processor does not
     * read. Without this, a topic heard but not declared is invisible to the channel checks, and one declared but not
     * heard tells them a reply is heard when it is lost.
     */
    private void checkDeclarations() {
        for (Map.Entry<String, Set<String>> e : declared.entrySet()) {
            Set<String> heard = new TreeSet<>();
            for (Topic<?> t : inboxes.get(e.getKey()).topics()) {
                if (t.payloadType() != Step.Transaction.class) {
                    heard.add(t.name());
                }
            }
            Set<String> undeclared = new TreeSet<>(heard);
            undeclared.removeAll(e.getValue());
            Set<String> unheard = new TreeSet<>(e.getValue());
            unheard.removeAll(heard);
            if (!undeclared.isEmpty() || !unheard.isEmpty()) {
                throw new IllegalStateException(e.getKey() + "'s @Hears does not match what its inbox subscribed to."
                        + (undeclared.isEmpty() ? "" : " Heard and not declared: " + undeclared + ".")
                        + (unheard.isEmpty() ? "" : " Declared and not heard: " + unheard + ".")
                        + " @Hears is how the processor checks the channels, so list exactly what the component hears");
            }
        }
    }

    private void runUntilStopped() {
        Lanes.Busy busy = lanes.busy(LANE);
        boolean carried = false;
        while (running) {
            if (!carried && !arrived) {
                LockSupport.parkNanos(this, PARK_NANOS);
                continue;
            }
            arrived = false;
            busy.enter();
            try {
                if (tick.run(System.nanoTime() + DEADLINE_NANOS) > 0) {
                    wake.run();
                }
                carried = tick.carried();
            } catch (RuntimeException | Error e) {
                // As a placement's: a step or a subscriber threw and nothing declared it, so what it was handling is
                // lost and its state is whatever the throw left. Ruled 2026-10-18: that ends the application, through
                // the process-wide fault policy, except while stopping, when the framework asked for the interrupt.
                if (!running) {
                    dev.vexelray.diag.Diagnostics.dropped("the logic lane", "a delivery while stopping", e.toString());
                    return;
                }
                Atchung.fatal().fault(new IllegalStateException(
                        "the logic lane threw from a delivery, and nothing declared the failure: " + e, e));
                throw e;
            } finally {
                busy.exit();
            }
        }
        // The last tick after the flag went down, so a stop does not leave a staged edge undelivered.
        tick.run(System.nanoTime() + DEADLINE_NANOS);
    }

    /** Stop: say so, end the park, and wait for the last tick. Idempotent. */
    @Override
    public void close() {
        if (stopped) {
            return;
        }
        stopped = true;
        running = false;
        Thread t = thread;
        if (t != null) {
            LockSupport.unpark(t);
            try {
                t.join(JOIN_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
