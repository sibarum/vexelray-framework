package ${packageName};

import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.TitleBar;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

/**
 * What this application builds — one recipe per part — and nothing about when.
 *
 * <h2>The wiring is generated from this</h2>
 *
 * <p>{@code ${className}Wiring} is written by {@code vexelray-framework-processor} while this project compiles,
 * and lands in {@code target/generated-sources/annotations}. It calls each method below once, in the phase its
 * parameters put it in: <b>a part's phase is the latest phase of anything it takes</b>, so the look and the model,
 * which take nothing the framework builds late, exist before the {@code Gui} does, and the tree waits for the
 * {@code Gui} and its title bar. There is no phase to declare and none to get wrong: a part asking for something
 * that does not exist yet is not a part that can be written.
 *
 * <p>The driving socket is not here either. It comes from {@code AutomationStarter}, which {@link ${className}}
 * names in its {@code @VexelApp} — so this application is drivable because it says so in one place, and a
 * {@code @Provides Driver} method here would replace the starter's with this application's own.
 *
 * <h2>What is not here is the point</h2>
 *
 * <p>Opening an input backend and settling its coordinate space, installing a clipboard, remembering where the
 * window was, attaching a clock, wiring the frame loop with its wakes and its pacing, parsing the command line,
 * installing the dialogs, and closing everything in the right order — every one of those used to be written out
 * by hand, and all of them are {@code vexelray-framework}'s now. So is the order this file's parts are built in,
 * and so is closing the ones that are {@code AutoCloseable}.
 *
 * <p>Adding a part is a method here. It returns an interface when other code should be able to swap it; the
 * application's own package-private classes, like {@link Model} and {@link Ui}, may be returned as they are,
 * because nothing outside this package could hold a call site against them anyway. Watch out for the case that
 * is easy to read past — an announcement depends on its <em>listeners</em>, so seeding state belongs after the
 * things it wakes up rather than beside the model it edits.
 */
@Configuration
final class Recipes {

    /**
     * The look, and the smallest window this UI is still coherent in — a floor, not the design size.
     *
     * <p>Built from nothing the framework makes, so it exists in the first phase, and the framework applies it the
     * moment it creates the {@code Gui}. That is what makes "the theme is applied before the first widget"
     * structural rather than a comment somebody has to keep obeying; a look that took the {@code Gui} would be a
     * compile error, not a half-themed window.
     */
    @Provides
    Appearance look() {
        return Appearance.of(Look.THEME, Length.em(${className}.MIN_W_EM), Length.em(${className}.MIN_H_EM));
    }

    /** The one authoritative state, built before the tree because every control is a view onto it. */
    @Provides
    Model model() {
        return new Model();
    }

    /**
     * The tree. Needs the {@code Gui}, its clock and its title bar, and no window. The clock is for the one
     * transition the tree has — see {@code Ui.pulse}.
     *
     * <p>That last part is a claim worth keeping true as this grows: a tree that can be built without a window is
     * a tree {@link Capture} can photograph headlessly. It is also why the initial {@code show} is here rather than
     * later — the tree a capture gets should be the tree a user gets, already carrying the document rather than
     * whatever a node was constructed with.
     *
     * <p>The title bar is the framework's. Chrome placement belongs to whoever owns the window, so that the
     * screenshot instrument in it means the same thing in every window on the desk; this application places the
     * node and supplies every colour in it.
     */
    @Provides
    Ui ui(Gui gui, KronoGui krono, Model model, TitleBar titleBar) {
        Ui ui = new Ui(gui, krono, model, titleBar);
        // Every change to the state redraws what is derived from it, on the committing thread -- which is a
        // worker, because every control's handler is. The GUI thread never reads the model.
        model.onChange(ui::show);
        ui.show(model.doc());
        zoomShortcuts(gui);
        keys(gui, model);
        return ui;
    }

    /**
     * Every single-key control, as a {@code GLOBAL} claim.
     *
     * <p>Claims rather than handlers, which is how this framework does preemption: a text field outranks these by
     * claiming the same key at {@code FOCUSED} scope, so typing {@code r} into a field types an {@code r} rather
     * than firing the shortcut. Nothing here has to know the field exists, and the field needs no list of keys to
     * avoid.
     */
    private static void keys(Gui gui, Model model) {
        gui.shortcut(Key.R, model::reset);
    }

    /**
     * Ctrl+= / Ctrl+- / Ctrl+0, and the numpad's three.
     *
     * <p>An application decision, which is why it is here: which chord zooms, or whether zooming exists at all, is
     * not something a framework should choose. <b>How far the zoom goes is</b>, and it is not stated here — that is
     * {@code Appearance.ZoomRange}, applied by the framework before the first widget, so every window on the desk
     * agrees about it.
     */
    private static void zoomShortcuts(Gui gui) {
        gui.shortcut(Key.EQUAL, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.MINUS, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.DIGIT_0, gui::resetZoom, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_ADD, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_SUBTRACT, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_0, gui::resetZoom, Modifier.CONTROL);
    }
}
