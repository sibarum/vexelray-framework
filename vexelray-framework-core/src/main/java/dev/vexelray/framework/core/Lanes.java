package dev.vexelray.framework.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Where this application's threads come from — all of them that are not the main thread and not the timeline.
 *
 * <p>Three of the five lanes live here: the <b>handler lane</b>, the <b>offload lane</b>, and the
 * <b>component threads</b> minted by {@link #thread}. The other two are not this class's to make — the main
 * thread is the one the process started on, and the timeline is single-threaded by construction inside
 * Kronometer, entered from {@code FrameStage.CLOCK}. A sixth lane is a design change argued in
 * {@code docs/architecture.md}, not a pool added at a call site.
 *
 * <p><b>Why a type rather than a field on the container.</b> The point of owning the threads is that placement
 * is decided in the wiring rather than by how many trees an application happens to hold, and that is only true
 * if there is one answer to <i>which lane</i> for the whole application. Before this, a {@code Gui} built a
 * cached pool of its own whatever it was handed, so an application's thread count was a property of its window
 * count. One of these exists per application, the container makes it before anything else, and it is closed
 * last.
 *
 * <p><b>Platform threads, never virtual.</b> Measured rather than stylistic, and the reason is not about
 * blocking. An application following Kronometer's advice sets
 * {@code -Djdk.virtualThreadScheduler.parallelism=1}, worth 3x on the baton handoff; that is a global JVM
 * property with <i>no public per-thread scheduler</i> in JDK 25, so there is no second carrier to give these
 * lanes. A virtual lane would land on the one carrier the baton needs and deadlock against the serialization
 * that makes it fast, rather than merely running slowly. The flag is the application's to set and correctness
 * may not depend on it, so the default here has to be the one that is still right when it <em>is</em> set.
 *
 * <p>Every thread is a daemon, so a lane that is still working cannot hold the process open past the loop
 * ending. {@link #close} is the orderly path and it is bounded — see it for why stopping is on a timer.
 */
public final class Lanes implements AutoCloseable {

    /**
     * How long {@link #close} lets work in flight finish before it stops asking.
     *
     * <p>Bounded rather than patient, because shutdown is where an application with a wedged lane hangs on the
     * way out instead of on the way in — and a process that will not quit is worse than one that quits having
     * abandoned a file write. The framework's own answer to a bus fault makes the same trade for the same
     * reason: <i>a lost placement is the cheaper loss</i>.
     */
    private static final long STOP_NANOS = TimeUnit.SECONDS.toNanos(2);

    /**
     * Threads on the offload lane. Bounded, because a pool that answers a full queue by growing has chosen the
     * one policy the rest of this model refuses: the lane exists to absorb a stall, and an unbounded one
     * answers a wedged network mount by making a thread per blocked call, so the lane meant to absorb the
     * stall is what multiplies it.
     *
     * <p>Bounded in <em>threads</em>, not in queue. A full lane makes work wait behind the work already on it,
     * which is backpressure. A bounded queue would make it <em>fail</em>, and choosing loss is a decision taken
     * per channel by whoever knows what that channel carries — not a default a pool takes on their behalf.
     */
    private static final int OFFLOAD_THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());

    private final ExecutorService handlers;
    private final ExecutorService offload;
    /** Component threads, in the order they were placed. Stopped in reverse, like everything else. */
    private final List<Thread> components = new ArrayList<>();
    private volatile boolean closed;

    /** The lanes an ordinary application gets. */
    public Lanes() {
        this(Executors.newCachedThreadPool(named("handler")), boundedOffload());
    }

    /**
     * The lanes, with both supplied — for a test that wants the container's placement without its threads.
     *
     * <p>A same-thread executor ({@code Runnable::run}) for the handler lane is what makes a headless run
     * deterministic: input published on the bus is handled synchronously inside the frame, so a test has exact
     * control over what fires and when.
     */
    public Lanes(ExecutorService handlers, ExecutorService offload) {
        this.handlers = handlers;
        this.offload = offload;
    }

    /**
     * The lane input handlers run on — clicks, keys, chars, drags, state.
     *
     * <p>Unbounded in threads, deliberately and for now. A handler is meant to be short application code
     * answering an input event, and {@link #offload} is where anything that blocks belongs; but while blocking
     * handlers still exist in applications on this stack, bounding this lane would stop click dispatch dead
     * rather than reveal the defect. The bound arrives after the blocking handlers move.
     */
    public ExecutorService handlers() {
        return handlers;
    }

    /**
     * The lane for work that is genuinely unbounded — file I/O, network, an image decode — and which may
     * outlast any number of frames.
     *
     * <p><b>A pool here does not contradict static placement.</b> A component is placed statically because it
     * is stateful and ordered; an offloaded task is stateless and unordered, so there is nothing to confine and
     * no sequence to keep. What polices the edge is the shareable set rather than the placement: a task that
     * captures a component's state is the thing that must not compile.
     *
     * <p><b>A result never lands in place.</b> Work finishing here comes back through one of exactly two doors
     * — published on a topic and folded into a cell, or dropped on a queue drained in {@code FrameStage.APP}.
     * What a thread from this lane must never do is touch the tree or the timeline directly.
     */
    public ExecutorService offload() {
        return offload;
    }

    /**
     * Mint a component's own platform thread, started immediately, running {@code body} until it returns.
     *
     * <p>One component, one thread: the thread is the confinement domain, and the component's state is its
     * own thread's in the sense that nothing else reads it, writes it, or holds a reference to it. Which
     * component gets which thread is decided in the wiring and never at runtime — there is no work stealing
     * and no placement decision, and that one restriction is what lets the colour of a value be known while
     * compiling.
     *
     * <p>The {@code body} is expected to loop on its own mailbox and to return once {@link #closed} is true —
     * that flag is the stop signal, and the interrupt {@link #close} sends only shortens the park it may be
     * sitting in. A body that ignores both is joined until the shutdown timeout and then left to the process,
     * which is what being a daemon thread is for.
     *
     * @param name what the thread is called in a dump and in a report — the component's name, not a number
     */
    public Thread thread(String name, Runnable body) {
        if (closed) {
            throw new IllegalStateException("lanes are closed; " + name + " cannot be placed on one");
        }
        Thread t = new Thread(body, "vexel-component-" + name);
        t.setDaemon(true);
        components.add(t);
        t.start();
        return t;
    }

    /**
     * Stop everything, giving work in flight a bounded chance to finish first.
     *
     * <p>Drain then stop, in that order and on a timer. Not cosmetic: {@code FAIL} is the default backpressure
     * and it resolves through a process-wide fault, so getting shutdown ordering wrong kills the process rather
     * than dropping a message. Component threads are asked first and interrupted only if they outstay
     * {@link #STOP_NANOS}, because a component that is mid-publish has somewhere to put its result and an
     * interrupted one does not.
     *
     * <p>Reverse placement order, which is the same rule the container's {@code Disposer} uses everywhere else.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        long deadline = System.nanoTime() + STOP_NANOS;

        // The interrupt is how a component is *asked*: closed is already true, and a component's drain loop
        // reads it, does its last drain and returns. The interrupt only shortens the park it may be sitting
        // in, so a stop costs one drain period rather than the whole of one mailbox timeout.
        for (int i = components.size() - 1; i >= 0; i--) {
            components.get(i).interrupt();
        }
        for (int i = components.size() - 1; i >= 0; i--) {
            join(components.get(i), deadline);
        }
        components.clear();

        handlers.shutdown();
        offload.shutdown();
        awaitOrStop(handlers, deadline);
        awaitOrStop(offload, deadline);
    }

    /** Whether {@link #close} has run. A component checks this to know an interrupt was a shutdown. */
    public boolean closed() {
        return closed;
    }

    private static void join(Thread t, long deadline) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            return;
        }
        try {
            t.join(TimeUnit.NANOSECONDS.toMillis(left) + 1);
        } catch (InterruptedException e) {
            // The thread closing the lanes was itself interrupted. Stop waiting, keep the flag, and let the
            // daemon threads go with the process — the alternative is swallowing an interrupt during shutdown,
            // which is how a quit turns into a hang.
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitOrStop(ExecutorService lane, long deadline) {
        long left = deadline - System.nanoTime();
        try {
            if (left > 0 && lane.awaitTermination(left, TimeUnit.NANOSECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        lane.shutdownNow();
    }

    private static ExecutorService boundedOffload() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(OFFLOAD_THREADS, OFFLOAD_THREADS, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), named("offload"));
        // An idle application should not sit on threads it is not using, and letting the core time out makes
        // the bound a ceiling rather than a floor.
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * Daemon platform threads, named for their lane.
     *
     * <p>The name is load-bearing rather than decorative: "worker thread" used to mean the handler lane and the
     * offload lane both, which is one name for two lanes with different rules in the documentation applications
     * read — and in the thread dump they reach for when one of them is the problem.
     */
    private static ThreadFactory named(String lane) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "vexel-" + lane + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
