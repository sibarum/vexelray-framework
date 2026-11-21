package dev.vexelray.framework.highlight;

import dev.vexelray.framework.api.All;
import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Default;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.api.Stability;

/**
 * Syntax highlighting, as a starter: name it and an application has {@link Highlighting} for the job that colours its
 * documents, with sixteen TextMate grammars, TM4E and their native-image metadata, and nothing copied into the
 * application.
 *
 * {@snippet :
 * @VexelApp(name = "notes", title = "Notes", starters = {HighlightStarter.class, Core.class})
 * final class NotesApp { }
 * }
 *
 * <p><b>What the application says, and how.</b> Three things, each by providing a type, so each is a decision the
 * processor checks at compile time rather than a call made at startup:
 * <ul>
 *   <li><b>Its look:</b> a {@code @Provides Colours} backs off {@link #colours()}, the stack's standard table.</li>
 *   <li><b>Its own languages:</b> each {@code @Provides Language} anywhere in the application, a {@code -core} module's
 *       configuration included, is one more ({@link Language}). They are taken as {@code All<Language>}, so none
 *       names another and leaving one out of the build is one fewer; none is the bundled set alone.</li>
 *   <li><b>All of it:</b> a {@code @Provides Highlighting} of its own backs off {@link #highlighting} altogether.</li>
 * </ul>
 *
 * <p><b>Built first, from nothing the framework makes</b>, and parsing nothing until a document wants a grammar, so
 * naming the starter costs a start nothing. Taken by a {@code @Job} (two requests may run at once, and
 * {@link Highlighting} is safe for that) and by a view or a recipe for {@link Highlighting#scopeFor} and
 * {@link Highlighting#nameOf}, which take no lock. Not by a {@code @Component}: see {@link Highlighting}.
 *
 * <p><b>Why the dependency and the name are two decisions</b>, as for {@code AutomationStarter}: depending on this
 * module is what links TM4E, joni and the grammars into the binary at all, and naming this class is what builds the
 * part. A starter named and not on the path is a compile error, so the two cannot drift apart silently.
 */
@Configuration
@Stability(Stability.Level.EXPERIMENTAL)
public final class HighlightStarter {

    /** The stack's standard colour table ({@link Colours#standard()}), unless the application provides its own. */
    @Default
    @Provides
    public Colours colours() {
        return Colours.standard();
    }

    /** The bundled languages and the application's own, coloured by {@code colours}. */
    @Default
    @Provides
    public Highlighting highlighting(Colours colours, All<Language> languages) {
        return Highlighting.of(colours, languages.list());
    }
}
