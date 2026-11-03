package dev.vexelray.framework.shell;

import sibarum.atchung.Tick;
import sibarum.atchung.Topic;

import java.util.Arrays;
import java.util.Objects;

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
 * <p><b>Bounded.</b> At {@link #LIMIT} messages either way it stops, at the end of the last whole tick, and says it is
 * full: a replay then covers the run up to there.
 *
 * <p><b>Threads.</b> Written by whoever runs the ticks, one at a time; read by a replay on another thread, which reads
 * only the ticks published as whole: a tick's entries are written before it is published.
 */
final class InputRecording implements Tick.Recorder {

    /** How many messages in, or sends out, a recording keeps before it is full. */
    static final int LIMIT = 1 << 18;

    // What arrived, in order. The writer's; a reader reads below a whole tick's end only.
    private int[] inbox = new int[256];
    private int[] subscription = new int[256];
    private Object[] event = new Object[256];
    private long[] arrivedRun = new long[256];
    private int arrived;

    // What was sent, in order.
    private Topic<?>[] topic = new Topic<?>[256];
    private int[] fingerprint = new int[256];
    private long[] sentRun = new long[256];
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
    private volatile Held arrays = new Held(0, inbox, subscription, event, arrivedRun, topic, fingerprint,
            sentRun, arrivedTo, sentTo, rounds, carried);

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
        if (sent == this.topic.length) {
            int n = sent * 2;
            this.topic = Arrays.copyOf(this.topic, n);
            this.fingerprint = Arrays.copyOf(this.fingerprint, n);
            this.sentRun = Arrays.copyOf(this.sentRun, n);
        }
        this.topic[sent] = topic;
        this.fingerprint[sent] = Objects.hashCode(event);
        this.sentRun[sent] = run;
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
        arrays = new Held(ticks, inbox, subscription, event, arrivedRun, topic, fingerprint, sentRun, arrivedTo,
                sentTo, this.rounds, this.carried);
    }

    /** What a replay reads: the whole ticks so far, as of now. Any thread. */
    Taken take() {
        boolean f = full;
        Held a = arrays;
        return new Taken(a.ticks, a, f);
    }

    /** The arrays as they were when a tick was last made whole: growing them makes new ones, never changes these. */
    private record Held(int ticks, int[] inbox, int[] subscription, Object[] event, long[] arrivedRun,
                        Topic<?>[] topic, int[] fingerprint, long[] sentRun, int[] arrivedTo, int[] sentTo,
                        int[] rounds, boolean[] carried) {
    }

    /** The recording's first {@code ticks} ticks, read without copying. */
    static final class Taken {
        final int ticks;
        private final Held a;
        final boolean full;

        private Taken(int ticks, Held a, boolean full) {
            this.ticks = ticks;
            this.a = a;
            this.full = full;
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
            return a.topic[i];
        }

        int fingerprint(int i) {
            return a.fingerprint[i];
        }

        long sentRun(int i) {
            return a.sentRun[i];
        }
    }
}
