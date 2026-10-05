package ${packageName};

import dev.vexelray.framework.api.VexelApp;
import dev.vexelray.framework.automation.AutomationStarter;
import dev.vexelray.framework.shell.VexelApplication;


/**
 * ${summary}
 *
 * <h2>What this class is</h2>
 *
 * <p>The entry point, and the constants the rest of this package reads. That is all it is, and the reason is
 * worth knowing before anything else here: <b>the application edge is the framework's.</b> Opening an input
 * backend, installing a clipboard, remembering where the window was, attaching a clock, wiring the frame loop with
 * its wakes and its pacing, installing the dialogs, parsing the command line and closing everything in the right
 * order are all done for you.
 *
 * <p>What this application builds — a text editor with tabs and a file navigator — is in {@link Recipes}, one
 * method per part. {@code ${className}Wiring}, which builds those parts in order, is generated from them and from
 * the annotation on this class while the project compiles.
 *
 * <pre>
 * ${className}                     the editor, on the folder it was last showing (or the working directory)
 * ${className} &lt;path&gt;...          open these: a folder becomes the navigator's root, a file a tab
 * ${className} &lt;frames&gt;            run a fixed number of frames and quit (a script, not a session)
 * ${className} --key=value         override a setting for this launch
 * </pre>
 *
 * <p>A misspelled flag is refused by name with the alternatives listed, rather than a stack trace before any
 * window. Needs {@code --enable-native-access=ALL-UNNAMED}.
 *
 * <p><b>{@code starters} is everything configuring this application beyond {@link Recipes}</b>, listed rather
 * than discovered. {@link AutomationStarter} is the driving socket — off unless {@code --automation} or
 * {@code -Dautomation} asks, and loopback-only when it is, because it hands whoever reaches it full control of the
 * application's input. Delete it here, and the {@code vexelray-framework-automation} dependency in the pom, and the
 * binary links no socket at all.
 */
@VexelApp(name = ${className}.APP, title = ${className}.TITLE, width = ${className}.W, height = ${className}.H,
        starters = AutomationStarter.class)
public final class ${className} {

    /** The application's own name, which is what its settings directory is called. Stable across releases. */
    static final String APP = "${appName}";

    /** The window's title. */
    static final String TITLE = "${title}";

    /** Window size on a first run, in the engine's logical coordinates. */
    static final int W = ${width};
    static final int H = ${height};

    /**
     * The smallest this UI is still coherent at, in root ems — a floor, not the design size: a navigator and a
     * document side by side, each still wide enough to read.
     *
     * <p>Named here rather than written at each use because it is read from two places that have to agree:
     * {@link Recipes#look} declares it to the framework, and a screenshot at exactly the minimum
     * ({@code ottermate --size 36emx20em shot}) is the picture that shows a panel outgrowing it.
     */
    static final float MIN_W_EM = 36;
    static final float MIN_H_EM = 20;

    /**
     * Entry point.
     *
     * <p>There is no screenshot flag here on purpose. A picture of a running window, at a chosen size, zoom and
     * density, is {@code ottermate}'s, because it photographs the application's own device and so is correct
     * about content as well as chrome. See the README, <i>Taking a screenshot</i>.
     */
    public static void main(String[] args) {
        String[] cleaned = java.util.Arrays.stream(args).filter(s -> !s.isBlank()).toArray(String[]::new);
        VexelApplication.run(new ${className}Wiring(), cleaned);
    }

    private ${className}() {
    }
}
