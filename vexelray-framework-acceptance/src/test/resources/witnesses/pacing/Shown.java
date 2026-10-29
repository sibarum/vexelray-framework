package ${packageName};

import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.api.View;
import dev.vexelray.gui.core.Timing;
import dev.vexelray.gui.core.Tween;
import dev.vexelray.gui.widget.Button;
import sibarum.atchung.Sender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The instrument's view: it starts each probe and reads the clock where a person would see the result, in the frame.
 *
 * <ul>
 *   <li><b>Pulse</b>: a 600 ms tween, heard once a frame while it moves, so the gaps between its steps are the frame
 *       gaps of an animation started by a click.</li>
 *   <li><b>Tap</b>: a {@link Ping} stamped in the frame that took the click, answered on the logic lane, timed in the
 *       frame that hears the answer. That is how long a click's result through the logic takes to reach a frame.</li>
 *   <li><b>Probe</b>: a timer's beats stamped on the logic lane, timed in the frame each wakes.</li>
 *   <li><b>Idle</b> and <b>Storm</b>: started here, answered by a job and a component, and their frames counted by
 *       the driver from the flight recorder.</li>
 * </ul>
 */
@View
final class Shown {

    private static final Timing PULSE = Timing.millis(600, Timing.Curve.LINEAR);

    private final Ui ui;
    private final Sender<Ping> ping;
    private final Sender<Probe> probe;
    private final Sender<Storm> storm;
    private final Sender<Weigh> weigh;
    private final Tween tween;

    private final List<Long> taps = new ArrayList<>();
    private final List<Long> wakes = new ArrayList<>();
    private final List<Long> steps = new ArrayList<>();
    private int pulses;
    private int weighs;
    private long pulseStart;

    Shown(Ui ui, Sender<Ping> ping, Sender<Probe> probe, Sender<Storm> storm, Sender<Weigh> weigh) {
        this.ui = ui;
        this.ping = ping;
        this.probe = probe;
        this.storm = storm;
        this.weigh = weigh;
        this.tween = Tween.of(ui.gui(), "pulse", PULSE);
    }

    @Subscribe
    public void pressed(Button.Pressed p) {
        String b = p.button();
        if (b.equals(ui.address(Landmarks.TAP_BUTTON))) {
            ping.send(new Ping(System.nanoTime()));
        } else if (b.equals(ui.address(Landmarks.PULSE_BUTTON))) {
            if (sibarum.probe.Probe.ON) {
                sibarum.probe.Probe.mark(sibarum.probe.Lane.APP, "pulse.click", "#" + (pulses + 1));
            }
            steps.clear();
            pulseStart = System.nanoTime();
            tween.at(0f);
            tween.toward(1f);
        } else if (b.equals(ui.address(Landmarks.PROBE_BUTTON))) {
            wakes.clear();
            probe.send(new Probe(15));
        } else if (b.equals(ui.address(Landmarks.STORM_BUTTON))) {
            storm.send(new Storm(3_000));
        } else if (b.equals(ui.address(Landmarks.IDLE_BUTTON))) {
            weigh.send(new Weigh(++weighs, 4_000));
        }
    }

    /** A step of the pulse, once a frame while it moves. */
    @Subscribe
    public void tweened(Tween.Tweened t) {
        if (!t.tween().equals(tween.address()) || pulseStart == 0L) {
            return;
        }
        steps.add(System.nanoTime());
        ui.figure.text(String.format(Locale.ROOT, "%.2f", Math.sin(Math.PI * t.value())));
        if (!t.settled()) {
            return;
        }
        if (sibarum.probe.Probe.ON) {
            sibarum.probe.Probe.mark(sibarum.probe.Lane.APP, "pulse.done", "#" + (pulses + 1));
        }
        long[] gaps = new long[Math.max(0, steps.size() - 1)];
        for (int i = 1; i < steps.size(); i++) {
            gaps[i - 1] = (steps.get(i) - steps.get(i - 1)) / 1_000L;
        }
        long millis = (System.nanoTime() - pulseStart) / 1_000_000L;
        long[] sorted = gaps.clone();
        Arrays.sort(sorted);
        long median = sorted.length == 0 ? 0 : sorted[sorted.length / 2];
        long late = Arrays.stream(gaps).filter(g -> g > median * 3 / 2).count();
        ui.pulse.text("pulse #" + ++pulses + " frames " + steps.size() + " in " + millis + "ms gap " + stats(gaps)
                + " late " + late);
        pulseStart = 0L;
    }

    @Subscribe
    public void pong(Pong p) {
        taps.add((System.nanoTime() - p.stamp()) / 1_000L);
        ui.tap.text("tap " + stats(taps.stream().mapToLong(Long::longValue).toArray()));
    }

    @Subscribe
    public void stamped(Stamped s) {
        wakes.add((System.nanoTime() - s.stamp()) / 1_000L);
        ui.tick.text("tick " + wakes.size());
        if (s.last()) {
            ui.latency.text("latency " + stats(wakes.stream().mapToLong(Long::longValue).toArray()));
        }
    }

    @Subscribe
    public void stormed(Stormed s) {
        ui.storm.text("storm deliveries " + s.deliveries() + " in " + s.millis() + "ms");
    }

    @Subscribe
    public void weighed(Weighed w) {
        ui.idle.text(String.format(Locale.ROOT, "idle #%d cpu %dms in %dms (%.2f%%)", w.n(), w.cpuMillis(),
                w.wallMillis(), w.cpuMillis() < 0 ? -1.0 : 100.0 * w.cpuMillis() / w.wallMillis()));
    }

    /** {@code n=… median …us p95 …us max …us min …us}. */
    private static String stats(long[] micros) {
        if (micros.length == 0) {
            return "n=0";
        }
        long[] s = micros.clone();
        Arrays.sort(s);
        return "n=" + s.length + " median " + s[s.length / 2] + "us p95 " + s[Math.min(s.length - 1, (int) (s.length * 0.95))]
                + "us max " + s[s.length - 1] + "us min " + s[0] + "us";
    }
}
