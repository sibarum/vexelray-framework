package ${packageName};

import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.framework.shell.NamedWindow;
import dev.vexelray.framework.shell.Windows;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.widget.TitleBar;

/**
 * The two-window witness's parts: the look, the main window's tree, a peer window and a satellite declared through
 * {@code Windows}, and what the launch asked for. Opening, closing and asking are messages, sent by the view and the
 * {@link Gate} and the {@link Keeper}; nothing here holds a window's handle past building its tree.
 */
@Configuration(components = {Counter.class, Gate.class, Keeper.class})
final class Recipes {

    @Provides
    Appearance look() {
        return Appearance.of(Look.DARK, Length.rem(${className}.MIN_W_EM), Length.rem(${className}.MIN_H_EM));
    }

    @Provides
    Ui ui(Gui gui, TitleBar titleBar) {
        return new Ui(gui, titleBar);
    }

    /** A peer of the main window, remembered under its own name. */
    @Provides
    Notes notes(Windows windows) {
        return new Notes(windows.named(Notes.KEY, "Notes", Notes.W, Notes.H));
    }

    /** A satellite, whose close the {@link Gate} puts to the person. */
    @Provides
    Palette palette(Windows windows) {
        return new Palette(windows.named(NamedWindow.of(Palette.KEY, "Palette", Palette.W, Palette.H).satellite()));
    }
}
