package ${packageName};

import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.api.View;
import dev.vexelray.framework.core.CloseRequested;
import dev.vexelray.framework.core.WindowCommand;
import dev.vexelray.framework.core.WindowOpened;
import dev.vexelray.gui.widget.Button;
import sibarum.atchung.Sender;

/**
 * The view over all three windows: one bus, so it hears every window's buttons by their addresses, and writes the
 * count into each tree. Opening, closing and quitting are {@link WindowCommand}s; the question is the {@link Gate}'s.
 */
@View
final class Shown {

    private final Ui ui;
    private final Notes notes;
    private final Palette palette;
    private final Sender<Bump> bump;
    private final Sender<Question> question;
    private final Sender<WindowCommand> windows;
    private int opened;

    Shown(Ui ui, Notes notes, Palette palette, Sender<Bump> bump, Sender<Question> question,
          Sender<WindowCommand> windows) {
        this.ui = ui;
        this.notes = notes;
        this.palette = palette;
        this.bump = bump;
        this.question = question;
        this.windows = windows;
    }

    @Subscribe
    public void pressed(Button.Pressed p) {
        String b = p.button();
        if (b.equals(at(CloseRequested.MAIN, Landmarks.COUNT_BUTTON)) || b.equals(at(Palette.KEY, Landmarks.PALETTE_BUMP))) {
            bump.send(new Bump());
        } else if (b.equals(at(CloseRequested.MAIN, Landmarks.NOTES_BUTTON))) {
            windows.send(WindowCommand.show(Notes.KEY));
        } else if (b.equals(at(CloseRequested.MAIN, Landmarks.PALETTE_BUTTON))) {
            windows.send(WindowCommand.show(Palette.KEY));
        } else if (b.equals(at(CloseRequested.MAIN, Landmarks.ASK_BUTTON))) {
            question.send(new Question());
        } else if (b.equals(at(CloseRequested.MAIN, Landmarks.QUIT_BUTTON))) {
            windows.send(WindowCommand.close(CloseRequested.MAIN));
        } else if (b.equals(at(Palette.KEY, Landmarks.PALETTE_CLOSE))) {
            windows.send(WindowCommand.close(Palette.KEY));
        }
    }

    @Subscribe
    public void counted(Counted c) {
        String n = String.valueOf(c.doc().count());
        ui.count.text(n);
        notes.count.text(n);
        palette.count.text(n);
    }

    @Subscribe
    public void windowOpened(WindowOpened w) {
        if (w.window().equals(Notes.KEY)) {
            notes.opened.text("opened " + ++opened);
        }
    }

    private static String at(String window, String landmark) {
        return window + "." + landmark;
    }
}
