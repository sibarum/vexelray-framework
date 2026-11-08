package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What writes outside the application: a pseudo-terminal's input, a socket's sending half, a log file, a recording. The
 * mirror of a {@link Source}, and a part may be both, which is a connection read and written: a terminal's shell, a
 * socket, a serial port.
 *
 * <pre>{@code
 * @Source(stop = CloseShell.class) @Destination
 * public final class Shell {
 *     private final Sender<Output> output;
 *     public Shell(Sender<Output> output) { this.output = output; }
 *
 *     public Pty open(OpenShell request) { ... }               // the connection, per key of the request
 *     public ShellEnded run(OpenShell request, Pty pty) { ... } // the source half: reads, and sends as it goes
 *     public void write(Pty pty, Typed typed) { ... }          // the destination half, one method per message heard
 *     public void write(Pty pty, Resized resized) { ... }
 *     public void close(Pty pty) { ... }                       // once, after both halves are done with it
 * }
 * }</pre>
 *
 * <p><b>The framework holds the connection</b>, what {@code open} returns, and hands it to each method; the part's
 * fields are final, as a job's are. {@code open} taking the request makes one connection per key of the request's
 * topic, and a request for a key already open stops that one first. {@code open} taking nothing makes one connection,
 * opened when the application starts and closed at shutdown.
 *
 * <p><b>Each {@code write} hears one message type</b>, the connection first: every message, in the order sent, on the
 * key's writer thread ({@code vexel-destination-<name>}), a thread of its own because a write may block. On a keyed
 * connection a message goes to the connection its topic's key names; on an unkeyed one, to the one there is. Messages
 * for a key still opening wait for it, and one for a key with no connection is dropped and counted.
 *
 * <p><b>With {@link Source}, {@code run} is the reading half:</b> it takes the request and the connection, sends as it
 * goes, and returns why it ended, published as a source's reply is. A run that returns stops its key. {@code close}
 * runs once per connection, after the run and the writer are done with it; closing is what ends a read that ignores an
 * interrupt, so a part with a connection takes no {@link Closer}.
 *
 * <h2>Checked and generated</h2>
 *
 * <p>{@code vexelray-framework-processor} holds a destination to a job's rules (a final class, one constructor, final
 * fields, no {@code throws}, held by nothing) and to its own: exactly one {@code open}, at least one {@code void
 * write(connection, message)} whose message is shareable and has a declared topic, and exactly one {@code void
 * close(connection)}. Listed with {@link Configuration#destinations()} when it is compiled in another module.
 *
 * <p>Ruled 2026-11-01 ({@code docs/components.md}, section 12): a destination is a kind of part, and its writer a lane.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Stability(Stability.Level.EXPERIMENTAL)
public @interface Destination {

    /**
     * The message that closes the connection for its key, by class literal, for a destination that is not also a
     * {@link Source} (a source names its stop on {@link Source#stop()}). {@code void.class}, the default, for one closed
     * only by a newer request or by shutdown.
     */
    Class<?> stop() default void.class;
}
