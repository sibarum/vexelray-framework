package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Atchung;
import sibarum.atchung.Subscription;
import sibarum.atchung.Topic;
import sibarum.probe.Cause;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What the generated wiring calls to put a {@code @Destination} on its topics: a connection per key, made by the part's
 * {@code open}, written by its {@code write} methods on a thread of its own, read by its {@code run} on another when the
 * part is also a {@code @Source}, and closed once by its {@code close} ({@code docs/components.md}, section 12).
 *
 * <p><b>Not for application code</b>, on the terms {@link Inboxes} states: the generated wiring lives in the
 * application's package, so the door it needs is public, and this is that door.
 *
 * <h2>Connections, by key</h2>
 *
 * <p>{@link #keyed} opens one per key of the request's topic, as {@link Sources} starts one run per key: a request for a
 * key already open stops that connection, and the new one opens once the old one's threads have returned, so a key's
 * connections never overlap and its replies keep their order. {@link #atStart} opens one when the application starts.
 * Each connection's writer thread opens it (opening may block), then writes what it is handed, every message in the
 * order it was heard. A message heard before its connection is open waits for it; one for a key with no connection, or
 * one that is closing, is dropped and counted.
 *
 * <p>Requests, stops and writes are heard inline, on whatever thread delivers them, and only queue or interrupt there,
 * so they never block it.
 *
 * <h2>Stopping</h2>
 *
 * <p>A stop interrupts the run and the writer, then closes the connection from the stopping thread, which is what ends a
 * read that ignores the interrupt; a connection still opening is closed by its writer once {@code open} returns. A run
 * that returns stops its key. Every request gets its reply: a connection stopped before it opened still opens, and its
 * run starts interrupted, so its first read says so. At shutdown a part that reads is stopped before the logic's last
 * tick, as a source is, so its replies are delivered in it; one that only writes writes what was queued, then closes,
 * after the last tick, as an {@code AutoCloseable} part is closed. Both within a bound.
 *
 * <h2>Failure</h2>
 *
 * <p>As a job's ({@link Jobs}): what escapes {@code open}, {@code write}, {@code run} or {@code close}, and a reply of
 * {@code null}, goes to the process-wide fault policy, which halts. Except once the connection is stopping, when the
 * throw is most likely the stop itself, and is dropped and counted.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Destinations {

    /** How long shutdown waits for every connection's threads to return, in all. */
    static final long STOP_MILLIS = 2_000;

    private Destinations() {
    }

    /** One message type a destination writes: its topic, and the part's {@code write} method for it. */
    public static final class Write<C> {
        private final Topic<Object> topic;
        private final BiConsumer<C, Object> to;

        @SuppressWarnings("unchecked")
        private <M> Write(Topic<M> topic, BiConsumer<C, M> to) {
            this.topic = (Topic<Object>) topic;
            this.to = (BiConsumer<C, Object>) to;
        }
    }

    /** {@code to} writes each message on {@code topic} to the connection it is handed. */
    public static <C, M> Write<C> write(Topic<M> topic, BiConsumer<C, M> to) {
        return new Write<>(topic, to);
    }

    /**
     * Put the destination {@code part} on {@code request}: each request opens a connection for its key with {@code open},
     * on a writer thread named {@code vexel-destination-<thread>}, and each of {@code writes} is written to the
     * connection its key names. With {@code run}, the part is also a source: the run reads on a thread named
     * {@code vexel-source-<thread>} and what it returns is published on {@code reply}. A message on {@code stop}, when it
     * is not null, stops its key.
     *
     * @param part the part's class, by name: what a fault says failed, and the cause a reply begins
     */
    public static <Q, C, R> void keyed(Shell shell, String part, String thread, Topic<Q> request, Function<Q, C> open,
                                       BiFunction<Q, C, R> run, Topic<R> reply, Topic<?> stop, Consumer<C> close,
                                       List<Write<C>> writes) {
        Connections<Q, C, R> conns = new Connections<>(shell.bus(), part, thread, request, open, run, reply, close);
        conns.listen(writes);
        conns.subscriptions.add(shell.bus().subscribe(request, conns::start));
        if (stop != null) {
            conns.subscriptions.add(stopOn(shell.bus(), stop, conns));
        }
        if (run != null) {
            shell.disposer().beforeClose(() -> conns.stopAll(false));
        }
        shell.disposer().register(conns);
    }

    /**
     * Put the destination {@code part} on its {@code writes} with one connection, opened now by {@code open} on its
     * writer thread and closed at shutdown once what was queued is written.
     */
    public static <C> void atStart(Shell shell, String part, String thread, Supplier<C> open, Consumer<C> close,
                                   List<Write<C>> writes) {
        Connections<Object, C, Object> conns = new Connections<>(shell.bus(), part, thread, null,
                ignored -> open.get(), null, null, close);
        conns.listen(writes);
        conns.start(null);
        shell.disposer().register(conns);
    }

    private static <S> Subscription stopOn(Atchung bus, Topic<S> stop, Connections<?, ?, ?> conns) {
        return bus.subscribe(stop, message -> conns.stop(stop.keyFor(message)));
    }

    /** One destination's connections, by key. */
    private static final class Connections<Q, C, R> implements AutoCloseable {
        private final Atchung bus;
        private final String part;
        private final String thread;
        /** The request's topic, or null for the one connection opened at start. */
        private final Topic<Q> requests;
        private final Function<Q, C> open;
        private final BiFunction<Q, C, R> run;
        private final Topic<R> reply;
        private final Consumer<C> close;
        private final Cause.Source cause;
        /** The connection for each key, from its request until it is stopped. Under its own lock. */
        private final Map<Object, Connection> going = new HashMap<>();
        /** Every connection not yet done, stopped or not: what shutdown waits for. Under {@link #going}'s lock. */
        private final List<Connection> live = new ArrayList<>();
        private boolean closed;
        final List<Subscription> subscriptions = new ArrayList<>();

        Connections(Atchung bus, String part, String thread, Topic<Q> requests, Function<Q, C> open,
                    BiFunction<Q, C, R> run, Topic<R> reply, Consumer<C> close) {
            this.bus = bus;
            this.part = part;
            this.thread = thread;
            this.requests = requests;
            this.open = open;
            this.run = run;
            this.reply = reply;
            this.close = close;
            this.cause = Cause.source("source " + part, Cause.Kind.IO);
        }

        void listen(List<Write<C>> writes) {
            for (Write<C> w : writes) {
                subscriptions.add(bus.subscribe(w.topic, message -> write(w, message)));
            }
        }

        /** The key a message is for: its topic's, on a keyed connection; the one there is, otherwise. */
        private Object key(Topic<Object> topic, Object message) {
            return requests == null || !requests.keyed() ? null : topic.keyFor(message);
        }

        /** A request: stop the key's connection, if any, and open the new one behind it. Never blocks. */
        void start(Q message) {
            Object key = requests == null ? null : requests.keyFor(message);
            synchronized (going) {
                if (closed) {
                    dev.vexelray.diag.Diagnostics.dropped("destination " + part, "a request while stopping",
                            String.valueOf(message));
                    return;
                }
                Connection before = going.get(key);
                if (before != null) {
                    before.stop(false);
                }
                Connection next = new Connection(key, message, before);
                going.put(key, next);
                live.add(next);
                next.writer.start();
            }
        }

        /** A message to write: queued for its key's connection, or dropped when there is none. Never blocks. */
        void write(Write<C> w, Object message) {
            Object key = key(w.topic, message);
            Connection c;
            synchronized (going) {
                c = going.get(key);
            }
            if (c == null || !c.offer(w, message)) {
                dev.vexelray.diag.Diagnostics.dropped("destination " + part, "a message for no open connection ("
                        + key + ")", String.valueOf(message));
            }
        }

        /** A stop message: stop the key's connection, if one is open. */
        void stop(Object key) {
            Connection c;
            synchronized (going) {
                c = going.get(key);
            }
            if (c != null) {
                c.stop(false);
            }
        }

        /**
         * Shutdown: refuse new requests, stop every connection, and wait a bounded time for their threads. With
         * {@code drain}, each writer writes what was queued before its connection closes.
         */
        void stopAll(boolean drain) {
            List<Connection> all;
            synchronized (going) {
                closed = true;
                all = new ArrayList<>(live);
            }
            for (Connection c : all) {
                c.stop(drain);
            }
            long until = System.nanoTime() + STOP_MILLIS * 1_000_000;
            for (Connection c : all) {
                if (!c.join(until)) {
                    return;
                }
            }
        }

        @Override
        public void close() {
            stopAll(true);
            for (Subscription s : subscriptions) {
                s.close();
            }
        }

        private boolean closing() {
            synchronized (going) {
                return closed;
            }
        }

        /** Something escaped the part: a fault, unless the connection is stopping, when it is most likely the stop. */
        private void failed(String where, Connection c, Throwable e) {
            if (c.stopping() || closing()) {
                dev.vexelray.diag.Diagnostics.dropped("destination " + part, where + " while stopping", e.toString());
                return;
            }
            Atchung.fatal().fault(new IllegalStateException("destination " + part + " failed in " + where
                    + ", and nothing declared the failure: " + e, e));
        }

        /** One connection: its writer, its reader when the part reads, and the connection once it is open. */
        private final class Connection {
            /** What a writer takes to know it is to stop once what is before it is written. */
            private static final Object END = new Object();

            final Thread writer;
            /** Set by the writer once the connection is open; read by whoever stops it. */
            private volatile Thread reader;
            private final Object key;
            private final Q request;
            private Connection before;
            private final LinkedBlockingQueue<Object[]> queue = new LinkedBlockingQueue<>();
            private final AtomicBoolean closedOnce = new AtomicBoolean();
            /** Guarded by this connection. */
            private C connection;
            private boolean stopped;
            /** Stopped by shutdown, to write what is queued first: the writer goes on to {@link #END}. */
            private boolean draining;

            Connection(Object key, Q request, Connection before) {
                this.key = key;
                this.request = request;
                this.before = before;
                this.writer = new Thread(this::writerBody, "vexel-destination-" + thread);
                this.writer.setDaemon(true);
            }

            synchronized boolean stopping() {
                return stopped;
            }

            /** Queue {@code message} for the writer; false once the connection is stopping. */
            boolean offer(Write<C> w, Object message) {
                synchronized (this) {
                    if (stopped) {
                        return false;
                    }
                }
                queue.add(new Object[] {w, message});
                return true;
            }

            private void writerBody() {
                boolean interrupted = false;
                Connection b = before;
                before = null;
                if (b != null) {
                    // A key's connections never overlap: wait for the one stopped for this, through any stop of this
                    // one meanwhile, which then stops this one as soon as it opens.
                    while (!b.done()) {
                        try {
                            b.joinAll();
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    }
                }
                C c;
                try {
                    c = open.apply(request);
                    if (c == null) {
                        throw new IllegalStateException("open answered null, and a connection is owed");
                    }
                } catch (RuntimeException | Error e) {
                    failed("open", this, e);
                    forget();
                    return;
                }
                boolean stopNow;
                synchronized (this) {
                    connection = c;
                    // A drain that arrived while this opened still writes what was queued: only a stop is at once.
                    stopNow = (stopped && !draining) || interrupted;
                }
                if (run != null) {
                    reader = new Thread(() -> readerBody(c, stopNow), "vexel-source-" + thread);
                    reader.setDaemon(true);
                    reader.start();
                }
                if (stopNow) {
                    closeOnce(c);
                } else {
                    writeLoop(c);
                }
                if (reader != null) {
                    while (reader.isAlive()) {
                        try {
                            reader.join();
                        } catch (InterruptedException e) {
                            // stopped meanwhile: the close below ends the read
                            closeOnce(c);
                        }
                    }
                }
                closeOnce(c);
                forget();
            }

            /** Write what is queued, in order, until stopped: at once, or once what was before {@link #END} is written. */
            @SuppressWarnings("unchecked")
            private void writeLoop(C c) {
                while (true) {
                    Object[] next;
                    try {
                        next = queue.take();
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (next[0] == END) {
                        return;
                    }
                    Write<C> w = (Write<C>) next[0];
                    try {
                        w.to.accept(c, next[1]);
                    } catch (RuntimeException | Error e) {
                        failed("write of " + w.topic.name(), this, e);
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                }
            }

            private void readerBody(C c, boolean interrupted) {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                try {
                    R answer = run.apply(request, c);
                    if (answer == null) {
                        throw new IllegalStateException("answered null on " + reply.name() + ", and a reply is owed: a"
                                + " run's end, and why, belongs in the reply");
                    }
                    long previous = Cause.enter(cause.next());
                    try {
                        bus.publish(reply, answer);
                    } finally {
                        Cause.exit(previous);
                    }
                } catch (RuntimeException | Error e) {
                    failed("run on " + requests.name(), this, e);
                } finally {
                    stop(false);
                }
            }

            /**
             * Stop: no more writes are taken, the reader is interrupted, and the connection closed from here once it is
             * open. With {@code drain}, the writer writes what was queued first and closes it itself. Idempotent.
             */
            void stop(boolean drain) {
                C c;
                synchronized (this) {
                    if (stopped) {
                        return;
                    }
                    stopped = true;
                    draining = drain && run == null;
                    c = connection;
                }
                synchronized (going) {
                    if (going.get(key) == this) {
                        going.remove(key);
                    }
                }
                Thread r = reader;
                if (r != null) {
                    r.interrupt();
                }
                if (drain && run == null) {
                    queue.add(new Object[] {END, null});
                    return;
                }
                writer.interrupt();
                if (c != null) {
                    closeOnce(c);
                }
            }

            private void closeOnce(C c) {
                if (closedOnce.compareAndSet(false, true)) {
                    try {
                        close.accept(c);
                    } catch (RuntimeException | Error e) {
                        dev.vexelray.diag.Diagnostics.dropped("destination " + part, "closing", e.toString());
                    }
                }
            }

            private void forget() {
                synchronized (going) {
                    if (going.get(key) == this) {
                        going.remove(key);
                    }
                    live.remove(this);
                }
            }

            boolean done() {
                return !writer.isAlive() && (reader == null || !reader.isAlive());
            }

            void joinAll() throws InterruptedException {
                writer.join();
                Thread r = reader;
                if (r != null) {
                    r.join();
                }
            }

            /** Wait for this connection's threads until {@code until}, in nanos; false once the time is spent. */
            boolean join(long until) {
                for (Thread t : new Thread[] {writer, reader}) {
                    if (t == null) {
                        continue;
                    }
                    long left = (until - System.nanoTime()) / 1_000_000;
                    if (left <= 0) {
                        return false;
                    }
                    try {
                        t.join(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return true;
            }
        }
    }
}
