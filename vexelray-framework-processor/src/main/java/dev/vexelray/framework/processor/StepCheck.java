package dev.vexelray.framework.processor;

import com.sun.source.tree.AssignmentTree;
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
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
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
 */
final class StepCheck {

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
    private final Set<CompilationUnitTree> read = new HashSet<>();
    /** Where an error was said, so a helper two steps reach is reported once. */
    private final Set<Tree> said = new HashSet<>();

    private StepCheck(Trees trees, Types types, Report report) {
        this.trees = trees;
        this.types = types;
        this.report = report;
    }

    /** The check, or null under a compiler whose trees cannot be read: then nothing is refused. */
    static StepCheck of(javax.annotation.processing.ProcessingEnvironment env, Report report) {
        try {
            return new StepCheck(Trees.instance(env), env.getTypeUtils(), report);
        } catch (IllegalArgumentException notJavac) {
            return null;
        }
    }

    /** Read every compilation unit among {@code roots} once, and check each step declared in it. */
    void scan(Set<? extends Element> roots) {
        for (Element root : roots) {
            TreePath path = trees.getPath(root);
            if (path != null && read.add(path.getCompilationUnit())) {
                new Finder(path.getCompilationUnit()).scan(path.getCompilationUnit(), null);
            }
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
                    new Body(name, unit, false, new HashSet<>()).scan(at, null);
                } else if (body instanceof MemberReferenceTree ref) {
                    named(name, trees.getElement(at), ref, unit, new HashSet<>());
                }
            }
            return super.visitMethodInvocation(call, unused);
        }
    }

    /** A step written as a method reference: a bound receiver is a capture; the method's body is read if it is here. */
    private void named(String step, Element target, MemberReferenceTree ref, CompilationUnitTree unit,
                       Set<Element> seen) {
        if (!(target instanceof ExecutableElement method)) {
            return;
        }
        boolean isStatic = method.getModifiers().contains(Modifier.STATIC);
        Element qualifier = trees.getElement(new TreePath(trees.getPath(unit, ref), ref.getQualifierExpression()));
        boolean unbound = qualifier != null && (qualifier.getKind().isClass() || qualifier.getKind().isInterface());
        if (!isStatic && !unbound) {
            error(step, "is a method reference on an object, " + ref.getQualifierExpression() + ", so it captures it",
                    "declare the step as a static method, or keep what it needs in the ledger's state",
                    ref, unit);
            return;
        }
        follow(step, method, seen);
    }

    /** Read {@code method}'s body as part of {@code step}'s, if its source is in this compilation. */
    private void follow(String step, ExecutableElement method, Set<Element> seen) {
        if (!seen.add(method)) {
            return;
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
        private final String step;
        private final CompilationUnitTree unit;
        /** Whether {@code this} belongs to the body (a method reached on its receiver), so using it captures nothing. */
        private final boolean hasThis;
        private final Set<Element> seen;
        private final Set<Element> locals = new LinkedHashSet<>();

        Body(String step, CompilationUnitTree unit, boolean hasThis, Set<Element> seen) {
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
                            "declare the step as a static method, or keep what it needs in the ledger's state",
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
                            "carry '" + e.getSimpleName() + "' in the step's arguments, or keep it in the ledger's "
                                    + "state", at, unit);
                }
            } else if (k == ElementKind.FIELD || k == ElementKind.ENUM_CONSTANT) {
                field(e, at, true);
            } else if (k == ElementKind.METHOD && !e.getModifiers().contains(Modifier.STATIC) && !hasThis
                    && at instanceof IdentifierTree) {
                error(step, "calls " + e.getSimpleName() + "() on the enclosing object, so it captures it",
                        "make " + e.getSimpleName() + " static, or keep what it needs in the ledger's state", at, unit);
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
                        "keep what the step needs in the ledger's state", at, unit);
                return;
            }
            if (isStatic) {
                if (written(at)) {
                    error(step, "writes the static field " + name,
                            "a step changes only its ledger's state; keep this there", at, unit);
                } else if (!e.getModifiers().contains(Modifier.FINAL)) {
                    error(step, "reads the static field " + name + ", which is not final, so another thread may be "
                                    + "changing it",
                            "keep it in the ledger's state, or make it a constant", at, unit);
                } else if (owner(e).equals("java.lang.System") && Set.of("in", "out", "err")
                        .contains(e.getSimpleName().toString())) {
                    error(step, "uses " + name + ", the process's own stream, which does I/O",
                            "a step makes no effects: send a message to a @Job that does it, or log outside the step",
                            at, unit);
                }
                return;
            }
            if (bare && !hasThis) {
                error(step, "uses the field " + e.getSimpleName() + " of the enclosing object, so it captures it",
                        "declare the step as a static method, or keep what it needs in the ledger's state", at, unit);
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
                if (!effect(m, call)) {
                    follow(step, m, seen);
                }
            }
            return super.visitMethodInvocation(call, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree create, Void unused) {
            Element e = trees.getElement(getCurrentPath());
            if (e instanceof ExecutableElement m) {
                effect(m, create);
            }
            return super.visitNewClass(create, unused);
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
            error(step, "calls " + what + ", which " + found.what,
                    "a step makes no effects and never waits: send a message to a @Job that does it, and take its "
                            + "reply in a later step", at, unit);
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

    private static String owner(Element field) {
        return field.getEnclosingElement() instanceof TypeElement t ? t.getQualifiedName().toString()
                : String.valueOf(field.getEnclosingElement());
    }

    private void error(String step, String what, String fix, Tree at, CompilationUnitTree unit) {
        if (!said.add(at)) {
            return;
        }
        report.at(trees, Diagnostic.Kind.ERROR, "step '" + step + "' " + what + ". A step sees only its state, its "
                + "arguments and its Out (docs/plans/chains.md, design F): " + fix + ".", at, unit);
    }
}
