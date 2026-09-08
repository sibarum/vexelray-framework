package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Phase;

/**
 * What the annotation processor generates, and the only thing {@link VexelApplication} knows about an
 * application: one method per {@link Phase}, called in order.
 *
 * <p><b>A sink, not a dispatch.</b> The obvious shape for this is a single {@code build(Phase, Shell)}, and it
 * is the wrong one — it forces every implementation to open a {@code switch} over {@code Phase} with a
 * {@code default} nobody wants, which is the thing {@code vexelray-gui/CLAUDE.md} rules out on the grounds that
 * it <i>"puts behaviour outside the type it belongs to"</i>. Inverting it to a method per phase costs nothing
 * and buys two things: the runtime makes six direct calls with no dispatch at all, and adding a phase later is
 * a compile-time conversation with every implementation rather than a silently unhandled case.
 *
 * <p>The methods default to doing nothing, and that is a real answer rather than a swallowed one — most
 * applications have nothing in most phases. What an implementation must not do is put work in the wrong one:
 * {@link Shell}'s accessors refuse to hand over what does not exist yet, and once the processor exists it
 * rejects a backwards dependency outright.
 *
 * <p><b>Hand-writing one of these is supported, and is how the framework is being built.</b> The generated
 * version is not privileged — it implements this interface with constructor calls in phase order, which is
 * exactly what a hand-written one does. A code generator whose output has never been written by hand is a
 * generator whose output nobody has checked the shape of.
 */
public interface Wiring {

    /** The facts from {@code @VexelApp}, as a constant. Nothing reads the annotation at runtime. */
    AppInfo info();

    /**
     * {@link Phase#CONFIG} — settings-derived values, and the look.
     *
     * <p>The one phase with something an application almost always wants to say: {@link Shell#appearance} is
     * only accepted here, which is what makes "the theme is applied before the first widget" structural.
     */
    default void config(Shell shell) {
    }

    /** {@link Phase#MODEL} — what the application knows, before there is anything to draw it with. */
    default void model(Shell shell) {
    }

    /** {@link Phase#GUI} — components needing the {@code Gui} or the clock, but not a window. */
    default void gui(Shell shell) {
    }

    /** {@link Phase#TREE} — the widgets. Buildable with no window, which is what makes a capture possible. */
    default void tree(Shell shell) {
    }

    /**
     * {@link Phase#WINDOW} — components needing the device or the window handle.
     *
     * <p>Everything reached from here is main-thread-only.
     */
    default void window(Shell shell) {
    }

    /**
     * {@link Phase#ATTACH} — wiring that needed the window to exist: frame hooks, deadlines, wakes, and
     * anything pointing a widget at real window controls.
     */
    default void attach(Shell shell) {
    }
}
