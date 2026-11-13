package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.ThemeShifted;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.WindowInstrument;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.input.MenuItem;
import dev.vexelray.gui.core.input.MenuSink;
import dev.vexelray.gui.draw.Picture;
import sibarum.probe.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The person's choice between an application's light and dark looks: remembered, read back before the first widget,
 * and offered in every bar's app menu as a radio pair, <i>Light</i> and <i>Dark</i> under <i>Theme</i>.
 *
 * <p><b>Only for an application that declared both</b> ({@link Appearance#themes}). The framework derives neither
 * look, so an application with one has no choice to offer and gets no section.
 *
 * <p><b>Why a section of the app menu</b> rather than a title-bar button, which is what the request of 2026-10-21 first
 * named: a look is a setting about the application, as its key bindings are, and the app menu is where those are, the
 * same in every window. A button at the bar's right is for a state a person has to see without asking (a recording
 * running), and the look already shows itself. The lines are radio lines ({@link MenuItem#radio}), as an application's
 * own settings in its menus are, so the chosen one carries the dot and choosing the other moves it.
 *
 * <p><b>What a choice reaches.</b> Every tree that took the application's theme rather than one of its own
 * ({@link #follow}): the main window's and each declared window's without a look of its own. The swap is
 * {@code Gui.theme}, which lands with the frame's edits and fades under each tree's paint motion, so nothing changes in
 * one frame. {@link Shell#appearance()} says the look in force from then on, so every dialog opened later, and any
 * window the application builds later and themes through it, opens in the chosen one. The key-bindings window is one
 * of the framework's declared windows ({@link KeyBindings}), so it follows too. A window already open with a tree the
 * framework does not hold (one an application builds through the {@code GuiApp} itself) keeps the look it opened in.
 */
final class ThemeChoice {

    private static final Log LOG = Log.of("framework.theme");

    /** The key in the settings store: {@code light} or {@code dark}; absent until somebody chooses. */
    static final String KEY = "theme";

    /** The radio group the two lines share. */
    private static final String GROUP = "theme";

    /** The role the section's instrument declares, so a script finds it by one name in every application. */
    static final String ROLE = "instrument-theme";

    private final Shell shell;
    private final Settings settings;
    private final List<Gui> trees = new CopyOnWriteArrayList<>();

    private ThemeChoice(Shell shell, Settings settings) {
        this.shell = shell;
        this.settings = settings;
    }

    /**
     * The choice for {@code shell}'s application, its appearance put in the look remembered from an earlier run; or
     * {@code null} for an application with one look, whose appearance is left as it is. In {@code GUI}, before the
     * first tree is themed, so a run opens in the remembered look rather than switching to it.
     */
    static ThemeChoice open(Shell shell) {
        Appearance appearance = shell.appearance();
        if (!appearance.choosesTheme()) {
            return null;
        }
        Settings settings = shell.settings();
        String remembered = settings == null ? null : settings.getString(KEY, null);
        if ("light".equals(remembered) || "dark".equals(remembered)) {
            shell.chosenAppearance(appearance.chosen("light".equals(remembered)));
        } else if (remembered != null) {
            // A hand-edited store, or a later version's word: the application's own first look, and say so once.
            LOG.warn("settings: theme={} is neither light nor dark; opening in the application's own", remembered);
        }
        // The look in force from the start, as state: a part that subscribes late still hears which one it is.
        shell.bus().publish(ThemeShifted.TOPIC, new ThemeShifted(shell.appearance().isLight()));
        return new ThemeChoice(shell, settings);
    }

    /** Have {@code gui}, which took the application's theme, follow every choice from now on. */
    void follow(Gui gui) {
        trees.add(gui);
    }

    /** Whether the look in force is the light one. */
    boolean light() {
        return shell.appearance().isLight();
    }

    /**
     * Put the application in its light look, or its dark one: every following tree, the store, and the appearance
     * every window opened later is themed by. Nothing for the look already in force. On the thread the menu ran it on.
     */
    void choose(boolean light) {
        Appearance now = shell.appearance();
        if (now.isLight() == light) {
            return;
        }
        Appearance next = now.chosen(light);
        shell.chosenAppearance(next);
        for (Gui gui : trees) {
            gui.theme(next.theme());
        }
        // The application's control over a shift (ThemeShifted): after every tree has been asked for the new look, so a
        // view that hears it and asks its tree for the theme is told the new one.
        shell.bus().publish(ThemeShifted.TOPIC, new ThemeShifted(light));
        String word = light ? "light" : "dark";
        LOG.info("theme: {}", word);
        if (settings != null) {
            // Saved now rather than at the next window move: a choice is rare, and a run that crashes after it should
            // still open in it.
            settings.putString(KEY, word).save();
        }
    }

    /**
     * The section of every bar's app menu: a rule, the heading, and the two radio lines. Among the application's own
     * lines, after the key bindings, and never a button at the bar's right.
     */
    WindowInstrument instrument() {
        return new WindowInstrument(ROLE, "Theme", (box, ink) -> Picture.of(), controls -> { }, null,
                (controls, sink) -> section(sink)).place(WindowInstrument.Place.APPLICATION);
    }

    /** The lines, built at each click against the look in force then. */
    void section(MenuSink sink) {
        boolean light = light();
        sink.separator();
        sink.heading("Theme");
        sink.item(MenuItem.of("Light", () -> choose(true)).radio(GROUP, light));
        sink.item(MenuItem.of("Dark", () -> choose(false)).radio(GROUP, !light));
    }
}
