package ${packageName};

/** Every name the measurement's driver writes down: the template's, and one button and one readout per probe. */
final class Landmarks {

    static final String CARD = "card";
    static final String COUNT = "count";
    static final String NOTE = "note";
    static final String COUNT_BUTTON = "button.count";
    static final String RESET_BUTTON = "button.reset";

    /** A 600 ms animation, and a readout of how many frames the loop ran while it played. */
    static final String PULSE_BUTTON = "button.pulse";
    static final String PULSE = "pulse";

    /** Wake-to-first-frame latency from a parked loop, over a run of trials. */
    static final String PROBE_BUTTON = "button.probe";
    static final String LATENCY = "latency";

    /** Three seconds of mutations with no pause, and the frame rate the loop settled at under them. */
    static final String STORM_BUTTON = "button.storm";
    static final String STORM = "storm";

    /**
     * A click whose handler times the hop: from the frame that took the click, to the frame that draws what the
     * handler wrote. The readout aggregates every click so far.
     */
    static final String TAP_BUTTON = "button.tap";
    static final String TAP = "tap";

    /** Rewritten by the probes to make the wake; its text is the trial number and means nothing else. */
    static final String TICK = "tick";

    private Landmarks() {
    }
}
