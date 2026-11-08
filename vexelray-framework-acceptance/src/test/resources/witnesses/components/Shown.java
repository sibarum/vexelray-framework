package ${packageName};

import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.api.View;
import dev.vexelray.framework.core.Stalled;
import dev.vexelray.framework.core.WindowCommand;
import dev.vexelray.gui.widget.Button;
import sibarum.atchung.Sender;

/**
 * The view: what the components said, shown, and what the person pressed, told. It runs in the frame, on the GUI
 * thread, so it is the one part a wedged lane cannot reach, and {@code taps} is the proof: a count it keeps itself.
 *
 * <p>A stall is heard here too, as the watchdog's {@link Stalled}: the report is a message, and the liveness policy
 * only interrupts.
 */
@View
final class Shown {

    private final Ui ui;
    private final Sender<Bump> bump;
    private final Sender<Reset> reset;
    private final Sender<Job> job;
    private final Sender<Ping> ping;
    private final Sender<Wedge> wedge;
    private final Sender<WindowCommand> windows;
    private int taps;
    private int asked;

    Shown(Ui ui, Sender<Bump> bump, Sender<Reset> reset, Sender<Job> job, Sender<Ping> ping, Sender<Wedge> wedge,
          Sender<WindowCommand> windows) {
        this.ui = ui;
        this.bump = bump;
        this.reset = reset;
        this.job = job;
        this.ping = ping;
        this.wedge = wedge;
        this.windows = windows;
    }

    @Subscribe
    public void pressed(Button.Pressed p) {
        String b = p.button();
        if (b.equals(ui.address(Landmarks.COUNT_BUTTON))) {
            bump.send(new Bump());
        } else if (b.equals(ui.address(Landmarks.RESET_BUTTON))) {
            reset.send(new Reset());
        } else if (b.equals(ui.address(Landmarks.TAP_BUTTON))) {
            ui.taps.text("taps " + ++taps);
        } else if (b.equals(ui.address(Landmarks.WORK_BUTTON))) {
            job.send(new Job(++asked));
        } else if (b.equals(ui.address(Landmarks.ECHO_BUTTON))) {
            ping.send(new Ping(++asked));
        } else if (b.equals(ui.address(Landmarks.WEDGE_DEFAULT_BUTTON))) {
            // Said here, by the asker: the component's own word would be held until its wedge ends.
            ui.standard.text("default asked");
            wedge.send(new Wedge("logic"));
        } else if (b.equals(ui.address(Landmarks.WEDGE_ISOLATED_BUTTON))) {
            ui.isolated.text("isolated asked");
            wedge.send(new Wedge("isolated"));
        } else if (b.equals(ui.address(Landmarks.TOOL_BUTTON))) {
            windows.send(WindowCommand.show(ToolWindow.KEY));
        } else if (b.equals(ToolWindow.KEY + "." + Landmarks.TOOL_CLOSE_BUTTON)) {
            windows.send(WindowCommand.close(ToolWindow.KEY));
        }
    }

    @Subscribe
    public void counted(Counted c) {
        ui.count.text(String.valueOf(c.doc().count()));
        ui.note.text(c.doc().note());
    }

    @Subscribe
    public void worked(Worked w) {
        ui.result.text("worked " + w.count());
    }

    @Subscribe
    public void echoed(Echoed e) {
        ui.echo.text("echoed " + e.count());
    }

    @Subscribe
    public void wedging(Wedging w) {
        if (w.lane().equals("isolated")) {
            ui.isolated.text("isolated " + w.state());
        } else {
            ui.standard.text("default " + w.state());
        }
    }

    @Subscribe
    public void stalled(Stalled s) {
        ui.stall.text("stall " + s.lane());
    }
}
