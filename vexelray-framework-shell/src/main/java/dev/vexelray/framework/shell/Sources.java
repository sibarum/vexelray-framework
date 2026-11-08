package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Closer;
import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Atchung;
import sibarum.atchung.Sender;
import sibarum.atchung.Subscription;
import sibarum.atchung.Topic;
import sibarum.probe.Cause;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * What the generated wiring calls to put a {@code @Source} on its topics: each request starts a run on a thread of its
 * own, one run per key of the request, and what the run returns is published on the reply topic.
 *
 * <p><b>Not for application code</b>, on the terms {@link Inboxes} states: the generated wiring lives in the
 * application's package, so the door it needs is public, and this is that door.
 *
 * <h2>Runs, by key</h2>
 *
 * <p>The request's topic says what a key is ({@code Topic.keyFor}); an unkeyed topic is one key, so one run. A request
 * for a key whose run is going stops that run and starts a new one, which waits for the old one to return before it
 * does any work, so a key's replies come in the order its requests did and two runs of one key never overlap. A
 * request for another key starts beside it. Every request gets one reply: a run stopped before it began starts with
 * its thread interrupted, so its first read says so and it returns.
 *
 * <p>Requests and stops are heard inline, on whatever thread delivers them (the logic lane's, at the end of a tick),
 * and only start or interrupt a thread there, so they never block it.
 *
 * <h2>Stopping</h2>
 *
 * <p>A stop interrupts the run's thread, then closes what the run handed its {@link Closer}, from the stopping thread.
 * At shutdown every run is stopped and waited for, a bounded time, before anything is closed, so its reply is staged
 * before the logic's last tick and delivered in it. A request after that is dropped, and said so.
 *
 * <h2>Failure</h2>
 *
 * <p>As a job's ({@link Jobs}): what escapes {@code run}, and a reply of {@code null}, goes to the process-wide fault
 * policy, which halts. Except once the source is closed for shutdown, when the throw is the framework's interrupt.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Sources {

    /** How long shutdown waits for every run to return, in all: a run that ignores its stop is left behind. */
    static final long STOP_MILLIS = 2_000;

    private Sources() {
    }

    /**
     * A sender for a source's run: each message begins a cause of the source's own, since it arrives from outside as
     * input does ({@code Sender.outside}).
     */
    public static <T> Sender<T> sender(Shell shell, String source, Topic<T> topic) {
        return Sender.outside(shell.bus(), topic, cause(source));
    }

    /**
     * Put the source {@code source} on {@code request}: each request starts a run of {@code run} on a thread named
     * {@code vexel-source-<thread>}, and what it returns is published on {@code reply}. A message on {@code stop}, when
     * it is not null, stops the run for its key.
     *
     * @param source the source's class, by name: what a fault says failed, and the cause its messages begin
     */
    public static <Q, R> void run(Shell shell, String source, String thread, Topic<Q> request,
                                  BiFunction<Q, Closer, R> run, Topic<R> reply, Topic<?> stop) {
        if (shell.replaying()) {
            return;   // a replay is told what each run sent, and when, by its recording
        }
        Runs<Q, R> runs = new Runs<>(shell.bus(), source, "vexel-source-" + thread, request, run, reply);
        runs.request = shell.bus().subscribe(request, runs::start);
        if (stop != null) {
            runs.stop = stopOn(shell.bus(), stop, runs);
        }
        shell.disposer().beforeClose(runs::stopAll);
        shell.disposer().register(runs);
    }

    private static <S> Subscription stopOn(Atchung bus, Topic<S> stop, Runs<?, ?> runs) {
        return bus.subscribe(stop, message -> runs.stop(stop.keyFor(message)));
    }

    private static Cause.Source cause(String source) {
        return Cause.source("source " + source, Cause.Kind.IO);
    }

    /** One source's runs, by key. */
    private static final class Runs<Q, R> implements AutoCloseable {
        private final Atchung bus;
        private final String source;
        private final String thread;
        private final Topic<Q> requests;
        private final BiFunction<Q, Closer, R> run;
        private final Topic<R> reply;
        private final Cause.Source cause;
        /** The run going for each key, or stopping and not yet returned. Under its own lock. */
        private final Map<Object, Run> going = new HashMap<>();
        private boolean closed;
        Subscription request;
        Subscription stop;

        Runs(Atchung bus, String source, String thread, Topic<Q> requests, BiFunction<Q, Closer, R> run,
             Topic<R> reply) {
            this.bus = bus;
            this.source = source;
            this.thread = thread;
            this.requests = requests;
            this.run = run;
            this.reply = reply;
            this.cause = cause(source);
        }

        /** A request: stop the key's run, if any, and start the new one behind it. Never blocks. */
        void start(Q message) {
            Object key = requests.keyFor(message);
            synchronized (going) {
                if (closed) {
                    dev.vexelray.diag.Diagnostics.dropped("source " + source, "a request while stopping",
                            String.valueOf(message));
                    return;
                }
                Run before = going.get(key);
                if (before != null) {
                    before.stop();
                }
                Run next = new Run(key, message, before);
                going.put(key, next);
                next.thread.start();
            }
        }

        /** A stop message: stop the key's run, if one is going. */
        void stop(Object key) {
            synchronized (going) {
                Run r = going.get(key);
                if (r != null) {
                    r.stop();
                }
            }
        }

        /** Shutdown begins: stop every run, refuse new ones, and wait a bounded time for them to return. */
        void stopAll() {
            List<Run> all;
            synchronized (going) {
                closed = true;
                all = new ArrayList<>(going.values());
            }
            for (Run r : all) {
                r.stop();
            }
            long until = System.nanoTime() + STOP_MILLIS * 1_000_000;
            for (Run r : all) {
                long left = (until - System.nanoTime()) / 1_000_000;
                if (left <= 0) {
                    break;
                }
                try {
                    r.thread.join(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        @Override
        public void close() {
            stopAll();
            request.close();
            if (stop != null) {
                stop.close();
            }
        }

        /** One run: its thread, the run before it on the same key, and what it handed over to close. */
        private final class Run implements Closer {
            final Thread thread;
            private final Object key;
            private final Q message;
            private Run before;
            /** Guarded by this run. */
            private final List<AutoCloseable> toClose = new ArrayList<>(1);
            private boolean stopped;

            Run(Object key, Q message, Run before) {
                this.key = key;
                this.message = message;
                this.before = before;
                this.thread = new Thread(this::body, Runs.this.thread);
                this.thread.setDaemon(true);
            }

            private void body() {
                boolean interrupted = false;
                Run b = before;
                before = null;
                if (b != null) {
                    // A key's runs never overlap, and their replies keep their order: wait for the one stopped for
                    // this, through any stop of this one meanwhile, which the interrupt below then reports.
                    while (b.thread.isAlive()) {
                        try {
                            b.thread.join();
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    }
                }
                synchronized (this) {
                    if (stopped || interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                try {
                    R answer = run.apply(message, this);
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
                    boolean closing;
                    synchronized (going) {
                        closing = closed;
                    }
                    if (closing) {
                        dev.vexelray.diag.Diagnostics.dropped("source " + source, "a run while stopping",
                                e.toString());
                    } else {
                        Atchung.fatal().fault(new IllegalStateException("source " + source + " failed on "
                                + requests.name() + ", and nothing declared the failure: " + e, e));
                        throw e;
                    }
                } finally {
                    synchronized (this) {
                        toClose.clear();
                    }
                    synchronized (going) {
                        if (going.get(key) == this) {
                            going.remove(key);
                        }
                    }
                }
            }

            /** Interrupt the run, then close what it handed over. Any thread; idempotent. */
            void stop() {
                List<AutoCloseable> closing;
                synchronized (this) {
                    if (stopped) {
                        return;
                    }
                    stopped = true;
                    closing = new ArrayList<>(toClose);
                    toClose.clear();
                }
                thread.interrupt();
                for (AutoCloseable c : closing) {
                    close(c);
                }
            }

            @Override
            public void closeOnStop(AutoCloseable resource) {
                synchronized (this) {
                    if (!stopped) {
                        toClose.add(resource);
                        return;
                    }
                }
                close(resource);
            }

            private void close(AutoCloseable resource) {
                try {
                    resource.close();
                } catch (Exception e) {
                    dev.vexelray.diag.Diagnostics.dropped("source " + source, "closing on stop", e.toString());
                }
            }
        }
    }
}
