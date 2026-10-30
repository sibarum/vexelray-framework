package dev.vexelray.framework.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A view's {@code @Draw} method (T7.6, vexelray-gui's {@code docs/plans/frame-contract.md}): its shape is
 * {@link Declarations}', and its body is {@link StepCheck}'s, held to a step's rules and to the frame's besides.
 *
 * <p>As in {@link StepCheckTest}, the refusals are one each, and the acceptances matter as much: a draw that reads its
 * state, does arithmetic, calls a pure helper, names a node it holds and sets an offset must compile, and so must a
 * string javac folds to a constant. vexelray-gui is not on this module's path, so its {@code Frame}, {@code Drawn} and
 * {@code Node} are stand-ins of the same names, compiled beside the view.
 */
class DrawCheckTest {

    @TempDir
    Path temp;

    private static final Map<String, String> GUI = Map.of(
            "dev/vexelray/gui/core/Frame", """
                    package dev.vexelray.gui.core;
                    public record Frame(long presentNanos, boolean reducedMotion) {
                        public static final long NEVER = Long.MAX_VALUE;
                        public boolean owes(long due) { return due <= presentNanos; }
                    }
                    """,
            "dev/vexelray/gui/core/Drawn", """
                    package dev.vexelray.gui.core;
                    public interface Drawn { Drawn offset(Node node, float dx, float dy); }
                    """,
            "dev/vexelray/gui/core/Node", """
                    package dev.vexelray.gui.core;
                    public final class Node {
                        public float width;
                        public Node width(float w) { width = w; return this; }
                    }
                    """);

    private static final String HEAD = """
            package app;
            import dev.vexelray.framework.api.*;
            import dev.vexelray.gui.core.*;
            import java.util.*;
            @View
            public final class Strip {
                public record Scroll(Node rows, float from, float to, long start, long over, String label) {
                    float at(long now) {
                        if (now >= start + over) { return to; }
                        return from + (to - from) * (now - start) / (float) over;
                    }
                }
                public Scroll state = new Scroll(new Node(), 0f, 0f, 0L, 1L, "");
                public Strip() {}
                static final String PREFIX = "> ";
                static int counter;
                static final java.util.logging.Logger LOG = java.util.logging.Logger.getGlobal();
            """;

    private List<String> errors(String body) throws IOException {
        return compile("app/Strip", HEAD + body + "\n}\n");
    }

    private List<String> compile(String path, String source) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path out = Files.createDirectories(temp.resolve("out"));
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
            List<JavaFileObject> units = new ArrayList<>();
            units.add(unit(path, source));
            GUI.forEach((name, code) -> units.add(unit(name, code)));
            JavaCompiler.CompilationTask task = javac.getTask(null, files, diagnostics,
                    List.of("-classpath", System.getProperty("java.class.path")), null, units);
            task.setProcessors(List.of(new VexelProcessor()));
            task.call();
        }
        List<String> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                errors.add(d.getMessage(Locale.ROOT));
            }
        }
        return errors;
    }

    private static JavaFileObject unit(String path, String code) {
        return new SimpleJavaFileObject(URI.create("string:///" + path + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
    }

    private void refused(String body, String fragment) throws IOException {
        List<String> errors = errors(body);
        assertEquals(1, errors.size(), () -> "expected one error containing \"" + fragment + "\", got:\n"
                + String.join("\n", errors));
        assertTrue(errors.get(0).contains(fragment), () -> "expected \"" + fragment + "\" in:\n" + errors.get(0));
    }

    private void accepted(String body) throws IOException {
        assertEquals(List.of(), errors(body));
    }

    // --- the body: what a step may not do, and what a frame may not either ----------------------------------

    @Test
    void aDrawThatReadsItsStateAndSetsAnOffsetCompiles() throws IOException {
        accepted("""
                @Draw
                public static long draw(Scroll s, Frame frame, Drawn out) {
                    long now = frame.presentNanos();
                    float y = s.at(now);
                    out.offset(s.rows(), 0f, y);
                    String tag = PREFIX + "row";               // folded to a constant: nothing built
                    return now >= s.start() + s.over() ? Frame.NEVER : now;
                }
                """);
    }

    @Test
    void anAllocationIsRefusedAndToldThatToldBuilds() throws IOException {
        refused("""
                @Draw
                public static long draw(Scroll s, Frame frame, Drawn out) {
                    Object box = new Object();
                    return Frame.NEVER;
                }
                """, "@Draw Strip.draw creates a new Object, which allocates in every frame. An asked half reads only its"
                + " state, the Frame and its Drawn, and allocates nothing, since it runs in every frame"
                + " (vexelray-gui docs/plans/frame-contract.md, B): build it in a @Subscribe method");
    }

    @Test
    void anArrayIsRefused() throws IOException {
        refused("""
                @Draw
                public static long draw(Scroll s, Frame frame, Drawn out) {
                    float[] at = {s.from(), s.to()};
                    return Frame.NEVER;
                }
                """, "builds an array, which allocates in every frame");
    }

    @Test
    void aStringBuiltFromStateIsRefused() throws IOException {
        refused("""
                @Draw
                public static long draw(Scroll s, Frame frame, Drawn out) {
                    String shown = PREFIX + s.label();
                    return Frame.NEVER;
                }
                """, "builds a string, which allocates in every frame");
    }

    @Test
    void aVarargsCallIsRefusedForTheArrayItBuilds() throws IOException {
        refused("""
                @Draw
                public static long draw(Scroll s, Frame frame, Drawn out) {
                    long most = Collections.max(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L));
                    return Frame.NEVER;
                }
                """, "calls List.of with varargs, which builds an array in every frame");
    }

    @Test
    void touchingANodeItHoldsIsRefused() throws IOException {
        refused("""
                @Draw
                public static long draw(Scroll s, Frame frame, Drawn out) {
                    s.rows().width(10f);
                    return Frame.NEVER;
                }
                """, "calls Node.width. A node in an asked half is a handle");
    }

    @Test
    void theThreadIsRefused() throws IOException {
        refused("""
                @Draw
                public static long draw(Scroll s, Frame frame, Drawn out) {
                    return Thread.currentThread() == null ? 0L : Frame.NEVER;
                }
                """, "calls Thread.currentThread. An asked half runs on the GUI thread");
    }

    @Test
    void theBusIsRefused() throws IOException {
        refused("""
                @Draw
                public static long draw(Scroll s, Frame frame, Drawn out) {
                    return sibarum.atchung.Topic.edge(Scroll.class) == null ? 0L : Frame.NEVER;
                }
                """, "calls Topic.edge, which reaches the bus");
    }

    @Test
    void loggingIsRefused() throws IOException {
        refused("""
                @Draw
                public static long draw(Scroll s, Frame frame, Drawn out) {
                    LOG.fine("drawn");
                    return Frame.NEVER;
                }
                """, "calls Logger.fine, which logs in every frame");
    }

    @Test
    void aStepsRefusalsHoldTooWordedForAView() throws IOException {
        refused("""
                @Draw
                public static long draw(Scroll s, Frame frame, Drawn out) {
                    counter++;
                    return Frame.NEVER;
                }
                """, "@Draw Strip.draw writes the static field app.Strip.counter");
    }

    @Test
    void anEffectIsRefusedAndToldWhereItBelongs() throws IOException {
        refused("""
                @Draw
                public static long draw(Scroll s, Frame frame, Drawn out) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        return 0L;
                    }
                    return Frame.NEVER;
                }
                """, "an asked half makes no effects and never waits: do it in a"
                + " @Subscribe method or a @Job");
    }

    @Test
    void aHelperItCallsIsReadWithTheSameRules() throws IOException {
        refused("""
                @Draw
                public static long draw(Scroll s, Frame frame, Drawn out) {
                    out.offset(s.rows(), 0f, eased(s, frame.presentNanos()));
                    return Frame.NEVER;
                }
                static float eased(Scroll s, long now) {
                    float[] keep = new float[1];
                    return s.at(now);
                }
                """, "@Draw Strip.draw builds an array");
    }

    // --- the shape -------------------------------------------------------------------------------------------

    @Test
    void aDrawMustBeStatic() throws IOException {
        List<String> errors = errors("""
                @Draw
                public long draw(Scroll s, Frame frame, Drawn out) { return Frame.NEVER; }
                """);
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("@Draw Strip.draw is not static")), errors::toString);
    }

    @Test
    void aDrawTakesStateFrameAndDrawn() throws IOException {
        refused("""
                @Draw
                public static long draw(Frame frame, Drawn out) { return Frame.NEVER; }
                """, "@Draw Strip.draw must take (state, Frame, Drawn)");
    }

    @Test
    void aDrawAnswersWhenItIsNextDue() throws IOException {
        refused("""
                @Draw
                public static void draw(Scroll s, Frame frame, Drawn out) {}
                """, "returns void, and must return a long");
    }

    @Test
    void theStateIsHeldInExactlyOneField() throws IOException {
        refused("""
                public Scroll previous = state;
                @Draw
                public static long draw(Scroll s, Frame frame, Drawn out) { return Frame.NEVER; }
                """, "Strip has 2 instance fields of that type. A view keeps its state in exactly one field");
    }

    @Test
    void theStateIsImmutableExceptForTheNodesItNames() throws IOException {
        refused("""
                public static final class Loose { public float y; }
                public Loose loose = new Loose();
                @Draw
                public static long draw(Loose s, Frame frame, Drawn out) { return Frame.NEVER; }
                """, "@Draw Strip.draw takes its state as app.Strip.Loose, which is not deeply immutable");
    }

    @Test
    void aDrawBelongsToAView() throws IOException {
        List<String> errors = compile("app/Plain", """
                package app;
                import dev.vexelray.framework.api.*;
                import dev.vexelray.gui.core.*;
                public final class Plain {
                    @Draw
                    public static long draw(Object s, Frame frame, Drawn out) { return Frame.NEVER; }
                }
                """);
        assertEquals(1, errors.size(), errors::toString);
        assertTrue(errors.get(0).startsWith("@Draw Plain.draw is not on a @View"), errors::toString);
    }
}
