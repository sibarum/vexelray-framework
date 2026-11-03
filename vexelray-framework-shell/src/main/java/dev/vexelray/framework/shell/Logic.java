package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Stability;
import dev.vexelray.framework.core.Lanes;
import dev.vexelray.framework.core.Timer;
import dev.vexelray.framework.core.WakeSource;
import sibarum.atchung.Alarm;
import sibarum.atchung.Atchung;
import sibarum.atchung.Inbox;
import sibarum.atchung.Step;
import sibarum.atchung.Tick;
import sibarum.atchung.Topic;
import sibarum.probe.Lane;
import sibarum.probe.Probe;
import sibarum.probe.Zone;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.LockSupport;

/**
 * The logic lane: the application's components' inboxes, drained together by one {@link Tick} (ROADMAP fork 2, *one
 * logic lane by default*; {@code docs/threading.md} §0, T1.6 and §5).
 *
 * <p><b>Every component is here.</b> One that takes an {@link Inbox} in its constructor is given its own, named after
 * it, and owns it; one that declares {@code @Subscribe} methods has each of them subscribed on an inbox the wiring makes
 * for it. Every message a component hears arrives there, and whatever it runs on them runs one delivery at a time, in
 * rounds whose order is fixed by the order the inboxes were made and by nothing that depends on timing. Many
 * components, one thread, no locks: what keeps them apart is that none holds another (T2.3), not which thread each is
 * on.
 *
 * <p><b>A declared lane is a lane of the same tick.</b> A component that declares one
 * ({@code @Component(lane = "physics")}) has its inbox on a lane of its own, which a {@link Crew} thread,
 * {@code vexel-component-<lane>}, delivers in parallel with the rest of each round: a core for heavy work, in the same
 * rounds and the same order as everything else (T5.6).
 *
 * <p><b>What this owns is the thread and its wake</b>; the tick owns the order. A message published from outside the
 * tick is staged by atchung and ends this thread's park; the thread runs one tick to quiescence or to its deadline, and
 * if that tick delivered anything it wakes the frame loop, because a component that ran has produced work the loop
 * could not predict ({@link WakeSource}). A tick that stopped at its deadline with work left runs again at once,
 * without parking.
 *
 * <p><b>Where the tick runs is a deployment mode</b> (T1.7, ROADMAP fork 4). By default this owns a thread,
 * {@code vexel-logic}. With {@code --logic=main} it owns none: an arrival wakes the frame loop instead, and the frame runs
 * the tick on the main thread ({@link #runOnMain}: the framework's own {@code APP} hook, or
 * {@code VexelApplication.frame} when headless), with every declared lane delivered there too, in lane order. The
 * deliveries are the same in both, because the order is the tick's and not the thread's, so nothing an application
 * holds can tell which mode it is in.
 *
 * <p><b>Made when the first component asks for an inbox, started with the components</b>, after the wiring's
 * {@code ATTACH} has returned, so every publisher exists before anything is delivered, and every inbox exists before
 * the tick first runs, which is when the tick fixes their order. An application has none of this to call: the
 * generated wiring hands a component its inbox, its mailboxes and its senders through {@link Inboxes}.
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
    private final boolean onMain;
    private final Map<String, Inbox> inboxes = new HashMap<>();
    /** The inboxes' names in the order they were made, which is the order the tick delivers them in. */
    private final List<String> order = new ArrayList<>();
    /** The lane each inbox was made on, so one component asking for two is caught. */
    private final Map<String, String> laneOf = new HashMap<>();
    /** The declared lanes, in the order first asked for: lane {@code i + 1} of the tick is the {@code i}th. */
    private final Map<String, Integer> declaredLanes = new LinkedHashMap<>();
    /** What each inbox's component declared it hears, for the inboxes whose component declared. */
    private final Map<String, Set<String>> declared = new HashMap<>();

    private volatile Runnable wake = () -> { };
    private volatile boolean arrived;
    private volatile boolean running;
    private volatile boolean stopped;
    private volatile Thread thread;
    /** The declared lanes' threads, or null when there are none or the ticks run on the main thread. */
    private Crew crew;
    private Tick.Lanes runner = Tick.ONE_THREAD;
    /** Whether the last tick on the main thread stopped at its deadline. The main thread's alone. */
    private boolean carriedOnMain;
    /** Every message staged for the tick, counted on the publisher's thread: what a quiet lane has delivered. */
    private final java.util.concurrent.atomic.AtomicLong arrivals = new java.util.concurrent.atomic.AtomicLong();
    /** How many arrivals the last tick that ended with nothing left over had seen. Written by whoever runs ticks. */
    private volatile long delivered;

    /** The logic lane on a thread of its own: the default mode. */
    Logic(Atchung bus, Lanes lanes) {
        this(bus, lanes, false);
    }

    /** The logic lane, with its ticks run on the main thread when {@code onMain} (single-thread mode, T1.7). */
    Logic(Atchung bus, Lanes lanes, boolean onMain) {
        this.tick = bus.tick(this::arrived).named(LANE);
        this.lanes = lanes;
        this.onMain = onMain;
    }

    /**
     * Keep the bounds of every chain on this lane with {@code timer}, and log a default bound running out as the bug
     * it is (atchung's {@code Chain}; {@code docs/plans/chains.md}, rulings 3, 7 and 14). The timer publishes a wait's
     * due message on its inbox's own topic, like any other message from outside, so nothing here runs a callback.
     */
    void keepBoundsWith(Timer timer) {
        tick.alarm(new Alarm() {
            @Override
            public <T> Cancel after(Duration delay, Topic<T> topic, T message) {
                return timer.after(delay, topic, message)::cancel;
            }
        });
        tick.whenOverdue(what -> CHAINS.warn(what));
    }

    private static final sibarum.probe.Log CHAINS = sibarum.probe.Log.of("framework.chains");

    /** The inbox named {@code name} on the logic lane's own share of the tick. */
    Inbox inbox(String name) {
        return inbox(name, Component.DEFAULT_LANE);
    }

    /**
     * The inbox named {@code name} on {@code lane} ({@link Component#DEFAULT_LANE} for the logic lane's own share),
     * made the first time it is asked for. Before {@link #start} only: the tick fixes the order of its inboxes when it
     * first runs.
     */
    Inbox inbox(String name, String lane) {
        Inbox in = inboxes.get(name);
        if (in != null) {
            if (!laneOf.get(name).equals(lane)) {
                throw new IllegalStateException(name + " asked for its inbox on lane " + lane + " and on lane "
                        + laneOf.get(name) + ": a component is on one lane");
            }
            return in;
        }
        if (running || stopped) {
            throw new IllegalStateException("inbox " + name + " asked for after the logic lane started: the wiring"
                    + " makes every inbox before the first tick fixes their order");
        }
        int index = Component.DEFAULT_LANE.equals(lane) ? 0
                : declaredLanes.computeIfAbsent(lane, l -> declaredLanes.size() + 1);
        in = tick.inbox(name, index);
        inboxes.put(name, in);
        order.add(name);
        laneOf.put(name, lane);
        return in;
    }

    /** Tell {@code recorder} what every tick takes in and sends, from the first ({@code Shell.replayable}). */
    void record(Tick.Recorder recorder) {
        tick.record(recorder);
    }

    /** The tick, for a replay, which runs it itself and never {@linkplain #start starts} this lane. */
    Tick tick() {
        return tick;
    }

    /** Every inbox's name, in the order the tick delivers them. */
    List<String> names() {
        return List.copyOf(order);
    }

    /** Whether the ticks run on the main thread ({@code --logic=main}). */
    boolean onMain() {
        return onMain;
    }

    /**
     * A message was staged for the next tick: end the park, or, when the ticks run on the main thread, ask the loop for
     * the frame that runs one. On the publisher's thread, holding no lock.
     */
    private void arrived() {
        arrivals.incrementAndGet();
        arrived = true;
        if (onMain) {
            if (running) {
                wake.run();
            }
            return;
        }
        Thread t = thread;
        if (t != null) {
            // Said once for the park it ends, on the thread that ended it, as the frame loop's wake is: the next
            // tick's wait on this lane began here (vexelray-inspector framework-notes FN-17).
            if (parked.compareAndSet(true, false) && Probe.ON) {
                Probe.mark(Lane.BUS, "wake", WOKEN);
            }
            LockSupport.unpark(t);
        }
    }

    /** The idle zone the lane's thread parks in between ticks, on the bus lane. */
    static final String PARK = "logic park";

    /** What a {@code wake} row ending this lane's park says: the frame loop's form, {@code <why> on <whom>}. */
    static final String WOKEN = "message on " + LANE;

    /** Whether the lane's thread is parked waiting for a message, for {@link #arrived} to say it ended the park. */
    private final java.util.concurrent.atomic.AtomicBoolean parked = new java.util.concurrent.atomic.AtomicBoolean();

    @Override
    public void onWake(Runnable onWake) {
        this.wake = onWake == null ? () -> { } : onWake;
    }

    /** Start the lane. Called by the container with the components; idempotent. */
    void start() {
        if (running || stopped) {
            return;
        }
        checkDeclarations();
        List<String> names = new ArrayList<>(declaredLanes.keySet());
        if (!onMain && !names.isEmpty()) {
            crew = new Crew(lanes, lanes.busy(LANE), names);
            runner = crew;
        } else {
            runner = inTurn(names);
        }
        running = true;
        if (onMain) {
            // Whatever arrived before the start is waiting for a tick: ask for the frame that runs it.
            if (arrived) {
                wake.run();
            }
            return;
        }
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

    /**
     * Run a tick on the calling thread, the main thread, if anything arrived or was carried over: the frame's share of
     * single-thread mode. Does nothing in the default mode, where the lane's own thread runs every tick, or before the
     * lane has started.
     *
     * @return whether a tick ran and delivered anything
     */
    boolean runOnMain() {
        if (!onMain || !running || (!arrived && !carriedOnMain)) {
            return false;
        }
        arrived = false;
        int delivered = runTick();
        carriedOnMain = tick.carried();
        if (carriedOnMain) {
            // The rest waits for the next frame, which nothing else would ask for.
            wake.run();
        }
        return delivered > 0;
    }

    private void runUntilStopped() {
        boolean carried = false;
        while (running) {
            if (!carried && !arrived) {
                // An idle zone, as the frame loop's wait for events is: rest, which a ledger reads as rest and never
                // as work or as a gap the rows leave unsaid (vexelray-inspector framework-notes FN-17).
                parked.set(true);
                try (Zone z = Probe.idleZone(Lane.BUS, PARK)) {
                    if (!arrived) {
                        LockSupport.parkNanos(this, PARK_NANOS);
                    }
                }
                parked.set(false);
                continue;
            }
            arrived = false;
            if (runTick() > 0) {
                wake.run();
            }
            carried = tick.carried();
        }
        lastTick();
    }

    /**
     * Every lane of a round on the calling thread, in lane order, each watched by its own gauge, so a wedged declared
     * lane is reported by its name even when one thread runs them all: single-thread mode, or no declared lane.
     */
    private Tick.Lanes inTurn(List<String> declared) {
        Lanes.Busy[] gauges = new Lanes.Busy[declared.size() + 1];
        gauges[0] = lanes.busy(LANE);
        for (int i = 0; i < declared.size(); i++) {
            gauges[i + 1] = lanes.busy(declared.get(i));
        }
        return (count, work) -> {
            for (int i = 0; i < count; i++) {
                Lanes.Busy busy = gauges[Math.min(i, gauges.length - 1)];
                busy.enter();
                try {
                    work.lane(i);
                } finally {
                    busy.exit();
                }
            }
        };
    }

    /**
     * Whether every message that has arrived for the lane has been delivered, and every send those deliveries made
     * between the lane's own inboxes with them: what the automation driver's {@code settle} waits for besides the
     * clock. Any thread. A tick marks delivered only the arrivals counted before it began, so a quiet answer is never
     * earlier than the truth; one counted after it has been delivered costs at most one more tick.
     */
    boolean quiet() {
        return delivered == arrivals.get();
    }

    /** How many arrivals are not yet delivered, for a settle that says what it is waiting on. Any thread. */
    long undelivered() {
        return arrivals.get() - delivered;
    }

    /** One tick, with a throw nothing declared sent to the fault policy. */
    private int runTick() {
        // Read before the tick: an arrival during it is the next tick's, and keeps the lane from reading as quiet.
        long seen = arrivals.get();
        try {
            int n = tick.run(System.nanoTime() + DEADLINE_NANOS, runner);
            if (!tick.carried()) {
                delivered = seen;
            }
            return n;
        } catch (RuntimeException | Error e) {
            // A step or a subscriber threw and nothing declared it, so what it was handling is lost and its state is
            // whatever the throw left. Ruled 2026-10-18: that ends the application, through the process-wide fault
            // policy, except while stopping, when the framework asked for the interrupt.
            if (!running) {
                dev.vexelray.diag.Diagnostics.dropped("the logic lane", "a delivery while stopping", e.toString());
                return 0;
            }
            Atchung.fatal().fault(new IllegalStateException(
                    "the logic lane threw from a delivery, and nothing declared the failure: " + e, e));
            throw e;
        }
    }

    /** The last tick after the flag went down, so a stop does not leave a staged edge undelivered. */
    private void lastTick() {
        try {
            tick.run(System.nanoTime() + DEADLINE_NANOS, runner);
        } catch (RuntimeException | Error e) {
            dev.vexelray.diag.Diagnostics.dropped("the logic lane", "a delivery while stopping", e.toString());
        }
    }

    /**
     * Stop: say so, end the park, and wait for the last tick; then let the declared lanes go. In single-thread mode the
     * last tick runs here, on the thread closing the application. Idempotent.
     */
    @Override
    public void close() {
        if (stopped) {
            return;
        }
        stopped = true;
        boolean wasRunning = running;
        running = false;
        Thread t = thread;
        if (t != null) {
            LockSupport.unpark(t);
            try {
                t.join(JOIN_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else if (onMain && wasRunning) {
            lastTick();
        }
        if (crew != null) {
            crew.close();
        }
    }
}
