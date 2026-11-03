package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Atchung;
import sibarum.atchung.Subscription;
import sibarum.atchung.Topic;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * What the generated wiring calls to put a {@code @Job} on its topic: each request runs on the job lane, and what
 * it returns is published on the reply topic.
 *
 * <p><b>Not for application code</b>, on the terms {@link Inboxes} states: the generated wiring lives in the
 * application's package, so the door it needs is public, and this is that door. A job an application subscribes by
 * hand is one the processor never saw, so its messages are unchecked and its reply is not in the graph.
 *
 * <h2>Why an async subscription and not a mailbox</h2>
 *
 * <p>A mailbox is drained by a lane, in order, one delivery at a time, and that is exactly what a job must not have:
 * it holds no state to confine and no order to keep, and a read stuck on a network drive must not hold up the next
 * request or be taken by the watchdog for a stalled component. So the request goes straight to the job lane, a
 * pool bounded in threads ({@code Lanes.jobs}): a full pool makes requests wait behind the work on it, which is
 * backpressure, and never drops one. The reply is published from the job lane's thread, and every subscriber receives it
 * on its own lane.
 *
 * <h2>Failure</h2>
 *
 * <p>Ruled 2026-10-18: a failure is declared and handled, or it ends the application. A job declares no
 * {@code throws}, so what it expects is in its reply; what escapes {@code run}, and a reply of {@code null} where one
 * is owed, goes to the process-wide fault policy, which halts, as a {@code @Subscribe} that throws does
 * ({@code Logic}). Except once the job is closed for shutdown, when the throw is the framework's interrupt.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public final class Jobs {

    private Jobs() {
    }

    /**
     * Subscribe {@code run} to {@code topic} on the job lane, publishing each answer on {@code reply}.
     *
     * @param job the job's class, by name: what a fault says failed
     */
    public static <Q, R> void reply(Shell shell, String job, Topic<Q> topic, Function<Q, R> run, Topic<R> reply) {
        Atchung bus = shell.bus();
        subscribe(shell, job, topic, request -> {
            R answer = run.apply(request);
            if (answer == null) {
                throw new IllegalStateException("answered null on " + reply.name() + ", and a reply is owed: a"
                        + " failure the job expects belongs in the reply");
            }
            bus.publish(reply, answer);
        });
    }

    /** Subscribe {@code run} to {@code topic} on the job lane, for a job run only for its effect. */
    public static <Q> void effect(Shell shell, String job, Topic<Q> topic, Consumer<Q> run) {
        subscribe(shell, job, topic, run);
    }

    private static <Q> void subscribe(Shell shell, String job, Topic<Q> topic, Consumer<Q> run) {
        if (shell.replaying()) {
            return;   // a replay is told what each job answered, and when, by its recording
        }
        Closing closing = new Closing();
        Subscription subscription = shell.bus().subscribeAsync(topic, request -> {
            try {
                run.accept(request);
            } catch (RuntimeException | Error e) {
                if (closing.closed) {
                    dev.vexelray.diag.Diagnostics.dropped("job " + job, "a request while stopping", e.toString());
                    return;
                }
                Atchung.fatal().fault(new IllegalStateException("job " + job + " failed on " + topic.name()
                        + ", and nothing declared the failure: " + e, e));
                throw e;
            }
        }, shell.jobLane());
        closing.subscription = subscription;
        shell.disposer().register(closing);
    }

    /** The subscription, closed with the application before the lanes are, and whether it has been. */
    private static final class Closing implements AutoCloseable {
        volatile boolean closed;
        Subscription subscription;

        @Override
        public void close() {
            closed = true;
            subscription.close();
        }
    }
}
