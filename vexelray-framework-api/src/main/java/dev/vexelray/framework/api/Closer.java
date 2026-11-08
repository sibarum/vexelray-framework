package dev.vexelray.framework.api;

/**
 * What a {@link Source}'s run hands the framework to close when it is stopped: the run's optional second parameter.
 *
 * <pre>{@code
 * public Exited run(Launch request, Closer closer) {
 *     Process p = ...;
 *     closer.closeOnStop(p.getInputStream());     // a read on it ignores an interrupt; closing it ends the read
 *     ...
 * }
 * }</pre>
 *
 * <p>Stopping a run interrupts its thread, which ends a read on an {@code InterruptibleChannel}. A read on anything
 * else, a process's output or most {@code InputStream}s, ignores the interrupt, and the standard way to end it is to
 * close what it reads from. The resource is the run's, local to {@code run}, since a source's fields are final; so the
 * run hands it over here, and the framework closes it from the stopping thread, after the interrupt.
 *
 * <p>Only for that. What the run opened it still closes itself when it returns, as it would anyway; a resource handed
 * over is closed by the framework only if the run is stopped. One handed over after the run was stopped is closed at
 * once. Belongs to one run, and means nothing on another thread or after the run has returned.
 */
@Stability(Stability.Level.EXPERIMENTAL)
public interface Closer {

    /** Close {@code resource} if this run is stopped, to end a read that an interrupt does not. */
    void closeOnStop(AutoCloseable resource);
}
