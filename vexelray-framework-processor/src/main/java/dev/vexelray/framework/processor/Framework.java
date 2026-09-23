package dev.vexelray.framework.processor;

import dev.vexelray.framework.core.Phase;

import java.util.List;

/**
 * What the framework supplies to generated wiring, and what it takes back — named by string, because the processor
 * cannot depend on {@code -shell} without putting the graphics stack on every application's processor path.
 *
 * <p><b>A string that must be spelled right is the thing this repo refuses elsewhere</b>, so these are held
 * against the code they name: {@code FrameworkTableTest} in {@code -shell} reads this table and checks every
 * accessor exists on {@code Shell} with the return type written here, and every phase method on {@code Wiring}.
 * A renamed accessor fails that test rather than a generated wiring.
 */
final class Framework {

    static final String SHELL = "dev.vexelray.framework.shell.Shell";
    static final String WIRING = "dev.vexelray.framework.shell.Wiring";
    static final String APP_INFO = "dev.vexelray.framework.shell.AppInfo";
    static final String PLACEMENT = "dev.vexelray.framework.shell.Placement";
    static final String APPEARANCE = "dev.vexelray.framework.shell.Appearance";

    /**
     * A value the framework owns and hands out through a {@code Shell} accessor, from the phase that accessor
     * refuses before.
     *
     * @param type       the value's type
     * @param accessor   the {@code Shell} method returning it, or empty for the shell itself
     * @param phase      the first phase it exists in — what {@code Shell.require} names for it
     * @param onMain     whether it is the main thread's: T2.2 and T3.1 read this, since the framework's own types
     *                   cannot carry {@code @MainThread} without their repos learning this one exists
     */
    record Root(String type, String accessor, Phase phase, boolean onMain) {

        String expression() {
            return accessor.isEmpty() ? "shell" : "shell." + accessor + "()";
        }
    }

    /**
     * The roots. <b>The shell itself is an {@link Phase#ATTACH} value</b>: it reaches everything, so a provider
     * taking it could touch anything, and the only phase where everything exists is the last one.
     */
    static final List<Root> ROOTS = List.of(
            new Root(SHELL, "", Phase.ATTACH, false),
            new Root("dev.vexelray.framework.core.Launch", "launch", Phase.CONFIG, false),
            new Root(APP_INFO, "info", Phase.CONFIG, false),
            new Root("sibarum.atchung.Atchung", "bus", Phase.CONFIG, false),
            new Root("dev.vexelray.framework.core.Lanes", "lanes", Phase.CONFIG, false),
            new Root("dev.vexelray.framework.core.FrameHooks", "hooks", Phase.CONFIG, false),
            new Root("dev.vexelray.framework.core.Disposer", "disposer", Phase.CONFIG, false),
            new Root("dev.vexelray.framework.shell.PointerLock", "pointerLock", Phase.CONFIG, false),
            new Root("dev.vexelray.gui.core.app.Settings", "settings", Phase.CONFIG, false),
            new Root("dev.vexelray.gui.core.Gui", "gui", Phase.GUI, false),
            new Root("dev.vexelray.gui.krono.KronoGui", "krono", Phase.GUI, false),
            new Root("dev.vexelray.gui.widget.TitleBar", "titleBar", Phase.GUI, false),
            new Root("dev.vexelray.gui.core.app.WindowMemory", "memory", Phase.WINDOW, false),
            // "Vulkan, the window and present stay on the main thread" -- vexelray-gui/CLAUDE.md.
            new Root("dev.vexelray.gui.core.app.GuiApp", "app", Phase.WINDOW, true),
            new Root("dev.vexelray.framework.shell.ClipboardBackend", "clipboard", Phase.ATTACH, false),
            new Root("dev.vexelray.gui.widget.Modals", "dialogs", Phase.ATTACH, false));

    /** The root for a type, by qualified name, or {@code null}. */
    static Root root(String qualifiedName) {
        for (Root r : ROOTS) {
            if (r.type().equals(qualifiedName)) {
                return r;
            }
        }
        return null;
    }

    /**
     * The {@code Wiring} method a phase's construction goes in. The six construction phases' names, lower-cased,
     * are exactly {@code Wiring}'s six methods; {@link Phase#RUN} constructs nothing.
     */
    static String method(Phase phase) {
        return phase.name().toLowerCase(java.util.Locale.ROOT);
    }

    private Framework() {
    }
}
