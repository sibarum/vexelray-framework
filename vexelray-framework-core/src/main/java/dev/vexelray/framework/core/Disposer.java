package dev.vexelray.framework.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Closes what the container built, in reverse construction order, without letting one failure hide the rest.
 *
 * <p>This replaces a nested try-with-resources, and it is worth being precise about what is gained, because
 * try-with-resources is the better construct wherever it reaches. What it cannot do is close a set of resources
 * whose membership was decided by the graph: the application edge in {@code mainframe-template} nests three
 * resources in one statement and can, because it knows all three at the time it is written. A container does
 * not — which resources exist depends on which providers contributed, which depends on the run mode and the
 * classpath — so the reverse ordering that nesting gives for free has to be kept explicitly here.
 *
 * <p><b>Reverse order, and it is not a nicety.</b> The GPU device outlives everything drawn with it and the
 * window outlives the input attached to it, so closing forwards means closing a device out from under a
 * resource that still holds a handle to it. On this stack that surfaces as a driver-level crash during
 * shutdown: after the last frame, hard to reproduce, and easy to dismiss as "it was exiting anyway".
 *
 * <p><b>Every close is attempted.</b> A throwing close is recorded and the walk continues, because the
 * remaining resources are native handles and skipping them leaks them. The first failure is rethrown with the
 * later ones attached as suppressed exceptions, which is the same shape try-with-resources produces and reads
 * the same way in a log.
 */
public final class Disposer implements AutoCloseable {

    private final List<AutoCloseable> resources = new ArrayList<>();

    private boolean closed;

    /**
     * Register {@code resource} as constructed now, to be closed before everything registered before it.
     *
     * <p>{@code null} is accepted and ignored, because an absent optional backend is expected on this stack —
     * a provider for a missing input or clipboard backend answers {@code null} rather than throwing, and the
     * caller should not have to test for it before registering.
     *
     * @return {@code resource}, so a call can wrap a construction expression
     */
    public <T extends AutoCloseable> T register(T resource) {
        if (closed) {
            throw new IllegalStateException("already closed; nothing more can be registered");
        }
        if (resource != null) {
            resources.add(resource);
        }
        return resource;
    }

    /** How many resources are registered. */
    public int size() {
        return resources.size();
    }

    /**
     * Close everything, newest first. Idempotent: a second call does nothing, so this is safe both in a
     * {@code finally} and in a shutdown path that may also have been reached normally.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Throwable failure = null;
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (Throwable t) {
                if (failure == null) {
                    failure = t;
                } else if (t != failure) {
                    failure.addSuppressed(t);
                }
            }
        }
        resources.clear();
        if (failure instanceof RuntimeException e) {
            throw e;
        }
        if (failure instanceof Error e) {
            throw e;
        }
        if (failure != null) {
            // A checked exception from a close, which the container's own signature cannot declare. Wrapped
            // rather than swallowed: shutdown has already completed by this point, so the only question left
            // is whether anyone hears about it.
            throw new IllegalStateException("shutdown failed", failure);
        }
    }
}
