package dev.vexelray.framework.api;

/**
 * How this process was asked to run — the framework's equivalent of a Spring profile, and the reason every
 * application on this stack currently begins by picking apart {@code args} by hand.
 *
 * <p>Each of the three demo applications and the scaffold template parse the same flags, in slightly different
 * orders, with slightly different failure behaviour; one of them documents having thrown
 * {@code NumberFormatException} out of {@code main} — <i>"a stack trace, before any window, for a typo"</i>.
 * The mode is decided once, before any device is opened, because {@link #CAPTURE} in particular must never
 * reach the input backend.
 *
 * <p>A mode is not a flag. {@code --profile} and {@code --automation} are orthogonal to all of these and stay
 * separate switches on the launch record: profiling a windowed session and profiling a fixed-frame run are both
 * meaningful, so they are not alternatives.
 */
public enum RunMode {

    /** A session: a window, input, and a loop that runs until the user closes it. The default. */
    WINDOWED,

    /**
     * Render a fixed number of frames and exit. A script's mode, not a user's — the loop is real, the window is
     * real, and the exit is on a count rather than on a close.
     */
    FRAMES,

    /**
     * Render one frame to a PNG and exit, with no window shown and no input backend opened.
     *
     * <p>This is the mode that makes a GPU application testable in CI, and the reason the mode is settled
     * before anything is constructed rather than checked inside the loop: components that only exist to serve a
     * session — the input bridge, the clipboard, the automation socket — are never built, so a capture cannot
     * fail on a machine that has no backend for them.
     */
    CAPTURE
}
