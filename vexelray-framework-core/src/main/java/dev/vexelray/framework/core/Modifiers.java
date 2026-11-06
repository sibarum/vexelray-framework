package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Topic;

/**
 * <b>The modifier keys held in a window</b>, said each time the set held there changes: a held key read as a mode
 * the person is in, not as a command. Shift held while a selection is shown can propose where a range ends; Control
 * held, the rule the picks are examples of (Vexplore FN-9). A key that makes a chord is still the chord's: this only
 * listens.
 *
 * <p>State, one piece per window: a part hears the last word for each window it hears, and a window losing focus
 * says {@link #none() none held}, since a key released over another window is never seen here and a mode held for
 * ever would be one there is no way out of. Left and right are not told apart.
 *
 * <p>Booleans rather than tactroller's {@code Modifier}, because a message here is heard by an application's
 * {@code -core}, which builds against the JDK and the bus alone.
 *
 * {@snippet :
 * // a step on Modifiers.TOPIC, holding what is held for the steps that propose
 * return s.withHeld(held.main() ? held : s.held());
 * }
 *
 * @param window  the window's key
 * @param shift   Shift is held
 * @param control Control is held
 * @param alt     Alt (Option) is held
 * @param meta    the Windows or Command key is held (tactroller's {@code SUPER})
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record Modifiers(String window, boolean shift, boolean control, boolean alt, boolean meta) {

    public static final Topic<Modifiers> TOPIC = Topic.state(Modifiers.class, Modifiers::window);

    public Modifiers {
        java.util.Objects.requireNonNull(window, "window");
    }

    /** Nothing held in {@code window}. */
    public static Modifiers released(String window) {
        return new Modifiers(window, false, false, false, false);
    }

    /** Whether nothing is held. */
    public boolean none() {
        return !shift && !control && !alt && !meta;
    }

    /** Whether this is the main window. */
    public boolean main() {
        return CloseRequested.MAIN.equals(window);
    }
}
