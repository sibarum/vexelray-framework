package dev.vexelray.framework.api;

/**
 * How this process was asked to run — the framework's equivalent of a Spring profile, and the reason every
 * application on this stack used to begin by picking apart {@code args} by hand.
 *
 * <p>Each of the demo applications and the scaffold template parsed the same flags, in slightly different
 * orders, with slightly different failure behaviour; one of them documents having thrown
 * {@code NumberFormatException} out of {@code main} — <i>"a stack trace, before any window, for a typo"</i>.
 *
 * <p>A mode is not a flag. {@code --profile} and {@code --automation} are orthogonal to both of these and stay
 * separate switches on the launch record: profiling a windowed session and profiling a fixed-frame run are both
 * meaningful, so they are not alternatives.
 *
 * <h2>There was a third, and it is gone</h2>
 *
 * <p>A {@code CAPTURE} mode used to sit here: build the tree, render one frame to a PNG through
 * {@code GuiApp.capture}, exit, with no window shown and no input backend opened. It was removed rather than
 * kept, because it could not photograph what the application actually draws.
 *
 * <p>{@code GuiApp.capture} is {@code static} and builds its <b>own</b> instance and device for the occasion,
 * while a {@code SampledColorTarget} comes from a {@code GuiApp} <b>instance</b>, on that application's device.
 * A target from another device yields a descriptor set the pipeline cannot bind, so a capture of a tree
 * carrying a marched viewport draws the framework's placeholder texture instead of the scene. It does not fail.
 * It produces a picture that is <b>correct about the chrome and silently wrong about the content</b>, which is
 * worse than no capture at all: a visual record that lies is one somebody will trust.
 *
 * <p>{@code WindowInstrument.screenshot()} replaces it and is strictly better — a real window on the
 * application's own device, so the content is the content. It is per-window rather than main-window-only, it is
 * reachable by hand from the title bar the framework now owns, and the same capability answers
 * {@code Automation}'s {@code shot} command for a script. An application that specifically wants a chrome-only
 * still can still call {@code GuiApp.capture} itself, knowing what it is getting.
 */
public enum RunMode {

    /** A session: a window, input, and a loop that runs until the user closes it. The default. */
    WINDOWED,

    /**
     * Render a fixed number of frames and exit. A script's mode, not a user's — the loop is real, the window is
     * real, and the exit is on a count rather than on a close.
     *
     * <p>Deliberately does not park between frames: a run that exists to finish as fast as it can gains nothing
     * from render-on-demand, and pacing it would only make it take longer.
     */
    FRAMES
}
