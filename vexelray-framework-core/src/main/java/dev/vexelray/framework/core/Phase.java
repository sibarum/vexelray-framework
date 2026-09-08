package dev.vexelray.framework.core;

/**
 * The order in which an application comes into existence.
 *
 * <p>Spring has no equivalent of this and does not need one: a bean either has its dependencies or it does not,
 * and the container is free to instantiate in whatever order the graph allows. Here the graph is not the only
 * constraint. Several of the seams this framework wires are only valid inside a window of time — a theme must
 * be set before the first widget reads a role, a clock must be attached before the first animating widget is
 * constructed, a window handle does not exist until the window does — and every one of those windows is
 * currently documented as a comment in a hand-written {@code main} and enforced by nothing.
 *
 * <p>So phases are not a scheduling convenience; they are those constraints, written as a type. Each boundary
 * below exists because getting it wrong is a real defect on this stack, and the comment quoted with it is the
 * place that defect was already written down.
 *
 * <p><b>A component never declares its phase.</b> It is inferred as the latest phase of anything the component
 * depends on, which makes the phase a consequence of the code rather than a second thing to keep in agreement
 * with it. What the processor rejects is a dependency pointing <em>backwards</em>: something in {@link #MODEL}
 * asking for a value that only exists from {@link #WINDOW} on.
 */
public enum Phase {

    /**
     * Configuration: the settings store, and the values bound out of it.
     *
     * <p>First because everything can want configuration and configuration can want nothing. This is also
     * where the store's single instance is established, which is the whole of one bug the demos carry a comment
     * about: <i>"One Settings for the whole application, shared rather than opened twice: two instances over
     * the same file each hold their own copy of it, so the second one to save would drop whatever the first had
     * added."</i> A container that owns the instance makes that true by construction instead of by vigilance —
     * and it is the clearest single argument for there being a container here at all.
     */
    CONFIG,

    /**
     * Application state: what the application knows, independent of how it is drawn.
     *
     * <p>Before the GUI exists, so that nothing in this phase can reach for it. That is a layering claim the
     * scaffold already makes in prose — <i>"everything the application knows is in Model"</i>, and the edge
     * <i>"holds no state of its own... the moment the edge starts remembering things, there are two places a
     * value can live"</i> — and putting it before {@link #GUI} is what stops it being merely advice.
     */
    MODEL,

    /**
     * The {@code Gui} exists and is configured: theme, minimum size, zoom range, and the frame clock attached.
     *
     * <p>Everything that has to be true <em>before the first widget is constructed</em> happens here, and both
     * halves are load-bearing for the same kind of reason. The look, because <i>"a role resolves at the moment
     * a widget writes a prop, so a theme set afterwards reaches the renderer's own chrome and nothing else"</i>.
     * The clock, because <i>"a widget that animates is handed its timing at construction"</i>.
     *
     * <p>Both failures are silent and cosmetic — a half-themed window, an animation that never runs — which is
     * exactly the class of bug that survives a test suite and is found by eye months later.
     */
    GUI,

    /**
     * The widget tree: everything built out of {@link #GUI} and {@link #MODEL}.
     *
     * <p>The tree may be built before there is a window to show it in, and on this stack it always is. That is
     * not incidental: the application's own title bar is a widget, so it exists and lays out against no window
     * controls at all until {@link #ATTACH} points it at some — <i>"until now the bar has been a working bar
     * against WindowControls.NONE — which is also what --capture renders"</i>. A tree that cannot be built
     * without a window could not be captured headlessly.
     */
    TREE,

    /**
     * The window and the GPU device exist. {@code GuiApp} is constructed here, and a window handle is real for
     * the first time.
     *
     * <p><b>The main-thread boundary.</b> From here on the framework is running where Vulkan, the window and
     * present are required to run, and {@code @MainThread} values start existing. Nothing before this phase may
     * hold one.
     */
    WINDOW,

    /**
     * Everything that needs the window handle: the input backend attached and its coordinate space settled, the
     * clipboard installed, the title bar pointed at real window controls, window memory watching, dialogs
     * installed, the close gate armed, the automation socket bound.
     *
     * <p>This phase is the largest and the most mechanical, and it is the bulk of what a hand-written
     * application edge spends its length on. Every item in it is a fixed recipe with one correct answer, which
     * is why it can be a phase rather than a chapter of documentation.
     */
    ATTACH,

    /**
     * The frame loop, and after it the reverse-order shutdown.
     *
     * <p>Nothing is constructed in this phase. It is here so that the loop is a position in the same ordering
     * as everything else rather than a separate concept — and so shutdown has a phase to run backwards from.
     */
    RUN
}
