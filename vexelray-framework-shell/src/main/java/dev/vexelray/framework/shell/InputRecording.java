package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Lanes;
import sibarum.atchung.Tick;
import sibarum.atchung.Topic;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/**
 * What the logic lane took in and sent, tick by tick, from its first tick: what a {@link Replay} runs again (ROADMAP
 * <i>Done means</i> 5). Kept only in a run launched {@code --replayable}, since it holds every message from outside
 * the logic from launch on.
 *
 * <p><b>What is kept.</b> Each message from outside, as the tick that took it in started: the message itself (messages
 * are immutable, so holding one changes nothing), where it was for (its inbox's place in the order and its
 * subscription's there), and its chain run. Each send of a component: its topic, its chain run, and a fingerprint of
 * what it said, its {@code hashCode}, which within one process is the same for equal messages, so a replay in this
 * process compares with it. And each tick's rounds, and whether it stopped at its deadline.
 *
 * <p><b>The fingerprint is taken behind the tick.</b> The tick only keeps the send; a thread of the recording's own,
 * {@code replay-hasher}, fingerprints the sends of each whole tick and then lets them go, so a large message costs the
 * tick nothing (measured 2026-11-03: hashing on the tick took a heavy run's tick from p50 0.5 to 1.6 ms). Launched
 * {@code --replayable=values}, the sends are kept too, so a difference can say what the run sent, at the memory that
 * costs (about 12 MB a second for a heavy run).
 *
 * <p><b>Bounded.</b> At {@link #LIMIT} messages either way it stops, at the end of the last whole tick, and says it is
 * full: a replay then covers the run up to there.
 *
 * <p><b>Threads.</b> Written by whoever runs the ticks, one at a time; read by a replay on another thread, which reads
 * only the ticks published as whole: a tick's entries are written before it is published. Sends are kept in chunks
 * that are never copied, so the hasher fingerprinting one and the tick filling the next never share an array.
 */
final class InputRecording implements Tick.Recorder, AutoCloseable {

    /** How many messages in, or sends out, a recording keeps before it is full. */
    static final int LIMIT = 1 << 18;

    /** Sends a chunk holds. */
    private static final int CHUNK = 1 << 12;

    /** How long the hasher waits for a tick to be made whole before it looks again: a ceiling, not a poll. */
    private static final long LOOK_NANOS = 100_000_000L;

    /** Whether the sends themselves are kept after they are fingerprinted ({@code --replayable=values}). */
    private final boolean values;

    // What arrived, in order. The writer's; a reader reads below a whole tick's end only.
    private int[] inbox = new int[256];
    private int[] subscription = new int[256];
    private Object[] event = new Object[256];
    private long[] arrivedRun = new long[256];
    private int arrived;

    // What was sent, in order, a chunk at a time: the directories are the writer's and grow; the chunks never move.
    private Topic<?>[][] topic = new Topic<?>[8][];
    private long[][] sentRun = new long[8][];
    /** Each send, until the hasher has fingerprinted it; for good when {@link #values}. */
    private Object[][] value = new Object[8][];
    /** Each send's fingerprint, written by the hasher. */
    private int[][] fingerprint = new int[8][];
    private int sent;

    // Each whole tick: where its arrivals and sends end, its rounds, whether it carried over.
    private int[] arrivedTo = new int[256];
    private int[] sentTo = new int[256];
    private int[] rounds = new int[256];
    private boolean[] carried = new boolean[256];
    /** How many ticks are whole. The writer's. */
    private int ticks;
    private volatile boolean full;

    /**
     * The whole ticks and the arrays that hold them, published at the end of each: what a reader reads. Growing an
     * array makes a new one and leaves this one as it was, so what it holds below its whole ticks never changes.
     */
    private volatile Held arrays = new Held(0, inbox, subscription, event, arrivedRun, topic, sentRun, value,
            fingerprint, arrivedTo, sentTo, rounds, carried);
    /** How many sends belong to whole ticks: what the hasher may take. Written after {@link #arrays}. */
    private volatile int sentWhole;
    /** How many sends are fingerprinted. The hasher's. */
    private volatile int hashed;

    private volatile Thread hasher;
    private volatile boolean stopped;
    private Lanes lanes;

    InputRecording(boolean values) {
        this.values = values;
    }

    /** Start fingerprinting behind the tick, on a thread of {@code lanes}. Before the first tick. */
    void start(Lanes lanes) {
        this.lanes = lanes;
        hasher = lanes.thread("replay-hasher", this::hashBehind);
    }

    @Override
    public void arrived(int inbox, int subscription, Object event, long run) {
        if (full) {
            return;
        }
        if (arrived == this.inbox.length) {
            int n = arrived * 2;
            this.inbox = Arrays.copyOf(this.inbox, n);
            this.subscription = Arrays.copyOf(this.subscription, n);
            this.event = Arrays.copyOf(this.event, n);
            this.arrivedRun = Arrays.copyOf(this.arrivedRun, n);
        }
        this.inbox[arrived] = inbox;
        this.subscription[arrived] = subscription;
        this.event[arrived] = event;
        this.arrivedRun[arrived] = run;
        arrived++;
    }

    @Override
    public void sent(Topic<?> topic, Object event, long run) {
        if (full) {
            return;
        }
        int c = sent / CHUNK;
        int o = sent % CHUNK;
        if (o == 0) {
            if (c == this.topic.length) {
                int n = c * 2;
                this.topic = Arrays.copyOf(this.topic, n);
                this.sentRun = Arrays.copyOf(this.sentRun, n);
                this.value = Arrays.copyOf(this.value, n);
                this.fingerprint = Arrays.copyOf(this.fingerprint, n);
            }
            this.topic[c] = new Topic<?>[CHUNK];
            this.sentRun[c] = new long[CHUNK];
            this.value[c] = new Object[CHUNK];
            this.fingerprint[c] = new int[CHUNK];
        }
        this.topic[c][o] = topic;
        this.sentRun[c][o] = run;
        this.value[c][o] = event;
        sent++;
    }

    @Override
    public void ran(int rounds, boolean carried) {
        if (full) {
            return;
        }
        int t = ticks;
        if (arrived > LIMIT || sent > LIMIT) {
            // This tick is not kept whole, so it is not kept: the recording ends with the one before it.
            full = true;
            return;
        }
        if (t == arrivedTo.length) {
            int n = t * 2;
            arrivedTo = Arrays.copyOf(arrivedTo, n);
            sentTo = Arrays.copyOf(sentTo, n);
            this.rounds = Arrays.copyOf(this.rounds, n);
            this.carried = Arrays.copyOf(this.carried, n);
        }
        arrivedTo[t] = arrived;
        sentTo[t] = sent;
        this.rounds[t] = rounds;
        this.carried[t] = carried;
        ticks = t + 1;
        arrays = new Held(ticks, inbox, subscription, event, arrivedRun, topic, sentRun, value, fingerprint, arrivedTo,
                sentTo, this.rounds, this.carried);
        if (sent > sentWhole) {
            sentWhole = sent;
            Thread h = hasher;
            if (h != null) {
                LockSupport.unpark(h);
            }
        }
    }

    /** The hasher's loop: fingerprint each whole tick's sends, then let them go unless they are kept. */
    private void hashBehind() {
        while (!stopped && !lanes.closed()) {
            int to = sentWhole;
            Held a = arrays;
            int from = hashed;
            if (from >= to) {
                LockSupport.parkNanos(this, LOOK_NANOS);
                continue;
            }
            for (int i = from; i < to; i++) {
                int c = i / CHUNK;
                int o = i % CHUNK;
                a.fingerprint[c][o] = Objects.hashCode(a.value[c][o]);
                if (!values) {
                    a.value[c][o] = null;
                }
            }
            hashed = to;
        }
    }

    /**
     * Wait until the hasher has fingerprinted the first {@code count} sends, at most {@code nanos}; whether it has. A
     * replay asks before it compares.
     */
    boolean awaitHashed(int count, long nanos) {
        long until = System.nanoTime() + nanos;
        while (hashed < count) {
            if (stopped || System.nanoTime() - until >= 0) {
                return false;
            }
            LockSupport.parkNanos(1_000_000L);
        }
        return true;
    }

    @Override
    public void close() {
        stopped = true;
        Thread h = hasher;
        if (h != null) {
            LockSupport.unpark(h);
        }
    }

    /** What a replay reads: the whole ticks so far, as of now. Any thread. */
    Taken take() {
        boolean f = full;
        Held a = arrays;
        return new Taken(a.ticks, a, f, values);
    }

    /** The arrays as they were when a tick was last made whole: growing them makes new ones, never changes these. */
    private record Held(int ticks, int[] inbox, int[] subscription, Object[] event, long[] arrivedRun,
                        Topic<?>[][] topic, long[][] sentRun, Object[][] value, int[][] fingerprint, int[] arrivedTo,
                        int[] sentTo, int[] rounds, boolean[] carried) {
    }

    /** The recording's first {@code ticks} ticks, read without copying. */
    static final class Taken {
        final int ticks;
        private final Held a;
        final boolean full;
        /** Whether each send is kept, so {@link #value} says what it was. */
        final boolean values;

        private Taken(int ticks, Held a, boolean full, boolean values) {
            this.ticks = ticks;
            this.a = a;
            this.full = full;
            this.values = values;
        }

        /** How many sends the whole ticks hold. */
        int sends() {
            return ticks == 0 ? 0 : a.sentTo[ticks - 1];
        }

        int arrivedFrom(int tick) {
            return tick == 0 ? 0 : a.arrivedTo[tick - 1];
        }

        int arrivedTo(int tick) {
            return a.arrivedTo[tick];
        }

        int sentFrom(int tick) {
            return tick == 0 ? 0 : a.sentTo[tick - 1];
        }

        int sentTo(int tick) {
            return a.sentTo[tick];
        }

        int rounds(int tick) {
            return a.rounds[tick];
        }

        boolean carried(int tick) {
            return a.carried[tick];
        }

        int inbox(int i) {
            return a.inbox[i];
        }

        int subscription(int i) {
            return a.subscription[i];
        }

        Object event(int i) {
            return a.event[i];
        }

        long arrivedRun(int i) {
            return a.arrivedRun[i];
        }

        Topic<?> topic(int i) {
            return a.topic[i / CHUNK][i % CHUNK];
        }

        /** Send {@code i}'s fingerprint: only once the hasher has reached it ({@link #awaitHashed}). */
        int fingerprint(int i) {
            return a.fingerprint[i / CHUNK][i % CHUNK];
        }

        /** What send {@code i} said, when sends are kept ({@link #values}); else null. */
        Object value(int i) {
            return values ? a.value[i / CHUNK][i % CHUNK] : null;
        }

        long sentRun(int i) {
            return a.sentRun[i / CHUNK][i % CHUNK];
        }
    }
}
