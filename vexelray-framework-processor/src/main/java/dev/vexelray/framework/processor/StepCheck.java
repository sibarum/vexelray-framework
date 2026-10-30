package dev.vexelray.framework.processor;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Reads the body of every step declared on an atchung {@code Ledger.Builder}, and refuses what is certainly wrong in
 * one (vexelray-framework's {@code docs/plans/chains.md}, rule 4 and design F).
 *
 * <p><b>A list of definite wrongs, never a proof.</b> Java cannot express that a step is safe, so a check that asked
 * for proof would refuse correct code, and an escape from a false refusal would be debt by construction. So this
 * refuses only what no safe step does, and lets through what it cannot judge: a call into code it cannot read (a
 * library, an interface), or a body held in a variable. An author who gets past it owns what follows. What it
 * refuses:
 *
 * <ul>
 *   <li><b>A capture.</b> A variable from outside the step, or the enclosing object's fields or methods (which is a
 *       capture of {@code this}). A step sees its state, its arguments, its {@code Out} and constants.</li>
 *   <li><b>A static field written</b>, or read when it is not {@code final}, and any {@code volatile} field: state
 *       another thread can see or change.</li>
 *   <li><b>A lock</b> ({@code synchronized}), which can stall the lane.</li>
 *   <li><b>A known effect or wait</b>: the JDK calls listed in {@link #EFFECTS}, by what they are (disk, network, a
 *       thread started or waited on, the process ended or another started), never everything in a package. A
 *       {@code StringWriter} is {@code java.io} and harmless, so {@code java.io} as a whole is not refused.</li>
 * </ul>
 *
 * <p>A step's body is a lambda or a method reference. A method it calls, or a method reference names, is read the
 * same way when its source is in this compilation, so a step written as a static method, or one that leans on a
 * helper, is checked through to the helper; its {@code this}, if it is an instance method, is the receiver's, not a
 * capture. Under a compiler without javac's tree API there is nothing to read, and nothing is refused.
 *
 * <p><b>A view's {@code @Draw} method is read the same way, and held to more</b> (T7.6, vexelray-gui's
 * {@code docs/plans/frame-contract.md}): it runs in every frame the window draws, so on top of a step's refusals it may
 * not allocate ({@code new}, an array, a string built, a varargs call), log, touch a {@code Thread} or reach the bus.
 * The same principle holds: an allocation hidden in a library call it cannot read is let through, and a string that
 * javac folds to a constant is not built at all.
 *
 * <p><b>When it reads: after javac has attributed a class, never while annotations are processed.</b> Asking javac
 * what a name means in a tree it has not attributed makes it attribute the whole class there and then, and
 * attribution rewrites trees in place (a record's compact constructor gains its implicit {@code super()}). Trees
 * outlive a processing round, so the real pass then found a constructor call that was not in the source and refused
 * every record with a compact constructor. So the check listens for each class's {@code ANALYZE} event, after flow
 * analysis and before desugaring, the point every javac plugin reads at. A helper in a class analyzed earlier has
 * since been desugared into code that no longer looks like the source, so a call into one is not read, and is let
 * through; a helper in a class not analyzed yet is attributed on demand, as javac itself does.
 */
final class StepCheck implements TaskListener {

    private static final String BUILDER = "sibarum.atchung.Ledger.Builder";

    /** What an effect is, said in the error so the author sees why it was refused. */
    private enum Effect {
        IO("does I/O"),
        NETWORK("goes to the network"),
        BLOCKS("blocks its thread"),
        THREAD("starts work on another thread"),
        EXIT("ends the process"),
        PROCESS("starts a process");

        final String what;

        Effect(String what) {
            this.what = what;
        }
    }

    /** "*" is every method and constructor of the type; "<init>" is its constructors. */
    private static final Map<String, Map<String, Effect>> EFFECTS = new HashMap<>();

    /** Packages every type of which is an effect. */
    private static final Map<String, Effect> PACKAGES = Map.of(
            "java.nio.channels", Effect.IO,
            "java.net.http", Effect.NETWORK);

    /** {@code java.io.File}'s methods that only read the path, not the disk. */
    private static final Set<String> PURE_FILE = Set.of("<init>", "getName", "getPath", "getParent",
            "getParentFile", "isAbsolute", "toPath", "toString", "equals", "hashCode", "compareTo", "toURI");

    static {
        all(Effect.IO, "java.io.FileInputStream", "java.io.FileOutputStream", "java.io.FileReader",
                "java.io.FileWriter", "java.io.RandomAccessFile", "java.io.Console", "java.nio.file.Files");
        all(Effect.NETWORK, "java.net.Socket", "java.net.ServerSocket", "java.net.DatagramSocket",
                "java.net.MulticastSocket", "java.net.URLConnection", "java.net.HttpURLConnection");
        some(Effect.NETWORK, "java.net.URL", "openConnection", "openStream", "getContent");
        some(Effect.NETWORK, "java.net.InetAddress", "getByName", "getAllByName", "getLocalHost");
        some(Effect.IO, "java.nio.file.Path", "toRealPath", "register");
        some(Effect.EXIT, "java.lang.System", "exit");
        some(Effect.EXIT, "java.lang.Runtime", "exit", "halt");
        some(Effect.PROCESS, "java.lang.Runtime", "exec");
        some(Effect.PROCESS, "java.lang.ProcessBuilder", "start");
        some(Effect.BLOCKS, "java.lang.Process", "waitFor");
        some(Effect.THREAD, "java.lang.Thread", "<init>", "start", "ofVirtual", "ofPlatform",
                "startVirtualThread", "setDaemon", "setPriority", "interrupt", "stop", "suspend", "resume");
        some(Effect.BLOCKS, "java.lang.Thread", "sleep", "join", "yield", "onSpinWait");
        some(Effect.BLOCKS, "java.lang.Object", "wait", "notify", "notifyAll");
        some(Effect.BLOCKS, "java.util.concurrent.Future", "get");
        some(Effect.BLOCKS, "java.util.concurrent.CompletableFuture", "join");
        some(Effect.THREAD, "java.util.concurrent.CompletableFuture", "runAsync", "supplyAsync");
        some(Effect.BLOCKS, "java.util.concurrent.CountDownLatch", "await");
        some(Effect.BLOCKS, "java.util.concurrent.CyclicBarrier", "await");
        some(Effect.BLOCKS, "java.util.concurrent.Semaphore", "acquire", "acquireUninterruptibly");
        some(Effect.BLOCKS, "java.util.concurrent.BlockingQueue", "put", "take");
        some(Effect.BLOCKS, "java.util.concurrent.Exchanger", "exchange");
        some(Effect.BLOCKS, "java.util.concurrent.Phaser", "arriveAndAwaitAdvance", "awaitAdvance",
                "awaitAdvanceInterruptibly");
        some(Effect.BLOCKS, "java.util.concurrent.locks.Lock", "lock", "lockInterruptibly", "tryLock");
        some(Effect.BLOCKS, "java.util.concurrent.locks.Condition", "await", "awaitNanos", "awaitUninterruptibly",
                "awaitUntil");
        some(Effect.THREAD, "java.util.concurrent.Executor", "execute");
        some(Effect.THREAD, "java.util.concurrent.ExecutorService", "submit", "invokeAll", "invokeAny");
        all(Effect.THREAD, "java.util.concurrent.Executors");
    }

    private static void all(Effect effect, String... types) {
        for (String t : types) {
            EFFECTS.computeIfAbsent(t, k -> new HashMap<>()).put("*", effect);
        }
    }

    private static void some(Effect effect, String type, String... methods) {
        Map<String, Effect> m = EFFECTS.computeIfAbsent(type, k -> new HashMap<>());
        for (String name : methods) {
            m.put(name, effect);
        }
    }

    private final Trees trees;
    private final Types types;
    private final Report report;
    /** Where an error was said, so a helper two steps reach is reported once. */
    private final Set<Tree> said = new HashSet<>();
    /** The top-level classes analyzed so far: each is desugared after its event, so none is read again. */
    private final Set<TypeElement> analyzed = new HashSet<>();
    /** The top-level class being read, whose trees are attributed and not yet desugared. */
    private TypeElement current;

    private StepCheck(Trees trees, Types types, Report report) {
        this.trees = trees;
        this.types = types;
        this.report = report;
    }

    /**
     * Listen to this compilation, to read each class once javac has analyzed it. Does nothing under a compiler whose
     * trees cannot be read: then nothing is refused.
     */
    static void install(javax.annotation.processing.ProcessingEnvironment env, Report report) {
        try {
            JavacTask.instance(env).addTaskListener(new StepCheck(Trees.instance(env), env.getTypeUtils(), report));
        } catch (IllegalArgumentException notJavac) {
            // Not javac: no trees to read.
        }
    }

    /** A class analyzed: check each step declared in it. */
    @Override
    public void finished(TaskEvent event) {
        if (event.getKind() != TaskEvent.Kind.ANALYZE || event.getTypeElement() == null) {
            return;
        }
        TreePath path = trees.getPath(event.getTypeElement());
        current = event.getTypeElement();
        try {
            if (path != null) {
                new Finder(path.getCompilationUnit()).scan(path, null);
            }
        } finally {
            analyzed.add(current);
            current = null;
        }
    }

    /** Finds the {@code step(name, body)} calls in one compilation unit. */
    private final class Finder extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree unit;

        Finder(CompilationUnitTree unit) {
            this.unit = unit;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
            Element m = trees.getElement(getCurrentPath());
            if (m instanceof ExecutableElement method && method.getSimpleName().contentEquals("step")
                    && method.getEnclosingElement() instanceof TypeElement owner
                    && owner.getQualifiedName().contentEquals(BUILDER) && call.getArguments().size() == 2) {
                String name = call.getArguments().get(0) instanceof LiteralTree lit && lit.getValue() instanceof String s
                        ? s : "?";
                ExpressionTree body = call.getArguments().get(1);
                TreePath at = new TreePath(getCurrentPath(), body);
                if (body instanceof LambdaExpressionTree) {
                    new Body(Subject.step(name), unit, false, new HashSet<>()).scan(at, null);
                } else if (body instanceof MemberReferenceTree ref) {
                    named(Subject.step(name), trees.getElement(at), ref, unit, new HashSet<>());
                }
            }
            return super.visitMethodInvocation(call, unused);
        }

        /** A view's {@code @Draw} method: read as a step is, with the frame's rules. Its shape is Declarations'. */
        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
            if (trees.getElement(getCurrentPath()) instanceof ExecutableElement method && drawn(method)
                    && method.getModifiers().contains(Modifier.STATIC) && tree.getBody() != null) {
                Set<Element> seen = new HashSet<>();
                seen.add(method);
                new Body(Subject.draw(Mirrors.where(method)), unit, false, seen)
                        .scan(getCurrentPath(), null);
            }
            return super.visitMethod(tree, unused);
        }
    }

    /** A step written as a method reference: a bound receiver is a capture; the method's body is read if it is here. */
    private void named(Subject step, Element target, MemberReferenceTree ref, CompilationUnitTree unit,
                       Set<Element> seen) {
        if (!(target instanceof ExecutableElement method)) {
            return;
        }
        boolean isStatic = method.getModifiers().contains(Modifier.STATIC);
        Element qualifier = trees.getElement(new TreePath(trees.getPath(unit, ref), ref.getQualifierExpression()));
        boolean unbound = qualifier != null && (qualifier.getKind().isClass() || qualifier.getKind().isInterface());
        if (!isStatic && !unbound) {
            error(step, "is a method reference on an object, " + ref.getQualifierExpression() + ", so it captures it",
                    "declare the step as a static method, or keep what it needs in " + step.state(),
                    ref, unit);
            return;
        }
        follow(step, method, seen);
    }

    /** Read {@code method}'s body as part of {@code step}'s, if its source is in this compilation. */
    private void follow(Subject step, ExecutableElement method, Set<Element> seen) {
        if (!seen.add(method)) {
            return;
        }
        TypeElement top = topLevel(method);
        if (top != current && analyzed.contains(top)) {
            return;     // desugared since it was analyzed: what is there now is not the source
        }
        TreePath path = trees.getPath(method);
        if (path == null || !(path.getLeaf() instanceof MethodTree tree) || tree.getBody() == null) {
            return;
        }
        boolean hasThis = !method.getModifiers().contains(Modifier.STATIC);
        new Body(step, path.getCompilationUnit(), hasThis, seen).scan(path, null);
    }

    /** Reads one body: the step's lambda, or a method it reaches. */
    private final class Body extends TreePathScanner<Void, Void> {
        private final Subject step;
        private final CompilationUnitTree unit;
        /** Whether {@code this} belongs to the body (a method reached on its receiver), so using it captures nothing. */
        private final boolean hasThis;
        private final Set<Element> seen;
        private final Set<Element> locals = new LinkedHashSet<>();

        Body(Subject step, CompilationUnitTree unit, boolean hasThis, Set<Element> seen) {
            this.step = step;
            this.unit = unit;
            this.hasThis = hasThis;
            this.seen = seen;
        }

        @Override
        public Void visitVariable(VariableTree v, Void unused) {
            Element e = trees.getElement(getCurrentPath());
            if (e != null) {
                locals.add(e);
            }
            return super.visitVariable(v, unused);
        }

        @Override
        public Void visitIdentifier(IdentifierTree id, Void unused) {
            if (id.getName().contentEquals("this") || id.getName().contentEquals("super")) {
                if (!hasThis) {
                    error(step, "uses " + id.getName() + ", so it captures the enclosing object",
                            "declare the step as a static method, or keep what it needs in " + step.state(),
                            id, unit);
                }
                return null;
            }
            Element e = trees.getElement(getCurrentPath());
            if (e == null) {
                return null;
            }
            switchOnKind(e, id);
            return null;
        }

        private void switchOnKind(Element e, Tree at) {
            ElementKind k = e.getKind();
            if (k == ElementKind.LOCAL_VARIABLE || k == ElementKind.PARAMETER || k == ElementKind.EXCEPTION_PARAMETER
                    || k == ElementKind.RESOURCE_VARIABLE || k == ElementKind.BINDING_VARIABLE) {
                if (!locals.contains(e)) {
                    error(step, "captures '" + e.getSimpleName() + "' from outside it",
                            "carry '" + e.getSimpleName() + "' in " + step.carry() + ", or keep it in "
                                    + step.state(), at, unit);
                }
            } else if (k == ElementKind.FIELD || k == ElementKind.ENUM_CONSTANT) {
                field(e, at, true);
            } else if (k == ElementKind.METHOD && !e.getModifiers().contains(Modifier.STATIC) && !hasThis
                    && at instanceof IdentifierTree) {
                error(step, "calls " + e.getSimpleName() + "() on the enclosing object, so it captures it",
                        "make " + e.getSimpleName() + " static, or keep what it needs in " + step.state(), at, unit);
            }
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree select, Void unused) {
            Element e = trees.getElement(getCurrentPath());
            if (e != null && (e.getKind() == ElementKind.FIELD || e.getKind() == ElementKind.ENUM_CONSTANT)) {
                field(e, select, false);
            }
            return super.visitMemberSelect(select, unused);
        }

        /** A field used: static and written, static and not final, volatile, a process stream, or this's. */
        private void field(Element e, Tree at, boolean bare) {
            boolean isStatic = e.getModifiers().contains(Modifier.STATIC);
            String name = owner(e) + "." + e.getSimpleName();
            if (e.getModifiers().contains(Modifier.VOLATILE)) {
                error(step, "uses the volatile field " + name + ", which is state another thread changes",
                        "keep what the step needs in " + step.state(), at, unit);
                return;
            }
            if (isStatic) {
                if (written(at)) {
                    error(step, "writes the static field " + name,
                            "keep this in " + step.state(), at, unit);
                } else if (!e.getModifiers().contains(Modifier.FINAL)) {
                    error(step, "reads the static field " + name + ", which is not final, so another thread may be "
                                    + "changing it",
                            "keep it in " + step.state() + ", or make it a constant", at, unit);
                } else if (owner(e).equals("java.lang.System") && Set.of("in", "out", "err")
                        .contains(e.getSimpleName().toString())) {
                    error(step, "uses " + name + ", the process's own stream, which does I/O", step.noEffects(),
                            at, unit);
                }
                return;
            }
            if (bare && !hasThis) {
                error(step, "uses the field " + e.getSimpleName() + " of the enclosing object, so it captures it",
                        "declare the step as a static method, or keep what it needs in " + step.state(), at, unit);
            }
        }

        /** Whether the tree at the current path is what an assignment, a compound assignment or ++/-- writes. */
        private boolean written(Tree at) {
            Tree parent = getCurrentPath().getParentPath().getLeaf();
            return (parent instanceof AssignmentTree a && a.getVariable() == at)
                    || (parent instanceof CompoundAssignmentTree c && c.getVariable() == at)
                    || (parent instanceof UnaryTree u && u.getExpression() == at
                    && (u.getKind() == Tree.Kind.PREFIX_INCREMENT || u.getKind() == Tree.Kind.POSTFIX_INCREMENT
                    || u.getKind() == Tree.Kind.PREFIX_DECREMENT || u.getKind() == Tree.Kind.POSTFIX_DECREMENT));
        }

        /**
         * A class declared inside the body (anonymous, local, a record) has its own {@code this}: its members are
         * read with it, and with the body's locals, which it may use as any code in the step may.
         */
        @Override
        public Void visitClass(ClassTree type, Void unused) {
            Body inner = new Body(step, unit, true, seen);
            inner.locals.addAll(locals);
            for (Tree member : type.getMembers()) {
                inner.scan(new TreePath(getCurrentPath(), member), null);
            }
            return null;
        }

        @Override
        public Void visitSynchronized(SynchronizedTree sync, Void unused) {
            error(step, "takes a lock (synchronized), which can stall its lane",
                    "a step owns its state and needs no lock; keep shared state in a ledger instead", sync, unit);
            return super.visitSynchronized(sync, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
            Element e = trees.getElement(getCurrentPath());
            if (e instanceof ExecutableElement m) {
                if (!effect(m, call) && !(step.drawn() && framed(m, call))) {
                    follow(step, m, seen);
                }
            }
            return super.visitMethodInvocation(call, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree create, Void unused) {
            Element e = trees.getElement(getCurrentPath());
            if (e instanceof ExecutableElement m) {
                if (!effect(m, create) && step.drawn()) {
                    error(step, "creates a new " + m.getEnclosingElement().getSimpleName() + ", which allocates in every"
                            + " frame", BUILT, create, unit);
                }
            }
            return super.visitNewClass(create, unused);
        }

        @Override
        public Void visitNewArray(NewArrayTree create, Void unused) {
            if (step.drawn()) {
                error(step, "builds an array, which allocates in every frame", BUILT, create, unit);
            }
            return super.visitNewArray(create, unused);
        }

        @Override
        public Void visitBinary(BinaryTree binary, Void unused) {
            if (step.drawn() && binary.getKind() == Tree.Kind.PLUS && string() && !constant(binary)) {
                error(step, "builds a string, which allocates in every frame", BUILT, binary, unit);
            }
            return super.visitBinary(binary, unused);
        }

        @Override
        public Void visitCompoundAssignment(CompoundAssignmentTree assign, Void unused) {
            if (step.drawn() && assign.getKind() == Tree.Kind.PLUS_ASSIGNMENT && string()) {
                error(step, "builds a string, which allocates in every frame", BUILT, assign, unit);
            }
            return super.visitCompoundAssignment(assign, unused);
        }

        /**
         * Refuse what a step may do and an asked half may not: log, touch a thread, reach the bus, or pass varargs, which
         * builds an array. @return whether it was refused, so that the method is not read on top of it.
         */
        private boolean framed(ExecutableElement m, MethodInvocationTree call) {
            if (!(m.getEnclosingElement() instanceof TypeElement owner)) {
                return false;
            }
            String what = owner.getSimpleName() + "." + m.getSimpleName();
            if (is(owner, LOGGERS)) {
                error(step, "calls " + what + ", which logs in every frame",
                        "log in the told half, where something changed, or count with a Probe", call, unit);
                return true;
            }
            if (is(owner, Set.of("java.lang.Thread"))) {
                error(step, "calls " + what + ". An asked half runs on the GUI thread and has no business with any",
                        "take what it needs from the view's state and the Frame", call, unit);
                return true;
            }
            if (inPackage(owner, "sibarum.atchung")) {
                error(step, "calls " + what + ", which reaches the bus. An asked half may not send; what it would report,"
                        + " the told half derives", "send from a @Subscribe method", call, unit);
                return true;
            }
            if (owner.getQualifiedName().contentEquals(Shareable.NODE)) {
                error(step, "calls " + what + ". A node in an asked half is a handle, held in the view's state so that"
                        + " it can be named, and what it shows changes only through the Drawn",
                        "set it with out.offset(...), or change the node in a @Subscribe method", call, unit);
                return true;
            }
            int params = m.getParameters().size();
            if (m.isVarArgs() && (call.getArguments().size() != params || trees.getTypeMirror(
                    new TreePath(getCurrentPath(), call.getArguments().get(params - 1))).getKind() != TypeKind.ARRAY)) {
                error(step, "calls " + what + " with varargs, which builds an array in every frame", BUILT, call, unit);
                return true;
            }
            return false;
        }

        /** Whether the expression at the current path is a String. */
        private boolean string() {
            TypeMirror type = trees.getTypeMirror(getCurrentPath());
            return type instanceof DeclaredType d && d.asElement() instanceof TypeElement t
                    && t.getQualifiedName().contentEquals("java.lang.String");
        }

        /** Whether {@code tree} is a compile-time constant, which javac folds and so allocates nothing. */
        private boolean constant(ExpressionTree tree) {
            if (tree instanceof LiteralTree) {
                return true;
            }
            if (tree instanceof ParenthesizedTree p) {
                return constant(p.getExpression());
            }
            if (tree instanceof BinaryTree b) {
                return constant(b.getLeftOperand()) && constant(b.getRightOperand());
            }
            if (tree instanceof IdentifierTree || tree instanceof MemberSelectTree) {
                Element e = trees.getElement(trees.getPath(unit, tree));
                return e instanceof VariableElement v && v.getConstantValue() != null;
            }
            return false;
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree ref, Void unused) {
            Element e = trees.getElement(getCurrentPath());
            if (e instanceof ExecutableElement m) {
                effect(m, ref);
            }
            return super.visitMemberReference(ref, unused);
        }

        /** Refuse {@code m} if it is a listed effect; @return whether it was. */
        private boolean effect(ExecutableElement m, Tree at) {
            if (!(m.getEnclosingElement() instanceof TypeElement owner)) {
                return false;
            }
            String method = m.getKind() == ElementKind.CONSTRUCTOR ? "<init>" : m.getSimpleName().toString();
            Effect found = effectOf(owner, method);
            if (found == null) {
                return false;
            }
            String what = m.getKind() == ElementKind.CONSTRUCTOR ? "new " + owner.getSimpleName()
                    : owner.getSimpleName() + "." + method;
            error(step, "calls " + what + ", which " + found.what, step.noEffects(), at, unit);
            return true;
        }
    }

    /** The effect {@code method} of {@code type} or of any of its supertypes is listed as, or null. */
    private Effect effectOf(TypeElement type, String method) {
        String name = type.getQualifiedName().toString();
        if (name.equals("java.io.File") && !PURE_FILE.contains(method)) {
            return Effect.IO;
        }
        Element pkg = type.getEnclosingElement();
        while (pkg != null && pkg.getKind() != ElementKind.PACKAGE) {
            pkg = pkg.getEnclosingElement();
        }
        if (pkg != null) {
            Effect whole = PACKAGES.get(pkg.toString());
            if (whole != null) {
                return whole;
            }
        }
        Map<String, Effect> listed = EFFECTS.get(name);
        if (listed != null) {
            Effect e = listed.get("*");
            if (e == null) {
                e = listed.get(method);
            }
            if (e != null) {
                return e;
            }
        }
        if (method.equals("<init>")) {
            return null;    // a constructor is its own type's, never a supertype's
        }
        for (TypeMirror sup : types.directSupertypes(type.asType())) {
            if (sup instanceof DeclaredType d && d.asElement() instanceof TypeElement t) {
                Effect e = effectOf(t, method);
                if (e != null) {
                    return e;
                }
            }
        }
        return null;
    }

    /** The fix for anything an asked half allocates: told builds, asked chooses. */
    private static final String BUILT = "build it in a @Subscribe method and keep it in the view's state; an asked half"
            + " only chooses among what the told half built, and sets numbers on it";

    /** The loggers an asked half may not call, the house's own first: in every frame a log line is a cost, enabled or not. */
    private static final Set<String> LOGGERS = Set.of("sibarum.probe.Log", "java.util.logging.Logger",
            "java.lang.System.Logger", "org.slf4j.Logger", "org.apache.logging.log4j.Logger");

    /** Whether {@code type}, or any of its supertypes, is one of {@code names}. */
    private boolean is(TypeElement type, Set<String> names) {
        if (names.contains(type.getQualifiedName().toString())) {
            return true;
        }
        for (TypeMirror sup : types.directSupertypes(type.asType())) {
            if (sup instanceof DeclaredType d && d.asElement() instanceof TypeElement t && is(t, names)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code type} is declared in {@code pkg} or a package under it. */
    private static boolean inPackage(TypeElement type, String pkg) {
        Element at = type;
        while (at != null && at.getKind() != ElementKind.PACKAGE) {
            at = at.getEnclosingElement();
        }
        String name = at == null ? "" : at.toString();
        return name.equals(pkg) || name.startsWith(pkg + ".");
    }

    /** Whether {@code method} is marked {@code @Draw}. */
    private static boolean drawn(ExecutableElement method) {
        for (var a : method.getAnnotationMirrors()) {
            if (a.getAnnotationType().asElement() instanceof TypeElement t
                    && t.getQualifiedName().contentEquals(dev.vexelray.framework.api.Draw.class.getCanonicalName())) {
                return true;
            }
        }
        return false;
    }

    /** The top-level class {@code e} is declared in, or null for one outside any class. */
    private static TypeElement topLevel(Element e) {
        TypeElement top = null;
        for (Element at = e; at != null && at.getKind() != ElementKind.PACKAGE; at = at.getEnclosingElement()) {
            if (at instanceof TypeElement t) {
                top = t;
            }
        }
        return top;
    }

    private static String owner(Element field) {
        return field.getEnclosingElement() instanceof TypeElement t ? t.getQualifiedName().toString()
                : String.valueOf(field.getEnclosingElement());
    }

    private void error(Subject step, String what, String fix, Tree at, CompilationUnitTree unit) {
        if (!said.add(at)) {
            return;
        }
        report.at(trees, Diagnostic.Kind.ERROR, step.name() + " " + what + ". " + step.rule() + ": " + fix + ".", at,
                unit);
    }

    /**
     * What a body is read as, and so how its errors are worded: a ledger's step, or a view's asked half, which is held
     * to everything a step is and to the frame's rules besides.
     *
     * @param drawn whether the body runs in every frame, so that allocating, logging, a thread or the bus is refused too
     */
    private record Subject(String name, String rule, String state, String carry, String noEffects, boolean drawn) {

        static Subject step(String name) {
            return new Subject("step '" + name + "'",
                    "A step sees only its state, its arguments and its Out (docs/plans/chains.md, design F)",
                    "the ledger's state", "the step's arguments",
                    "a step makes no effects and never waits: send a message to a @Job that does it, and take its "
                            + "reply in a later step", false);
        }

        static Subject draw(String where) {
            return new Subject("@Draw " + where,
                    "An asked half reads only its state, the Frame and its Drawn, and allocates nothing, since it runs"
                            + " in every frame (vexelray-gui docs/plans/frame-contract.md, B)",
                    "the view's state", "the view's state",
                    "an asked half makes no effects and never waits: do it in a @Subscribe method or a @Job, and keep "
                            + "what it found in the view's state", true);
        }
    }
}
