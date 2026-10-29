package ${packageName};

import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.api.View;
import dev.vexelray.gui.widget.Button;
import sibarum.atchung.Sender;

/**
 * The view: the metronome's count, the counter and the process's weight, shown, and the buttons, told. A tick count
 * rewritten up to a hundred times a second is what keeps the loop woken while the metronome runs.
 */
@View
final class Shown {

    private final Ui ui;
    private final Sender<Bump> bump;
    private final Sender<Reset> reset;
    private final Sender<Start> start;
    private final Sender<Stop> stop;
    private final Sender<Weigh> weigh;
    private int samples;

    Shown(Ui ui, Sender<Bump> bump, Sender<Reset> reset, Sender<Start> start, Sender<Stop> stop,
          Sender<Weigh> weigh) {
        this.ui = ui;
        this.bump = bump;
        this.reset = reset;
        this.start = start;
        this.stop = stop;
        this.weigh = weigh;
    }

    @Subscribe
    public void pressed(Button.Pressed p) {
        String b = p.button();
        if (b.equals(ui.address(Landmarks.COUNT_BUTTON))) {
            bump.send(new Bump());
        } else if (b.equals(ui.address(Landmarks.RESET_BUTTON))) {
            reset.send(new Reset());
        } else if (b.equals(ui.address(Landmarks.START_BUTTON))) {
            start.send(new Start());
        } else if (b.equals(ui.address(Landmarks.STOP_BUTTON))) {
            stop.send(new Stop());
        } else if (b.equals(ui.address(Landmarks.SAMPLE_BUTTON))) {
            weigh.send(new Weigh(++samples));
        }
    }

    @Subscribe
    public void counted(Counted c) {
        ui.count.text(String.valueOf(c.doc().count()));
        ui.note.text(c.doc().note());
    }

    @Subscribe
    public void ticked(Ticked t) {
        ui.ticks.text("ticks " + t.ticks());
    }

    @Subscribe
    public void weighed(Weighed w) {
        ui.sample.text("sample #" + w.n() + " threads " + w.threads() + " heap " + w.heapMb());
    }
}
