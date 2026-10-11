package ${packageName};

/**
 * Every name the witness's driver writes down. No two windows share one, so a {@code find} that reaches a node in
 * the wrong window is a failure the driver can see rather than a lucky match.
 */
final class Landmarks {

    static final String COUNT = "count";
    static final String COUNT_BUTTON = "button.count";
    /** Opens the notes window, a peer of the main one. */
    static final String NOTES_BUTTON = "button.notes";
    /** Opens the palette, a satellite of the main one. */
    static final String PALETTE_BUTTON = "button.palette";
    /** Quits, by closing the main window through its handle. */
    static final String QUIT_BUTTON = "button.quit";
    /** Asks a question through the framework's Dialogs, whose answer counts. */
    static final String ASK_BUTTON = "button.ask";

    /** In the notes window: the count, as that window shows it. */
    static final String NOTES_COUNT = "notes.count";
    /** In the notes window: how many times this process has opened it, which only its listener knows. */
    static final String NOTES_OPENED = "notes.opened";

    /** In the palette: the count, as that window shows it. */
    static final String PALETTE_COUNT = "palette.count";
    /** In the palette: counts, from a window other than the main one. */
    static final String PALETTE_BUMP = "palette.bump";
    /** In the palette: closes it the way its title bar's X does. */
    static final String PALETTE_CLOSE = "palette.close";

    private Landmarks() {
    }
}
