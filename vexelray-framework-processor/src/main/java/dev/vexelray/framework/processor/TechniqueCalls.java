package dev.vexelray.framework.processor;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.Set;

/**
 * A technique is declared, never called (threading.md T7.6, vexelray-gui's {@code docs/plans/device-feed.md}, 4c): an
 * application hands its {@code RenderTechnique}s to a {@code TechniqueView}, and from then on only the host calls
 * {@code realize}, {@code record} and {@code close}, on the thread that records frames, inside a frame. A call to one of
 * them from anywhere else records Vulkan commands into nothing, or frees what a frame in flight still reads, and nothing
 * at run time would say which. So it is refused here, wherever the application spells it.
 *
 * <p>What is not refused, because it is safe: a class that is itself a technique calling another (a composite records
 * its parts inside its own {@code record}, under the same contract); and {@code bindings()} and {@code bindingSet()},
 * which are declarations. The check reads calls by what they resolve to, so a technique held as an interface, a
 * concrete class or a field is caught alike. Where vexelray's engine API is not on the path, there are no techniques and
 * nothing to check.
 *
 * <p><b>When it reads: after javac has analyzed a class</b>, on its {@code ANALYZE} event, as {@link StepCheck} does
 * and for its reason. Resolving a class's calls while annotations are processed made javac attribute it there and then,
 * which adds a record's implicit {@code super()} to its compact constructor's tree; javac's own pass then refused every
 * record with a compact constructor, in every application compiled with the processor.
 */
final class TechniqueCalls implements TaskListener {

    static final String TECHNIQUE = "dev.vexelray.engine.RenderTechnique";
    /** What only the host calls: the technique's lifecycle. */
    private static final Set<String> HOSTS = Set.of("realize", "record", "close");

    private final Trees trees;
    private final Types types;
    private final TypeMirror technique;
    private final Report report;

    private TechniqueCalls(Trees trees, Types types, TypeMirror technique, Report report) {
        this.trees = trees;
        this.types = types;
        this.technique = technique;
        this.report = report;
    }

    /**
     * Listen to this compilation, to read each class once javac has analyzed it. Does nothing where javac's trees are
     * not to hand (another compiler) or no technique type is on the path: then nothing is refused.
     */
    static void install(ProcessingEnvironment env, Report report) {
        TypeElement t = env.getElementUtils().getTypeElement(TECHNIQUE);
        if (t == null) {
            return;
        }
        try {
            JavacTask.instance(env).addTaskListener(new TechniqueCalls(Trees.instance(env), env.getTypeUtils(),
                    env.getTypeUtils().erasure(t.asType()), report));
        } catch (IllegalArgumentException notJavac) {
            // Not javac: no trees to read.
        }
    }

    /** A class analyzed: every call in it, and in the classes nested in it. */
    @Override
    public void finished(TaskEvent event) {
        if (event.getKind() == TaskEvent.Kind.ANALYZE && event.getTypeElement() != null) {
            check(event.getTypeElement());
        }
    }

    /** Every call in {@code type}, and the classes nested in it, outside a technique of its own. */
    private void check(TypeElement type) {
        TreePath path = trees.getPath(type);
        if (path == null) {
            return;
        }
        CompilationUnitTree unit = path.getCompilationUnit();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree tree, Void unused) {
                Element e = trees.getElement(getCurrentPath());
                if (e instanceof TypeElement te && isTechnique(te.asType())) {
                    return null;   // a technique calling its parts does so inside the host's call to it
                }
                return super.visitClass(tree, unused);
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                Element e = trees.getElement(getCurrentPath());
                if (e instanceof ExecutableElement method && HOSTS.contains(method.getSimpleName().toString())
                        && method.getEnclosingElement() instanceof TypeElement owner && isTechnique(owner.asType())) {
                    report.at(trees, Diagnostic.Kind.ERROR, Mirrors.simple(type) + " calls " + Mirrors.simple(owner)
                            + "." + method.getSimpleName() + "(), which only the host that runs a technique calls: on the"
                            + " thread that records frames, inside a frame. Hand the technique to a TechniqueView"
                            + " (Node.image(view)), tell it its numbers through the view's Params from the @Draw"
                            + " (Drawn.param), and bind what it reads (TechniqueView.bind); the host realises, records"
                            + " and closes it (threading.md T7.6)", call, unit);
                }
                return super.visitMethodInvocation(call, unused);
            }
        }.scan(path, null);
    }

    private boolean isTechnique(TypeMirror type) {
        return types.isSubtype(types.erasure(type), technique);
    }
}
