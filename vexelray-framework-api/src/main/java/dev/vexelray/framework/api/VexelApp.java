package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the application's entry class, and carries the handful of facts the shell cannot infer.
 *
 * <p>One per application. The processor will generate a wiring class beside it, and the annotated class's
 * {@code main} is a single call into the runtime:
 *
 * <pre>{@code
 * @VexelApp(name = "text-editor", title = "Text Editor", width = 800, height = 592)
 * public final class TextEditorApp {
 *     public static void main(String[] args) {
 *         VexelApplication.run(new TextEditorAppWiring(), args);
 *     }
 * }
 * }</pre>
 *
 * <p>{@code run} takes a {@code Wiring} instance, so that is a constructor call and not a constructor
 * reference — which is what all three ported applications write. Today the class it names is hand-written; the
 * annotation changes nothing about the call, only about who types the class.
 *
 * <p><b>{@link #name()} is not cosmetic.</b> It is the settings directory — {@code $HOME/.{name}/} — so it is
 * the one value here that, changed after a release, loses a user's window placement and open files. The other
 * three are first-run defaults, which window memory overwrites the moment there is anything remembered.
 *
 * <p><b>Checked, not yet generated.</b> {@code vexelray-framework-processor} allows one per compilation, requires
 * every starter to be a {@link Configuration}, and reads the starters' providers into its conflict check. No
 * wiring class is generated yet. See {@link dev.vexelray.framework.api} for which half of this package is built.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface VexelApp {

    /**
     * The application's own name: the settings directory it keeps state in, and the key its windows are
     * remembered under. Stable across releases, or the user loses their placement.
     */
    String name();

    /** The main window's title. */
    String title();

    /** The main window's width on a first run, in the engine's logical coordinates — a default, not a floor. */
    int width() default 800;

    /** The main window's height on a first run, in the engine's logical coordinates. */
    int height() default 600;

    /**
     * The {@link Configuration} classes this application is built out of, beyond its own.
     *
     * <p><b>Listed, not discovered, and this is the load-bearing decision of the whole mechanism.</b> A
     * processor sees the elements of the compilation it was handed; it does not see annotated types sitting in
     * dependency jars. So a framework that lets you drop a starter on the classpath and have it configure
     * itself has to go and find it — which means walking every class file in every jar on every compile. That
     * is Spring's classpath scan moved one step to the left: still proportional to the classpath rather than to
     * what the application uses, and still leaving nobody able to say which jar contributed what.
     *
     * <p>A class literal costs one line and buys three things. javac resolves it, so a starter that has been
     * renamed or is not on the path is a <b>compile error</b> rather than a component missing at startup —
     * and that one is true today, because javac resolves the literal whether or not anything reads the
     * annotation. The processor obtains the type by resolution rather than search, so build time is
     * proportional to what is used. And the set of things configuring this application is written at the one
     * place somebody reads when they want to know.
     *
     * <p>What this gives up is "add a jar, get behaviour" — which for a framework whose output is a native
     * binary was never real, since the jar reaches the binary only by recompiling anyway. What it keeps is the
     * part that mattered: the thirty components <em>inside</em> a starter are still not hand-wired.
     */
    Class<?>[] starters() default {};
}
