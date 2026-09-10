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
 * version is not privileged — it extends this class with constructor calls in phase order, which is exactly
 * what a hand-written one does. A code generator whose output has never been written by hand is a generator
 * whose output nobody has checked the shape of.
 *
 * <p><b>A class rather than an interface, because an interface here was a lambda.</b> With {@link #info} the
 * only abstract method and all six phase methods {@code default}, this was a functional interface by accident:
 * {@code VexelApplication.run(() -> myAppInfo, args)} compiled, and produced an application that parsed its
 * flags, opened its settings, built a {@code Gui} and a window, and ran a loop over an empty tree. No phase
 * refused, nothing threw, and the window came up blank — the framework's central contract satisfied by
 * something that cannot possibly have meant to satisfy it. That is the silent class of defect this container
 * exists to move to compile time, sitting in the container.
 *
 * <p>The other way to close it is a second abstract method, and it is worse: a method invented for no reason
 * but to stop the first one being alone. Extending costs an application nothing here — a wiring is a dedicated
 * class in all three ported applications and in anything the processor emits, so there is no second superclass
 * it wanted. And the phase defaults survive the change unaltered, because a {@code default} method and a
 * concrete one are the same empty body: <i>most applications have nothing in most phases</i> either way.
 */
public abstract class Wiring {

    /** The facts from {@code @VexelApp}, as a constant. Nothing reads the annotation at runtime. */
    public abstract AppInfo info();

    /**
     * {@link Phase#CONFIG} — settings-derived values, and the look.
     *
     * <p>The one phase with something an application almost always wants to say: {@link Shell#appearance} is
     * only accepted here, which is what makes "the theme is applied before the first widget" structural.
     */
    public void config(Shell shell) {
    }

    /** {@link Phase#MODEL} — what the application knows, before there is anything to draw it with. */
    public void model(Shell shell) {
    }

    /** {@link Phase#GUI} — components needing the {@code Gui} or the clock, but not a window. */
    public void gui(Shell shell) {
    }

    /** {@link Phase#TREE} — the widgets. Buildable with no window, which is what makes a capture possible. */
    public void tree(Shell shell) {
    }

    /**
     * {@link Phase#WINDOW} — components needing the device or the window handle.
     *
     * <p>Everything reached from here is main-thread-only.
     */
    public void window(Shell shell) {
    }

    /**
     * {@link Phase#ATTACH} — wiring that needed the window to exist: frame hooks, deadlines, wakes, and
     * anything pointing a widget at real window controls.
     */
    public void attach(Shell shell) {
    }
}
