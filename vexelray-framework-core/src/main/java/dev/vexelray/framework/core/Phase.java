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
 * with it. What the processor will reject is a dependency pointing <em>backwards</em>: something in
 * {@link #MODEL} asking for a value that only exists from {@link #WINDOW} on. Until it is written, a
 * hand-written wiring works its own phases out and {@code Shell}'s accessors catch the mistake at startup
 * instead — a backstop standing in for a compile error, which is the trade recorded on {@code Shell}.
 *
 * <p><b>Each member below lists what its phase contains, and nothing checks the list.</b> That is this file's
 * one known hazard, and it has already cost something: porting the text editor found four capabilities the
 * framework documented and did not have, three of them named in a phase's own list of contents — including
 * {@link #TREE}'s complete and correct argument for a headless tree that no entry point produced. A member's
 * list is prose about {@code VexelApplication}, so it can drift from {@code VexelApplication} silently and the
 * processor will never catch it. Change one and read the other.
 */
public enum Phase {

    /**
     * Configuration: the settings store, the look as values, and the {@code @Setting} values bound out of the
     * store.
     *
     * <p>The framework opens the store and takes the {@code Appearance}; the binding is generated code, so in a
     * hand-written wiring it is whatever that wiring's {@code config} does. That division is worth stating
     * because it is the one item in this enum whose contents are only partly the framework's.
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
     *
     * <p>The minimum size and the zoom range are here for a weaker reason, and the difference is worth being
     * honest about: neither is read at construction, so either could be set later without breaking anything.
     * They are applied here because they are part of the {@code Appearance} the application declared in
     * {@link #CONFIG}, and one place that applies all of it is worth more than a distinction nobody can see.
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
     * clipboard installed, the title bar pointed at real window controls, window memory watching and the
     * remembered zoom restored, the dialogs installed, and the frame loop's stages, deadlines and wakes
     * connected.
     *
     * <p>This phase is the largest and the most mechanical, and it is the bulk of what a hand-written
     * application edge spends its length on. Every item in it is a fixed recipe with one correct answer, which
     * is why it can be a phase rather than a chapter of documentation.
     *
     * <p><b>Two things this phase is the place for and the framework deliberately does not do.</b> The list
     * above is what the framework builds; these are seams it opens and leaves empty, and the distinction is
     * the one the text-editor port found the framework's own documentation getting wrong.
     *
     * <ul>
     *   <li><b>The close gate.</b> {@code Shell.onClose} is registerable from here, and the framework installs
     *       no gate of its own — the default has to be that closing closes. A framework that interposed here
     *       would be deciding, for every application, that quitting is a question.</li>
     *   <li><b>The automation socket.</b> Bound by {@code vexelray-framework-automation}'s {@code Driver},
     *       called from an application's own {@code attach}, because a listening socket linked into every
     *       native binary is the wrong trade for a framework whose selling point is what it does not
     *       include.</li>
     * </ul>
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
