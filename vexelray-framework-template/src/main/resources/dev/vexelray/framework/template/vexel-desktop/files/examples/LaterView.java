package ${packageName}.examples.later;

import ${packageName}.core.examples.later.LaterMessages.NeverMind;
import ${packageName}.core.examples.later.LaterMessages.RemindMe;
import ${packageName}.core.examples.later.LaterMessages.Reminders;
import ${packageName}.core.examples.later.LaterMessages.Settled;
import ${packageName}.core.examples.later.LaterMessages.Typed;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.TextField;
import sibarum.atchung.Sender;

/**
 * The <i>later</i> example's view: every keystroke and press told to {@code Later} as it happens, and what
 * {@code Later} decides shown. It decides nothing, and in particular it does not wait: the waiting is the chain's.
 */
@dev.vexelray.framework.api.View
public final class LaterView {

    private final LaterPanel panel;
    private final Sender<Typed> typed;
    private final Sender<RemindMe> remind;
    private final Sender<NeverMind> neverMind;

    public LaterView(LaterPanel panel, Sender<Typed> typed, Sender<RemindMe> remind, Sender<NeverMind> neverMind) {
        this.panel = panel;
        this.typed = typed;
        this.remind = remind;
        this.neverMind = neverMind;
    }

    @Subscribe
    public void changed(TextField.Changed m) {
        if (m.field().equals(panel.searchAddress())) {
            typed.send(new Typed(m.text()));
        }
    }

    @Subscribe
    public void pressed(Button.Pressed p) {
        if (p.button().equals(panel.remindAddress())) {
            remind.send(new RemindMe());
        } else if (p.button().equals(panel.neverMindAddress())) {
            neverMind.send(new NeverMind());
        }
    }

    @Subscribe
    public void settled(Settled m) {
        panel.searched(m);
    }

    @Subscribe
    public void reminders(Reminders m) {
        panel.reminders(m);
    }
}
