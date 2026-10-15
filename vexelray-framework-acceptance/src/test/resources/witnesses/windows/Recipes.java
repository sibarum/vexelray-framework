package ${packageName};

import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.api.Setting;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.framework.shell.Dialogs;
import dev.vexelray.framework.shell.NamedWindow;
import dev.vexelray.framework.shell.Window;
import dev.vexelray.framework.shell.Windows;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.widget.Modal;
import dev.vexelray.gui.widget.TitleBar;

/**
 * W1's parts: one model, the main window's tree, and two named windows on it.
 *
 * <p>What is not here is the finding. Neither window takes the {@code GuiApp}, the {@code WindowMemory} or the
 * window mark, nothing here is main-thread, and no recipe builds a {@code Gui}: each window is one declaration and a
 * class that builds into the tree it was handed.
 */
@Configuration
final class Recipes {

    @Provides
    Appearance look() {
        return Appearance.of(Look.THEME, Length.rem(${className}.MIN_W_EM), Length.rem(${className}.MIN_H_EM));
    }

    @Provides
    Model model() {
        return new Model();
    }

    /**
     * The main window's tree. Its Quit closes the main window through its handle, and its Ask asks the framework's
     * {@code Dialogs}, with no {@code GuiApp} taken for either. {@code witness.ask=early} asks from here, in
     * {@code TREE}, before there is a window to ask in: the dialogs hold the question and ask it once there is.
     */
    @Provides
    Ui ui(Gui gui, Model model, TitleBar titleBar, Windows windows, Dialogs dialogs,
          @Setting(value = "witness.ask", def = "") String ask) {
        Window main = windows.main();
        Runnable question = () -> dialogs.ask(Modal.of("Witness question", "Count one more?")
                .defaultButton("Count", model::bump)
                .cancelButton("Cancel", () -> { }));
        Ui ui = new Ui(gui, model, titleBar, main::close, question);
        model.onChange(ui::show);
        ui.show(model.doc());
        if (ask.equals("early")) {
            question.run();
        }
        return ui;
    }

    /**
     * The notes window, a peer. {@code witness.open=notes} shows it from here, in {@code TREE}, two phases before
     * there is a device to open it on: the framework holds the request and carries it out when it claims the window.
     */
    @Provides
    Notes notes(Windows windows, Model model, Ui ui, @Setting(value = "witness.open", def = "") String open) {
        Window window = windows.named(Notes.KEY, "Notes", Notes.W, Notes.H);
        Notes notes = new Notes(window);
        ui.onNotes(window::show);
        model.onChange(notes::show);
        notes.show(model.doc());
        if (open.equals(Notes.KEY)) {
            window.show();
        }
        return notes;
    }

    /**
     * The palette, a satellite, with a close gate of its own: closing it asks first, through the framework's
     * {@code Dialogs}, and Cancel keeps it open. A gate on a window that is not the main one, which nothing could set
     * before {@code Window.onClose}.
     */
    @Provides
    Palette palette(Windows windows, Model model, Ui ui, Dialogs dialogs) {
        Window window = windows.named(NamedWindow.of(Palette.KEY, "Palette", Palette.W, Palette.H).satellite());
        window.onClose((proceed, cancel) ->
                dialogs.confirm("Close the palette?", "It keeps its count either way.", "Close", proceed, cancel));
        Palette palette = new Palette(window, model);
        ui.onPalette(window::show);
        model.onChange(palette::show);
        palette.show(model.doc());
        return palette;
    }
}
