package ${packageName};

import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.core.Answer;
import dev.vexelray.framework.core.Ask;
import dev.vexelray.framework.core.CloseAnswer;
import dev.vexelray.framework.core.CloseRequested;
import sibarum.atchung.Sender;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The witness's second say in a close (regions.md, ruling 3): it too hears every window's close request, and puts its
 * own question before the palette's goes. Its name sorts after {@link Gate}'s, so it is asked second, and only once the
 * Gate has said Close: a Cancel there means it is never asked. Every other window's close it lets through at once,
 * the main window's quit included, which is then asked of both in turn.
 *
 * <p>Its code is a part's that is asked alone. It never sees the Gate's turn, since the wiring hands it only the
 * requests addressed to it.
 */
@Component
final class Keeper {

    private final Sender<Ask> asks;
    private final Sender<CloseAnswer> closes;
    /** A palette close waiting on the person: the question's id, and the close's ticket. */
    private final Map<Long, Long> closing = new HashMap<>();
    /**
     * Its question ids, counted from 1 as the Gate counts its own: an answer comes back only to the part that asked, so
     * the two never meet. Before answers were addressed this counted from 2^40 to stay clear of the Gate, which is the
     * collision this witness now shows is gone.
     */
    private long asked;

    Keeper(Sender<Ask> asks, Sender<CloseAnswer> closes) {
        this.asks = asks;
        this.closes = closes;
    }

    @Subscribe
    public void closeRequested(CloseRequested r) {
        if (!r.window().equals(Palette.KEY)) {
            closes.send(new CloseAnswer(r.ticket(), true));
            return;
        }
        long id = ++asked;
        closing.put(id, r.ticket());
        asks.send(new Ask(id, "Put the palette away?", "Asked second, after the Gate said Close.",
                List.of("Put away", "Keep"), Keeper.class));
    }

    @Subscribe
    public void answered(Answer a) {
        Long ticket = closing.remove(a.id());
        if (ticket != null) {
            closes.send(new CloseAnswer(ticket, a.button() == 0));
        }
    }
}
