package ${packageName};

import ${packageName}.core.Messages.Begin;
import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.widget.TitleBar;
import sibarum.atchung.Sender;

/**
 * What this module builds — one recipe per part — and nothing about when.
 *
 * <h2>The wiring is generated from this</h2>
 *
 * <p>{@code ${className}AppWiring} is written by {@code vexelray-framework-processor} while this module compiles, and
 * lands in {@code target/generated-sources/annotations}. It calls each method below once, in the phase its
 * parameters put it in: <b>a part's phase is the latest phase of anything it takes.</b> So the look exists before
 * the {@code Gui} does; the window's contents wait for the {@code Gui}, its clock and its title bar; and the start,
 * which takes the {@code Shell}, comes last, when the window and the dialogs exist. There is no phase to declare and
 * none to get wrong. Open the generated file after a build to see the order.
 *
 * <h2>Where the rest comes from</h2>
 *
 * <p>Not every part is a recipe. The {@link View} is a {@code @Component}: a lane of its own, built from its one
 * constructor, reached by messages. The editor and the jobs that touch the disk are in {@code -core}, and reach the
 * wiring through {@code Core}, which {@code ${className}App} names among its starters beside {@code AutomationStarter},
 * the automation sockets.
 *
 * <p>Opening an input backend, installing a clipboard, remembering where the window was, attaching a clock, wiring
 * the frame loop with its wakes and its pacing, parsing the command line, installing the dialogs, and closing
 * everything in the right order are all {@code vexelray-framework}'s. So is closing the parts that are
 * {@code AutoCloseable}.
 *
 * <p>Adding a part is a method here. It returns an interface when other code should be able to swap it; this
 * package's own classes may be returned as they are, because nothing outside the package could hold a call site
 * against them anyway.
 */
@Configuration
final class Recipes {

    /**
     * The look, and the smallest window this UI is still coherent in. Built from nothing the framework makes, so it
     * exists in the first phase and the framework applies it the moment it creates the {@code Gui}: "the theme is
     * applied before the first widget" is structural rather than a comment somebody has to keep obeying.
     */
    @Provides
    Appearance look() {
        return Appearance.of(Look.THEME, Length.rem(${className}.MIN_W_EM), Length.rem(${className}.MIN_H_EM));
    }

    /**
     * The window's contents. Needs the {@code Gui}, its clock and its title bar, and no window — so a test can build
     * the real tree headless ({@code VexelApplication.tree}), which {@code ViewTest} does.
     *
     * <p>The clock is for motion: {@link Motion} makes the ramps every widget here animates with.
     */
    @Provides
    Ui ui(Gui gui, TitleBar titleBar) {
        return new Ui(gui, new Motion(gui), titleBar);
    }

    /** The main window's close gate, as messages: built here so the view can answer it and {@link #start} can arm it. */
    @Provides
    QuitGate gate(Sender<Intent> intents) {
        return new QuitGate(intents);
    }

    /**
     * Arm the close gate and tell the editor to bring back last time's session.
     *
     * <p>Takes the {@code Shell} to be last: restoring the session opens files and writes settings, which a tree built
     * headless for a test must not, and the {@code Shell} is the framework's last-phase value. The gate is the main
     * window's, and a window has one: a second is refused, because the one it would replace is as likely as not the
     * one that knew about the unsaved files.
     *
     * <p>Run for what it does, so it returns nothing: the wiring calls it once, in the phase its parameters put it in.
     * The message waits in the editor's mailbox until the framework starts the components. What it sends is a
     * parameter, as everything a part sends is, so the wiring builds its sender on {@code Begin}'s topic.
     */
    @Provides
    void start(Shell shell, QuitGate gate, Sender<Begin> begin) {
        shell.windows().main().onClose(gate::guard);
        begin.send(new Begin());
    }

}
