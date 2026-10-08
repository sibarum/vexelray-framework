package ${packageName};

/** Every name the witness's driver writes down. The template's, and one per thing a component can change. */
final class Landmarks {

    static final String CARD = "card";
    static final String COUNT = "count";
    static final String NOTE = "note";
    static final String COUNT_BUTTON = "button.count";
    static final String RESET_BUTTON = "button.reset";

    /** Sends a job to the worker lane; its result is {@link #RESULT}. */
    static final String WORK_BUTTON = "button.work";
    /** Asks the default lane to echo; the answer is {@link #ECHO}. */
    static final String ECHO_BUTTON = "button.echo";
    /** Wedges a component on the default lane, which every other default-lane component shares. */
    static final String WEDGE_DEFAULT_BUTTON = "button.wedge.default";
    /** Wedges a component on a lane of its own, which nothing else shares. */
    static final String WEDGE_ISOLATED_BUTTON = "button.wedge.isolated";

    /** Opens the tool window, a second window that closes while the application runs on. */
    static final String TOOL_BUTTON = "button.tool";
    /** In the tool window: closes it the way its title bar's X does. */
    static final String TOOL_CLOSE_BUTTON = "button.tool.close";

    static final String RESULT = "result";
    static final String ECHO = "echo";
    static final String ISOLATED = "isolated";
    static final String DEFAULT = "default";
    static final String STALL = "stall";

    private Landmarks() {
    }
}
