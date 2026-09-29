package ${packageName};

import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the components report, and the one place the view learns of it.
 *
 * <p>Not a component and not the model: a plain provided value that several lanes write and the view reads,
 * which is exactly the shape the colour rule permits — it is not main-thread, and nothing here holds a lane. Every
 * setter announces on {@link #onChange}, so a change made on a component's thread reaches the tree the way a
 * model commit does.
 */
final class Bench {

    private final AtomicInteger worked = new AtomicInteger();
    private final AtomicInteger echoed = new AtomicInteger();
    private volatile String isolated = "idle";
    private volatile String standard = "idle";
    private volatile String stalled = "none";
    private volatile Runnable changed = () -> { };

    void onChange(Runnable listener) {
        this.changed = listener;
    }

    void worked() {
        worked.incrementAndGet();
        changed.run();
    }

    void echoed() {
        echoed.incrementAndGet();
        changed.run();
    }

    void isolated(String state) {
        isolated = state;
        changed.run();
    }

    void standard(String state) {
        standard = state;
        changed.run();
    }

    void stalled(String lane) {
        stalled = lane;
        changed.run();
    }

    String workedText() {
        return "worked " + worked.get();
    }

    String echoedText() {
        return "echoed " + echoed.get();
    }

    String isolatedText() {
        return "isolated " + isolated;
    }

    String defaultText() {
        return "default " + standard;
    }

    String stallText() {
        return "stall " + stalled;
    }
}
