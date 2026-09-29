package ${packageName};

/** Every name the witness's driver writes down: the template's, and one per thing the metronome exposes. */
final class Landmarks {

    static final String CARD = "card";
    static final String COUNT = "count";
    static final String NOTE = "note";
    static final String COUNT_BUTTON = "button.count";
    static final String RESET_BUTTON = "button.reset";

    /** Starts the metronome: a component that then never has nothing to do. */
    static final String START_BUTTON = "button.start";
    static final String STOP_BUTTON = "button.stop";
    /** Copies the frame count, the thread count and the heap into {@link #SAMPLE}, once, when pressed. */
    static final String SAMPLE_BUTTON = "button.sample";

    /** How many ticks the metronome has made. Rewritten on every one, from the component's thread. */
    static final String TICKS = "ticks";
    /** The last sample, numbered so a driver can wait for the one it asked for. */
    static final String SAMPLE = "sample";

    private Landmarks() {
    }
}
