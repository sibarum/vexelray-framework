package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Slow work as a declaration: a request arrives on {@link #topic()}, runs on the offload lane, and its answer is
 * published on {@link #reply()}.
 *
 * <pre>{@code
 * @Job(topic = "file.read", reply = "file.opened")
 * public final class ReadFile {                         // no mutable state: checked
 *     public Opened run(OpenFile request) { ... }       // runs on the offload pool; declares no throws
 * }
 * record OpenFile(Path path) {}                         // the request: deeply immutable, as any payload
 * record Opened(Path path, String text, String problem) {}  // the reply carries what the caller needs
 * }</pre>
 *
 * <h2>Why a job and not a lambda</h2>
 *
 * <p>Offload used to be an {@code Executor} taking a {@code Runnable}, and a lambda can close over a component's
 * mutable fields: Java has no type that refuses one that does, so T2.5 (<i>a lambda that crosses a lane may capture
 * only what may cross</i>) was a rule for the reader. A job is not a closure. Its request and its reply are messages,
 * checked as every {@link Subscribe} payload is, and the class holds no state of its own to share, so there is
 * nothing to capture and nothing to check in a method body. Ruled 2026-10-26 ({@code docs/components.md}, section
 * 10), for the reason the user gave: the application's parts talk by message passing as far as it reaches.
 *
 * <h2>What it runs on, and what it does not promise</h2>
 *
 * <p>The offload lane: a bounded pool of platform threads, not a lane of its own. A job has no state to confine and
 * no order to keep, so two requests may run at once and their replies may arrive in either order. A caller that needs
 * an order — tabs restored as they were — sends the next request when the last reply arrives. The pool is not
 * watched by the liveness watchdog, which is the point of putting disk and network here rather than on a component's
 * lane: a read stuck on a network drive is a slow reply, not a stalled lane.
 *
 * <p>The reply is published on the offload thread, and whoever subscribes to it receives it on <em>their own</em>
 * lane, in order with the rest of their mail. A job never touches the tree, the timeline or a component.
 *
 * <h2>Failure</h2>
 *
 * <p><b>Every failure is either declared and handled, or it ends the application</b> (ruled 2026-10-18). A failure
 * the job expects — a file that is not there — is part of its reply, a field the subscriber cannot avoid reading,
 * and {@code run} declares no {@code throws}, so javac makes the job catch it. Anything else that escapes, an
 * {@code Error} included, goes to the process-wide fault policy, which halts, as a {@code @Subscribe} that throws
 * does.
 *
 * <h2>Checked and generated</h2>
 *
 * <p>{@code vexelray-framework-processor} holds: a final class with one constructor, injected like any part (a job
 * may not take a component or a main-thread value); final fields only, each of a shareable type or a type the
 * container supplies; one {@code run} method taking the request and returning the reply, or {@code void} for a job
 * run only for its effect, which then names no reply; request and reply deeply immutable; one job per topic; and,
 * in an application, at least one mailbox on the reply topic, since a reply nobody hears is a lost result. Nothing
 * holds a job: it is reached by publishing its topic. The generated wiring constructs it in the phase its parameters
 * put it in and subscribes it to its topic on the offload lane.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Stability(Stability.Level.EXPERIMENTAL)
public @interface Job {

    /** The topic requests arrive on. Its type is {@code run}'s parameter. */
    String topic();

    /**
     * The topic the answer is published on. Its type is {@code run}'s return type. Empty, the default, is a job run
     * only for its effect, whose {@code run} returns {@code void}.
     */
    String reply() default "";
}
