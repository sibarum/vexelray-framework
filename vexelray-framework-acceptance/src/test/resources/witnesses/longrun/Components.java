package ${packageName};

import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Overflow;
import dev.vexelray.framework.api.Subscribe;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;

/** The one channel, named once so an annotation and a publisher cannot disagree by a typo. */
final class Topics {
    static final String TICK = "witness.tick";

    private Topics() {
    }
}

record Tick(long n) { }

/**
 * A component that never has nothing to do: each delivery counts, waits ten milliseconds and publishes the next
 * one to itself, about a hundred a second for as long as it is running.
 *
 * <p>The mailbox is a sample — the next tick supersedes a queued one — so it coalesces, and a second Start while
 * one chain is running folds into it rather than making two. The wait is inside the delivery and is short, so the
 * lane is busy for a hundredth of a second at a time and never for the stall threshold.
 */
@Component(lane = "metronome")
final class Metronome {

    private static final Topic<Tick> TICK = Topic.of(Topics.TICK, Tick.class);

    private final Meter meter;
    private final Atchung bus;

    Metronome(Meter meter, Atchung bus) {
        this.meter = meter;
        this.bus = bus;
    }

    @Subscribe(topic = Topics.TICK, overflow = Overflow.COALESCE_LATEST)
    public void tick(Tick tick) {
        if (!meter.running()) {
            return;
        }
        meter.tick();
        try {
            Thread.sleep(10);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return;
        }
        bus.publish(TICK, new Tick(tick.n() + 1));
    }
}
