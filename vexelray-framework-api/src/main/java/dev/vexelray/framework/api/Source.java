package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What keeps producing from outside the application for as long as it runs: a socket, a stream, a child process, a
 * device. A {@link Job} that sends while it runs, on a thread of its own.
 *
 * <pre>{@code
 * @Source(stop = Detach.class)
 * public final class LinkRows {                          // final fields only, as a job's
 *     private final Sender<RowBatch> rows;
 *     public LinkRows(Sender<RowBatch> rows) { this.rows = rows; }   // what it sends, declared by construction
 *
 *     public Detached run(Attach request) {              // one run per request, on vexel-source-link-rows
 *         try (LinkClient link = LinkClient.open(request.discovery())) {
 *             for (RowBatch b; (b = link.next()) != null; ) {
 *                 rows.send(b);                          // as it goes
 *             }
 *             return new Detached(request.run(), Detached.Why.WENT_AWAY);
 *         } catch (ClosedByInterruptException stopped) {
 *             return new Detached(request.run(), Detached.Why.STOPPED);
 *         } catch (IOException e) {
 *             return new Detached(request.run(), Detached.Why.failed(e));
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p><b>A run starts on a request and ends by returning.</b> The request is a message on its payload's topic, as a
 * job's is, and what {@code run} returns is published on its own payload's topic: why the run ended, carrying what the
 * caller needs to tell which run it was. Per-run state is local to {@code run}; the class holds what a job may hold.
 *
 * <p><b>A thread of its own per run</b>, a platform thread named {@code vexel-source-<name>}, started when the run
 * starts and gone when it returns. Not the job lane: a run that blocks for an hour would hold one of the job lane's
 * few threads for good.
 *
 * <p><b>One run per key, as many keys as there are.</b> The request's topic decides: on a keyed topic
 * ({@code Topic.edge(Attach.class, a -> a.target())}) each key has a run of its own, side by side, one connection per
 * peer; an unkeyed topic is one key, so one run. A request for a key whose run is going stops that run, and the new
 * one starts once it has returned, which is reconnecting. Every request gets its reply, a run stopped before it
 * began its work included (it starts with its thread already interrupted, so its first read says so). A message of
 * {@link #stop}'s type stops the run for its key, read by its own topic's key, without starting another.
 *
 * <p><b>Stopping interrupts the run's thread.</b> A read on an {@code InterruptibleChannel} (a socket or file channel)
 * then throws {@code ClosedByInterruptException}, and the run returns its reply. A read that ignores an interrupt, a
 * process's output, is ended by closing what it reads from: {@code run} may take a {@link Closer} as its second
 * parameter and hand that over. At shutdown every run is stopped before the logic's last tick, so their replies are
 * delivered in it.
 *
 * <p><b>What it sends arrives from outside</b>, as input does: each message begins a cause of its own, named after the
 * source, so a trace finds where it came from. Its sends are ordinary publishes. A source that can outrun the logic
 * batches what it sends (rows by the batch, not by the row); a mailbox it overflows all the same fails as any does,
 * since nothing better can be done. The liveness watchdog does not watch a source: a run blocked in a read is
 * resting, not wedged.
 *
 * <h2>Checked and generated</h2>
 *
 * <p>{@code vexelray-framework-processor} holds a source to everything it holds a {@link Job} to, except that it may
 * take a {@code Sender<T>} for each topic it sends on, that {@code run} may take a {@link Closer} after its request,
 * and that it must return a reply: a run's end is always news. The stop message, when named, has a declared topic, and is deeply immutable. One part per request type,
 * job or source. Listed with {@link Configuration#sources()} when it is compiled in another module.
 *
 * <p>Ruled 2026-10-30 ({@code docs/components.md}, section 11): a source is a kind of part, and its thread a lane.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Stability(Stability.Level.EXPERIMENTAL)
public @interface Source {

    /**
     * The message that stops the run for its key without starting another, by class literal: a record of the
     * application's own with a declared topic, as any message, keyed as the request's is. {@code void.class}, the
     * default, for a source that is only ever stopped by a newer request or by shutdown.
     */
    Class<?> stop() default void.class;
}
