package dev.vexelray.framework.shell;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.krono.Fades;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.krono.Presence;
import dev.vexelray.gui.krono.Transitions;
import dev.vexelray.gui.widget.Unclip;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;

/**
 * The motion every window the framework builds is given, so that nothing in it changes in one frame unless something
 * says it should.
 *
 * <p><b>On by default, because opt-in motion is motion nobody has.</b> Each of these was once a line an application
 * had to know to write, and every application generated from the template shipped without most of them: rows that
 * popped in, lists that jumped when one grew, a second window with no fades at all because only the main one had
 * them (the scheduler's FN-1, and what each later retrospective found again). Said here, once per tree, they are what
 * an application starts with, and taking part is what it does not have to remember.
 *
 * <ul>
 *   <li><b>Colour</b> fades ({@link Fades}): a hover, a press, a selection, a theme switch. Linear, because a colour
 *       arrives nowhere; short, because feedback that trails the pointer reads as a slow application.</li>
 *   <li><b>A cut-off name revealed</b> fades the same way everywhere ({@link Unclip}): quick and symmetric, since it
 *       is the thing itself and not help arriving.</li>
 *   <li><b>A node layout moved</b> slides there ({@link Transitions}), enrolled over the whole tree, so the rows below
 *       an opened panel and the card below them cover the distance together rather than tearing along a boundary.
 *       A resize or a zoom is not a move, and neither is a node moved on every frame by a drag or by someone else's
 *       ramp: vexelray-gui's {@code Displacement.settle} follows those rather than chasing them.</li>
 *   <li><b>A node added</b> fades up and rises a little into its place; <b>one removed</b> fades out where it stands
 *       before it leaves, and stops taking the pointer at once ({@link Presence}). A virtualised list's rows are a
 *       view the user scrolled, not additions, and its container says so ({@code Node.childMotion(false)}).</li>
 *   <li><b>A zoom step</b> glides ({@code Gui.zoomGlide}): the zoom keys re-lay the whole window, which in one frame is
 *       the largest jump a window can make (the scheduler's FN-6). {@code Gui.zoom(float)} stays at once.</li>
 * </ul>
 *
 * <p><b>What an application still decides.</b> A node whose colour is data rather than state says
 * {@code paintCuts(true)}. A tree that wants other timings attaches its own sources with {@code gui.paintMotion},
 * {@code gui.motion}, {@code gui.enterMotion} and {@code gui.exitMotion}: one of each per tree, and the last call wins.
 * Reduced motion and simplified graphics are the user's, and every one of these lands at its end while they are on.
 *
 * <p><b>What it costs the code that is on screen</b>, which is the one thing default motion asks of it: keep the nodes
 * and change them. A list rebuilt from new nodes on every change is, to these, every row leaving and every row
 * arriving. {@code Node.keyed} and {@code Node.arrange} keep the nodes, and a rebuilt list is where a fresh project
 * meets this first.
 */
final class WindowMotion {

    /** A colour changing. */
    static final Dur PAINT = Dur.ms(120);

    /** A cut-off name being shown whole. */
    static final Dur REVEAL = Dur.ms(150);

    /** A node sliding to where layout put it. */
    static final Dur MOVE = Dur.ms(180);

    /** A node arriving or going. Shorter than a move: the slide that makes room is the larger part of the change. */
    static final Dur PRESENCE = Dur.ms(140);

    /** How far below its place an arriving node starts, in root ems: enough to read as coming, not as falling. */
    static final float RISE_EM = 0.35f;

    /**
     * How long a zoom step takes to close half the way to its target: 95% of it in about a sixth of a second, so a held
     * Ctrl+= reads as one smooth growth rather than a staircase. The wheel's half-life, near enough, for the same
     * reason: both are the user's hand.
     */
    static final java.time.Duration ZOOM_HALF_LIFE = java.time.Duration.ofMillis(40);

    private WindowMotion() {
    }

    /** Give {@code gui} the framework's motion, timed on {@code krono}. Before its first widget. */
    static void install(Gui gui, KronoGui krono) {
        Unclip.of(gui).motion((progress, done) -> krono.ramp(REVEAL, Ease.IN_OUT_CUBIC, progress, done));
        gui.paintMotion(Fades.on(krono, PAINT, Ease.LINEAR));
        gui.motion(Transitions.on(krono).followSubtree(gui.root(), MOVE, Ease.OUT_CUBIC));
        Presence presence = Presence.on(krono).rise(RISE_EM).followSubtree(gui.root(), PRESENCE, Ease.OUT_CUBIC);
        gui.enterMotion(presence).exitMotion(presence);
        gui.zoomGlide(ZOOM_HALF_LIFE);
    }
}
