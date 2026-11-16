package dev.vexelray.framework.processor;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionStatementTree;
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
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the body of every step declared on an atchung {@code Ledger.Builder}, and refuses what is certainly wrong in
 * one (vexelray-framework's {@code docs/plans/chains.md}, rule 4 and design F); and, with the same reader and the same
 * tables, a view's asked half, every delivery and every display function, each held to its own rules (below).
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
 * <p><b>A delivery is read for what blocks, and only that</b> ({@code docs/threading.md} T5.8: a component's delivery
 * never blocks, never sleeps and never waits for a reply). A delivery is a {@code @Subscribe} method of a
 * {@code @Component} or a {@code @View}, and a function a view hands {@code Gui} ({@code hear}, {@code mailbox},
 * {@code batch}), which the GUI thread runs as one. It owns its part's state, so nothing it captures is refused; what is
 * refused is an effect that certainly makes its thread wait ({@link Effect#blocking}): disk, the network, a sleep, a
 * join, a wait, a future's {@code get}, a latch, a barrier, a queue's {@code take}. What waits only while another thread
 * holds something (a lock, {@code synchronized}, a semaphore, a bounded queue's {@code put}) is let through, since no
 * reading of the source says whether it is ever contended; so is what starts a thread or a process without waiting on
 * it. The error names the delivery and the fix, a {@code @Job}, a {@code @Source} or a {@code @Destination}
 * ({@code docs/components.md} sections 10 to 12). Three things more are let through, each because the source does not
 * say what runs:
 * <ul>
 *   <li><b>A function in the body</b>, a lambda, a method reference or a class declared there: it may run now, or be
 *       kept and run later on another thread ({@code new Thread(() -> read(file))} blocks nothing).</li>
 *   <li><b>A method an override may replace</b>: a helper is followed, through as many calls as its source is here,
 *       only where the call certainly runs the body read: a static, private or final method, a constructor, or a method
 *       of a final class, a record or an anonymous class. An interface's method, or a non-final class's, is not.</li>
 *   <li><b>Code it cannot read</b>, as for a step: a library, a class analyzed earlier.</li>
 * </ul>
 *
 * <p><b>A display function is read for effects, sends and shared state</b> ({@code docs/threading.md} T7.2: a view may
 * give a widget display functions, which the processor reads, refusing I/O, blocking calls and captures of mutable
 * state). A display function is a lambda or a method reference handed to the widget library
 * ({@code dev.vexelray.gui.widget}, not its {@code spi}), or one whose own type is a widget library interface such as
 * {@code Face}: vexelray-gui's walk holds that every function a widget takes is presentation, run on the GUI thread
 * whenever the widget asks. Every listed effect is refused, blocking or not; so is a send on the bus
 * ({@code Sender.send}, an {@code Out}'s, {@code Atchung.publish}); and so is state that is no part's: a static field
 * written or read when not final, a volatile field, the process's streams. The view's own fields, and the item's, are
 * its to read, as an asked half reads its part's state (T7.1); a helper is followed as a delivery's is. A function held
 * in a variable and handed on later, or handed to another library (a plot, a codec), is not read.
 *
 * <p><b>What a step sends is declared</b> ({@code docs/threading.md} T4.5). A part's other sends are its
 * constructor's {@code Sender}s, which the wiring reads; a step sends through its {@code Out}, which no constructor
 * shows, so the class that declares the step lists what it sends in {@code @Publishes}, and that list is what the
 * message graph and the Inspector's {@code BUS_GRAPH} carry. Each {@code out.send(topic, message)} a step reaches,
 * through its helpers as above, is held to the list: a payload no {@code @Publishes} lists is a compile error at the
 * send, naming the step, the payload and the class to list it on. A channel is a payload type, so the payload is the topic's type argument, the
 * {@code X} of the {@code Topic<X>} handed to {@code send}. The list is the union of {@code @Publishes} on the class
 * that declares the step and on its methods, and on each class enclosing it, the same elements the graph draws as that
 * class's sends; a superclass's is not counted, since the graph draws it as the superclass's.
 *
 * <ul>
 *   <li><b>Only a part's steps are held to it.</b> A ledger runs on an {@code Inbox}, which only a component is handed,
 *       so a step declared in a class that is no {@code @Component} and lists nothing in {@code @Publishes}, around it
 *       or within it, is a test's or a library's, whose sends no application graph draws; it is not checked.</li>
 *   <li><b>A payload it cannot name is let through.</b> A send through a {@code Topic<E>} of a type variable (a
 *       generic helper's {@code tell(out, topic, message)}), a wildcard or a raw {@code Topic} has no one channel the
 *       check could hold to the list. Refusing it would refuse code that may well be right, so it is not refused; the send the
 *       helper's caller makes is not traced through it either.</li>
 * </ul>
 *
 * <p><b>The converse is a warning, and only where it is certain.</b> A payload a component lists that nothing it does
 * sends draws a send in the graph that never happens. That misleads a reader and harms no running program, so it is
 * never an error, and a warning that could be wrong would teach authors to ignore the warning, so it is said only when
 * the component has no way to send but its steps, every one of which was read to the end. It is said when the
 * {@code @Component} lists the payload on itself (a method's {@code @Publishes} is left alone), declares at least one
 * step, and none of its steps sends it, and only if all of these hold in its top-level class:
 * <ul>
 *   <li>its constructors take nothing but an {@code Inbox} and {@code Sender}s (a payload it takes a sender of is sent
 *       by that), so no {@code Timer} or provided value sends on its behalf;</li>
 *   <li>its {@code Inbox} goes nowhere but into {@code Ledger.Builder.build}, so no sender is made of it and no other
 *       class builds a ledger on it;</li>
 *   <li>a {@code Ledger.Builder} is used only as the receiver of its own methods or kept in a local, never passed,
 *       returned, held in a field or taken as a parameter, so no step of the ledger is declared out of sight;</li>
 *   <li>every step's body is a lambda or a method reference whose source is read, no send's payload is unnamed (above),
 *       and an {@code Out} is never handed to code that cannot be read (a library, an interface, a class analyzed
 *       earlier).</li>
 * </ul>
 * Anything less and the list may be right for a reason the check cannot see, so nothing is said.
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
    private static final String OUT = "sibarum.atchung.Out";
    private static final String INBOX = "sibarum.atchung.Inbox";
    private static final String TOPIC = "sibarum.atchung.Topic";

    /**
     * What an effect is, said in the error so the author sees why it was refused, and whether it certainly makes its
     * thread wait, which is all a delivery is held to (T5.8). A step, an asked half and a display function refuse every
     * kind; a delivery refuses only the blocking ones.
     */
    private enum Effect {
        IO("does I/O", true),
        NETWORK("goes to the network", true),
        /** A call whose purpose is to wait for another thread or the clock: a sleep, a join, a future, a latch. */
        BLOCKS("blocks its thread", true),
        /**
         * A lock, a semaphore's permit, a bounded queue's room: it waits only while another thread holds what it asks
         * for, which no reading of the source can tell, so a delivery is not refused for it.
         */
        CONTENDS("waits whenever another thread holds what it asks for", false),
        /** A notify, a yield: it touches another thread's schedule and never waits itself. */
        SIGNALS("coordinates with another thread", false),
        THREAD("starts work on another thread", false),
        EXIT("ends the process", false),
        PROCESS("starts a process", false);

        final String what;
        final boolean blocking;

        Effect(String what, boolean blocking) {
            this.what = what;
            this.blocking = blocking;
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
            "getParentFile", "isAbsolute", "getAbsolutePath", "getAbsoluteFile", "toPath", "toString", "equals",
            "hashCode", "compareTo", "toURI");

    /** The bus's sending methods, by type: what a display function may not call, since it sends nothing. */
    private static final Map<String, Set<String>> SENDS = Map.of(
            "sibarum.atchung.Sender", Set.of("send"),
            OUT, Set.of("send", "result", "resume"),
            "sibarum.atchung.Atchung", Set.of("publish"));

    /** The type whose methods run what they are handed on the GUI thread, as the caller or in the widget tick. */
    private static final String GUI = "dev.vexelray.gui.core.Gui";

    /** The widget library, every function handed to which is a display function; and its seam, which is not. */
    private static final String WIDGETS = "dev.vexelray.gui.widget";
    private static final String WIDGET_SEAMS = "dev.vexelray.gui.widget.spi";

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
        some(Effect.BLOCKS, "java.lang.Thread", "sleep", "join");
        some(Effect.SIGNALS, "java.lang.Thread", "yield", "onSpinWait");
        some(Effect.BLOCKS, "java.lang.Object", "wait");
        some(Effect.SIGNALS, "java.lang.Object", "notify", "notifyAll");
        some(Effect.BLOCKS, "java.util.concurrent.Future", "get");
        some(Effect.BLOCKS, "java.util.concurrent.CompletableFuture", "join");
        some(Effect.THREAD, "java.util.concurrent.CompletableFuture", "runAsync", "supplyAsync");
        some(Effect.BLOCKS, "java.util.concurrent.CountDownLatch", "await");
        some(Effect.BLOCKS, "java.util.concurrent.CyclicBarrier", "await");
        some(Effect.CONTENDS, "java.util.concurrent.Semaphore", "acquire", "acquireUninterruptibly");
        some(Effect.BLOCKS, "java.util.concurrent.BlockingQueue", "take");
        some(Effect.CONTENDS, "java.util.concurrent.BlockingQueue", "put");
        some(Effect.BLOCKS, "java.util.concurrent.Exchanger", "exchange");
        some(Effect.BLOCKS, "java.util.concurrent.Phaser", "arriveAndAwaitAdvance", "awaitAdvance",
                "awaitAdvanceInterruptibly");
        some(Effect.CONTENDS, "java.util.concurrent.locks.Lock", "lock", "lockInterruptibly", "tryLock");
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
    private final Mirrors mirrors;
    private final Report report;
    /** Where an error was said, so a helper two steps reach is reported once. */
    private final Set<Tree> said = new HashSet<>();
    /** The top-level classes analyzed so far: each is desugared after its event, so none is read again. */
    private final Set<TypeElement> analyzed = new HashSet<>();
    /** The top-level class being read, whose trees are attributed and not yet desugared. */
    private TypeElement current;
    /** What the steps of the top-level class being read send, for the converse. */
    private Sent sent;
    /**
     * The methods the deliveries of the top-level class being read have reached. Shared between them, since what a
     * helper does is reported where it does it, once, whichever delivery got there first.
     */
    private Set<Element> delivered;

    private StepCheck(Trees trees, Types types, Mirrors mirrors, Report report) {
        this.trees = trees;
        this.types = types;
        this.mirrors = mirrors;
        this.report = report;
    }

    /**
     * Listen to this compilation, to read each class once javac has analyzed it. Does nothing under a compiler whose
     * trees cannot be read: then nothing is refused.
     */
    static void install(javax.annotation.processing.ProcessingEnvironment env, Report report) {
        try {
            JavacTask.instance(env).addTaskListener(new StepCheck(Trees.instance(env), env.getTypeUtils(),
                    new Mirrors(env.getElementUtils()), report));
        } catch (IllegalArgumentException notJavac) {
            // Not javac: no trees to read.
        }
    }

    /** A class analyzed: check each step declared in it, then what its components list and never send. */
    @Override
    public void finished(TaskEvent event) {
        if (event.getKind() != TaskEvent.Kind.ANALYZE || event.getTypeElement() == null) {
            return;
        }
        TreePath path = trees.getPath(event.getTypeElement());
        current = event.getTypeElement();
        List<TypeElement> listing = new ArrayList<>();
        listing(current, listing);
        sent = new Sent(!listing.isEmpty());
        delivered = new HashSet<>();
        try {
            if (path != null) {
                new Finder(path.getCompilationUnit()).scan(path, null);
                if (sent.certain) {
                    for (TypeElement component : listing) {
                        unsent(component, path.getCompilationUnit());
                    }
                }
            }
        } finally {
            analyzed.add(current);
            current = null;
            sent = null;
            delivered = null;
        }
    }

    // ------------------------------------------------------------------ T4.5: what a step sends is declared

    /**
     * What the steps of one top-level class send, and whether that is all they can: the converse is said only while
     * {@link #certain} holds, and is not looked for at all ({@link #wanted} false) when no component there lists a
     * payload.
     */
    private static final class Sent {
        final boolean wanted;
        boolean certain = true;
        /** Each class around a step, to the payloads (by channel name) the step sends. */
        final Map<TypeElement, Set<String>> by = new HashMap<>();

        Sent(boolean wanted) {
            this.wanted = wanted;
        }

        /** Something a step may send through, or a step may be declared by, that the check cannot read. */
        void unsure() {
            certain = false;
        }
    }

    /**
     * What a step is held to: the payloads listed on the classes around its declaration, which class to tell the
     * author to list a payload on, and whether it is held at all (a part's step), with the classes its sends are
     * recorded against for the converse. A {@code @Draw} body sends nothing and has {@link #NONE}.
     */
    private record Sends(boolean checked, Set<String> declared, TypeElement listOn, List<TypeElement> around) {
        static final Sends NONE = new Sends(false, Set.of(), null, List.of());
    }

    /** What a step declared at {@code at} is held to: see {@link Sends}. */
    private Sends sends(TreePath at) {
        List<TypeElement> around = new ArrayList<>();
        for (TreePath p = at; p != null; p = p.getParentPath()) {
            if (p.getLeaf() instanceof ClassTree && trees.getElement(p) instanceof TypeElement t) {
                around.add(t);
            }
        }
        Set<String> declared = new HashSet<>();
        TypeElement component = null;
        TypeElement listing = null;
        for (TypeElement t : around) {
            boolean lists = listed(t, declared);
            for (Element member : t.getEnclosedElements()) {
                lists |= member.getKind() == ElementKind.METHOD && listed(member, declared);
            }
            if (component == null && mirrors.has(t, dev.vexelray.framework.api.Component.class)) {
                component = t;
            }
            if (listing == null && lists) {
                listing = t;
            }
        }
        TypeElement listOn = component != null ? component : listing;
        if (listOn == null) {
            return Sends.NONE;
        }
        return new Sends(true, declared, listOn, around);
    }

    /** Add what {@code @Publishes} on {@code e} lists to {@code into}; @return whether it lists anything. */
    private boolean listed(Element e, Set<String> into) {
        var publishes = mirrors.find(e, dev.vexelray.framework.api.Publishes.class);
        if (publishes == null) {
            return false;
        }
        List<TypeMirror> listed = mirrors.types(publishes, "value");
        for (TypeMirror payload : listed) {
            if (Mirrors.element(payload) != null) {
                into.add(Topics.name(payload));
            }
        }
        return !listed.isEmpty();
    }

    /** The {@code @Component}s in {@code type} and the types it declares that list payloads on themselves. */
    private void listing(TypeElement type, List<TypeElement> into) {
        if (mirrors.has(type, dev.vexelray.framework.api.Component.class)
                && mirrors.has(type, dev.vexelray.framework.api.Publishes.class)) {
            into.add(type);
        }
        for (Element member : type.getEnclosedElements()) {
            if (member instanceof TypeElement nested) {
                listing(nested, into);
            }
        }
    }

    /**
     * Warn of each payload {@code component} lists on itself that none of its steps sends, when its steps are certainly
     * its only way to send (the class javadoc's list). Called only while every step of its top-level class was read.
     */
    private void unsent(TypeElement component, CompilationUnitTree unit) {
        Set<String> sends = sent.by.get(component);
        if (sends == null) {
            return;     // no step of its own: it sends some other way, or not yet
        }
        Set<String> senders = new HashSet<>();
        for (Element member : component.getEnclosedElements()) {
            if (member.getKind() != ElementKind.CONSTRUCTOR) {
                continue;
            }
            for (VariableElement p : ((ExecutableElement) member).getParameters()) {
                TypeMirror type = p.asType();
                if (isClass(type, Channels.SENDER) && type instanceof DeclaredType d && d.getTypeArguments().size() == 1
                        && Mirrors.element(d.getTypeArguments().get(0)) != null) {
                    senders.add(Topics.name(d.getTypeArguments().get(0)));
                } else if (!isClass(type, INBOX)) {
                    return;     // a Timer, or a value provided: something else may send for it
                }
            }
        }
        var publishes = mirrors.find(component, dev.vexelray.framework.api.Publishes.class);
        Tree at = trees.getTree(component, publishes);
        for (TypeMirror payload : mirrors.types(publishes, "value")) {
            TypeElement type = Mirrors.element(payload);
            if (type == null || sends.contains(Topics.name(payload)) || senders.contains(Topics.name(payload))) {
                continue;
            }
            String name = Mirrors.simple(type);
            String message = "T4.5: @Publishes on " + Mirrors.simple(component) + " lists " + name + ", and no step of "
                    + Mirrors.simple(component) + " sends it, and nothing else of it can, so the graph draws a send"
                    + " that never happens: take " + name + " out of @Publishes, or send it from the step that should";
            if (at != null) {
                report.at(trees, Diagnostic.Kind.WARNING, message, at, unit);
            } else {
                report.printMessage(Diagnostic.Kind.WARNING, message, component);
            }
        }
    }

    /** Whether {@code type} is the class named {@code name}, whatever its type arguments. */
    private static boolean isClass(TypeMirror type, String name) {
        return type instanceof DeclaredType d && d.asElement() instanceof TypeElement t
                && t.getQualifiedName().contentEquals(name);
    }

    /** Whether {@code m} is {@code Out.send(topic, message)}. */
    private static boolean isSend(ExecutableElement m) {
        return m.getSimpleName().contentEquals("send") && m.getParameters().size() == 2
                && m.getEnclosingElement() instanceof TypeElement owner && owner.getQualifiedName().contentEquals(OUT);
    }

    /** Whether {@code m} is one of {@code Out}'s own methods, or takes an {@code Out}. */
    private static boolean takesOut(ExecutableElement m) {
        if (m.getEnclosingElement() instanceof TypeElement owner && owner.getQualifiedName().contentEquals(OUT)) {
            return true;
        }
        for (VariableElement p : m.getParameters()) {
            if (isClass(p.asType(), OUT)) {
                return true;
            }
        }
        return false;
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
                Subject step = Subject.step(name, sends(getCurrentPath()));
                for (TypeElement t : step.sends().around()) {
                    sent.by.computeIfAbsent(t, k -> new HashSet<>());
                }
                if (body instanceof LambdaExpressionTree) {
                    new Body(step, unit, false, new HashSet<>()).scan(at, null);
                } else if (!(body instanceof MemberReferenceTree ref
                        && named(step, trees.getElement(at), ref, unit, new HashSet<>()))) {
                    sent.unsure();      // a body held in a variable, or one whose source is not here
                }
            }
            return super.visitMethodInvocation(call, unused);
        }

        /** A builder taken as a parameter came from somewhere else, where more steps may be declared on it. */
        @Override
        public Void visitVariable(VariableTree v, Void unused) {
            if (sent.wanted && trees.getElement(getCurrentPath()) instanceof VariableElement e
                    && (e.getKind() == ElementKind.PARAMETER || e.getKind() == ElementKind.FIELD)
                    && isClass(e.asType(), BUILDER)) {
                sent.unsure();
            }
            return super.visitVariable(v, unused);
        }

        /** Every expression, while the converse is still sure: whether a builder or an inbox goes out of sight. */
        @Override
        public Void scan(Tree tree, Void unused) {
            if (sent.wanted && sent.certain && tree instanceof ExpressionTree && getCurrentPath() != null) {
                escapes(new TreePath(getCurrentPath(), tree));
            }
            return super.scan(tree, unused);
        }

        /**
         * A {@code Ledger.Builder} used other than as the receiver of its own methods, a discarded result or a
         * local's value, or an {@code Inbox} used other than as {@code build}'s argument: either may carry steps or
         * sends to code this does not read, so the converse is not said.
         */
        private void escapes(TreePath at) {
            if (trees.getElement(at) instanceof TypeElement) {
                return;     // a type's name, as in a declaration or an import
            }
            TypeMirror type = trees.getTypeMirror(at);
            Tree leaf = at.getLeaf();
            Tree parent = at.getParentPath().getLeaf();
            if (isClass(type, BUILDER)) {
                boolean kept = (parent instanceof MemberSelectTree s && s.getExpression() == leaf)
                        || parent instanceof ExpressionStatementTree
                        || (parent instanceof VariableTree
                        && trees.getElement(at.getParentPath()).getKind() == ElementKind.LOCAL_VARIABLE);
                if (!kept) {
                    sent.unsure();
                }
            } else if (isClass(type, INBOX)) {
                boolean built = parent instanceof MethodInvocationTree call && call.getArguments().contains(leaf)
                        && trees.getElement(at.getParentPath()) instanceof ExecutableElement m
                        && m.getSimpleName().contentEquals("build")
                        && m.getEnclosingElement() instanceof TypeElement owner
                        && owner.getQualifiedName().contentEquals(BUILDER);
                if (!built) {
                    sent.unsure();
                }
            }
        }

        /**
         * A view's {@code @Draw} method: read as a step is, with the frame's rules. Its shape is Declarations'. And a
         * {@code @Subscribe} method of a component or a view: read for what blocks (T5.8).
         */
        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
            if (trees.getElement(getCurrentPath()) instanceof ExecutableElement method && tree.getBody() != null) {
                if (drawn(method) && method.getModifiers().contains(Modifier.STATIC)) {
                    Set<Element> seen = new HashSet<>();
                    seen.add(method);
                    new Body(Subject.draw(Mirrors.where(method)), unit, false, seen)
                            .scan(getCurrentPath(), null);
                } else if (mirrors.has(method, dev.vexelray.framework.api.Subscribe.class)) {
                    Element owner = method.getEnclosingElement();
                    boolean view = mirrors.has(owner, dev.vexelray.framework.api.View.class);
                    if (view || mirrors.has(owner, dev.vexelray.framework.api.Component.class)) {
                        delivered.add(method);
                        new Body(Subject.delivery("@Subscribe " + Mirrors.where(method), view), unit, true, delivered)
                                .scan(getCurrentPath(), null);
                    }
                }
            }
            return super.visitMethod(tree, unused);
        }

        /** A lambda handed to the GUI: a display function, or a view's handler (see {@link #handed}). */
        @Override
        public Void visitLambdaExpression(LambdaExpressionTree lambda, Void unused) {
            Subject handed = handed(getCurrentPath());
            if (handed != null) {
                new Body(handed, unit, false, handed.holds().seen(delivered))
                        .scan(new TreePath(getCurrentPath(), lambda.getBody()), null);
            }
            return super.visitLambdaExpression(lambda, unused);
        }

        /** A method reference handed to the GUI: the method it names is read as a lambda's body would be. */
        @Override
        public Void visitMemberReference(MemberReferenceTree ref, Void unused) {
            Subject handed = handed(getCurrentPath());
            if (handed != null && trees.getElement(getCurrentPath()) instanceof ExecutableElement target) {
                Body body = new Body(handed, unit, false, handed.holds().seen(delivered));
                if (!body.effect(target, ref)) {
                    follow(handed, target, body.seen);
                }
            }
            return super.visitMemberReference(ref, unused);
        }

        /**
         * What a function at {@code at} is read as, or null for one this does not read.
         *
         * <ul>
         *   <li><b>A display function</b> (T7.2): handed to a method or constructor of the widget library
         *       ({@code dev.vexelray.gui.widget}, not its {@code spi}), or whose own type is one of the widget library's functional
         *       interfaces ({@code Face}, a {@code Tabs.HeaderSkin}), wherever it is handed. Every function a widget
         *       an application can reach takes is presentation: vexelray-gui's walk holds that, so the check needs no
         *       list of them.</li>
         *   <li><b>A view's handler</b>: handed to {@code Gui}, which takes a function only where it runs it on the GUI
         *       thread as the caller, {@code hear}, {@code mailbox} and {@code batch} (vexelray-gui's
         *       {@code GuiSurfaceTest}). It is a delivery as a view's {@code @Subscribe} method is, held to T5.8.</li>
         * </ul>
         *
         * A function held in a variable and handed on later, or handed to any other library, is not read: where and
         * when it runs is not something the source says.
         */
        private Subject handed(TreePath at) {
            TreePath up = at.getParentPath();
            Tree parent = up.getLeaf();
            List<? extends ExpressionTree> arguments = parent instanceof MethodInvocationTree call ? call.getArguments()
                    : parent instanceof NewClassTree create ? create.getArguments() : List.of();
            if (arguments.contains(at.getLeaf()) && trees.getElement(up) instanceof ExecutableElement m
                    && m.getEnclosingElement() instanceof TypeElement owner) {
                String receiver = m.getKind() == ElementKind.CONSTRUCTOR ? "new " + Mirrors.simple(owner)
                        : Mirrors.simple(owner) + "." + Mirrors.simple(m);
                if (owner.getQualifiedName().contentEquals(GUI)) {
                    return Subject.delivery("the handler handed to " + receiver + " in " + where(at), true);
                }
                if (widget(owner)) {
                    return Subject.display("the display function handed to " + receiver + " in " + where(at));
                }
            }
            if (trees.getTypeMirror(at) instanceof DeclaredType d && d.asElement() instanceof TypeElement function
                    && widget(function)) {
                return Subject.display("the " + Mirrors.simple(function) + " in " + where(at));
            }
            return null;
        }

        /** The method, constructor or class whose code {@code at} is in, as an author would look for it. */
        private String where(TreePath at) {
            for (TreePath p = at; p != null; p = p.getParentPath()) {
                if (p.getLeaf() instanceof MethodTree && trees.getElement(p) instanceof ExecutableElement m) {
                    return m.getKind() == ElementKind.CONSTRUCTOR
                            ? Mirrors.simple(m.getEnclosingElement()) + "'s constructor" : Mirrors.where(m);
                }
                if (p.getLeaf() instanceof ClassTree && trees.getElement(p) instanceof TypeElement t) {
                    return Mirrors.simple(t);
                }
            }
            return "?";
        }
    }

    /** Whether {@code type} is the widget library's, and not its seam. */
    private static boolean widget(TypeElement type) {
        return inPackage(type, WIDGETS) && !inPackage(type, WIDGET_SEAMS);
    }

    /**
     * A step written as a method reference: a bound receiver is a capture; the method's body is read if it is here.
     *
     * @return whether its body was read
     */
    private boolean named(Subject step, Element target, MemberReferenceTree ref, CompilationUnitTree unit,
                          Set<Element> seen) {
        if (!(target instanceof ExecutableElement method)) {
            return false;
        }
        boolean isStatic = method.getModifiers().contains(Modifier.STATIC);
        Element qualifier = trees.getElement(new TreePath(trees.getPath(unit, ref), ref.getQualifierExpression()));
        boolean unbound = qualifier != null && (qualifier.getKind().isClass() || qualifier.getKind().isInterface());
        if (!isStatic && !unbound) {
            error(step, "is a method reference on an object, " + ref.getQualifierExpression() + ", so it captures it",
                    "declare the step as a static method, or keep what it needs in " + step.state(),
                    ref, unit);
            return false;
        }
        return follow(step, method, seen);
    }

    /**
     * Read {@code method}'s body as part of {@code step}'s, if its source is in this compilation.
     *
     * @return whether it was read, now or before
     */
    private boolean follow(Subject step, ExecutableElement method, Set<Element> seen) {
        if (step.holds().exact() && overridable(method)) {
            return false;   // what runs may be an override this cannot see
        }
        if (!seen.add(method)) {
            return true;
        }
        TypeElement top = topLevel(method);
        if (top != current && analyzed.contains(top)) {
            return false;   // desugared since it was analyzed: what is there now is not the source
        }
        TreePath path = trees.getPath(method);
        if (path == null || !(path.getLeaf() instanceof MethodTree tree) || tree.getBody() == null) {
            return false;
        }
        boolean hasThis = !method.getModifiers().contains(Modifier.STATIC);
        new Body(step, path.getCompilationUnit(), hasThis, seen).scan(path, null);
        return true;
    }

    /**
     * Whether a call to {@code method} may run another body than its own: an instance method that is not private or
     * final, in a class that is neither final nor anonymous (a record, and an enum without constant bodies, are final).
     */
    private static boolean overridable(ExecutableElement method) {
        Set<Modifier> m = method.getModifiers();
        if (method.getKind() != ElementKind.METHOD || m.contains(Modifier.STATIC) || m.contains(Modifier.PRIVATE)
                || m.contains(Modifier.FINAL)) {
            return false;
        }
        return !(method.getEnclosingElement() instanceof TypeElement owner)
                || !(owner.getModifiers().contains(Modifier.FINAL) || owner.getNestingKind() == NestingKind.ANONYMOUS);
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
                if (!hasThis && step.holds().confined()) {
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
            if (!step.holds().confined()) {
                if (k == ElementKind.FIELD) {
                    field(e, at, true);
                }
            } else if (k == ElementKind.LOCAL_VARIABLE || k == ElementKind.PARAMETER || k == ElementKind.EXCEPTION_PARAMETER
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

        /**
         * A field used: static and written, static and not final, volatile, a process stream, or this's. A delivery is
         * held to none of these ({@link Holds#shared}), and a display function to all but the last.
         */
        private void field(Element e, Tree at, boolean bare) {
            if (!step.holds().shared()) {
                return;
            }
            boolean isStatic = e.getModifiers().contains(Modifier.STATIC);
            String name = owner(e) + "." + e.getSimpleName();
            if (e.getModifiers().contains(Modifier.VOLATILE)) {
                error(step, "uses the volatile field " + name + ", which is state another thread changes",
                        "keep what it needs in " + step.state(), at, unit);
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
            if (bare && !hasThis && step.holds().confined()) {
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
         * read with it, and with the body's locals, which it may use as any code in the step may. A delivery does not
         * read it ({@link Holds#deferred}).
         */
        @Override
        public Void visitClass(ClassTree type, Void unused) {
            if (!step.holds().deferred()) {
                return null;
            }
            Body inner = new Body(step, unit, true, seen);
            inner.locals.addAll(locals);
            for (Tree member : type.getMembers()) {
                inner.scan(new TreePath(getCurrentPath(), member), null);
            }
            return null;
        }

        /**
         * An annotation is not code the body runs: {@code @Publishes(Pong.class)} on a step's method is
         * {@code value = Pong.class}, whose {@code value} would otherwise read as a call on the enclosing object.
         */
        @Override
        public Void visitAnnotation(AnnotationTree annotation, Void unused) {
            return null;
        }

        /**
         * A lambda in the body. A delivery does not read one ({@link Holds#deferred}): it may run now or be kept and run
         * later, on another thread, and the source does not say which.
         */
        @Override
        public Void visitLambdaExpression(LambdaExpressionTree lambda, Void unused) {
            return step.holds().deferred() ? super.visitLambdaExpression(lambda, unused) : null;
        }

        @Override
        public Void visitSynchronized(SynchronizedTree sync, Void unused) {
            if (step.holds().confined()) {
                error(step, "takes a lock (synchronized), which can stall its lane",
                        "a step owns its state and needs no lock; keep shared state in a ledger instead", sync, unit);
            }
            return super.visitSynchronized(sync, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
            Element e = trees.getElement(getCurrentPath());
            if (e instanceof ExecutableElement m) {
                if (step.holds().ledger() && isSend(m)) {
                    send(call);
                } else if (!effect(m, call) && !(step.drawn() && framed(m, call)) && !(step.holds().quiet()
                        && sends(m, call)) && !follow(step, m, seen) && step.holds().ledger()
                        && handsOut(call.getArguments())) {
                    sent.unsure();      // an Out handed to code that is not read may send anything
                }
            }
            return super.visitMethodInvocation(call, unused);
        }

        /** Refuse a call that sends on the bus, in a body that sends nothing; @return whether it was refused. */
        private boolean sends(ExecutableElement m, MethodInvocationTree call) {
            if (!(m.getEnclosingElement() instanceof TypeElement owner)) {
                return false;
            }
            Set<String> sending = SENDS.get(owner.getQualifiedName().toString());
            if (sending == null || !sending.contains(m.getSimpleName().toString())) {
                return false;
            }
            error(step, "calls " + owner.getSimpleName() + "." + m.getSimpleName() + ", which sends a message",
                    SILENT, call, unit);
            return true;
        }

        /** {@code out.send(topic, message)}: held to the list of what the step's class publishes (T4.5). */
        private void send(MethodInvocationTree call) {
            TypeMirror topic = trees.getTypeMirror(new TreePath(getCurrentPath(), call.getArguments().get(0)));
            TypeMirror payload = isClass(topic, TOPIC) && ((DeclaredType) topic).getTypeArguments().size() == 1
                    ? ((DeclaredType) topic).getTypeArguments().get(0) : null;
            if (payload == null || payload.getKind() != TypeKind.DECLARED) {
                sent.unsure();      // a type variable, a wildcard or a raw topic: no one channel to hold it to
                return;
            }
            String channel = Topics.name(payload);
            Sends sends = step.sends();
            for (TypeElement t : sends.around()) {
                sent.by.computeIfAbsent(t, k -> new HashSet<>()).add(channel);
            }
            if (sends.checked() && !sends.declared().contains(channel) && said.add(call)) {
                String name = Mirrors.simple(Mirrors.element(payload));
                String on = Mirrors.simple(sends.listOn());
                report.at(trees, Diagnostic.Kind.ERROR, "T4.5: " + step.name() + " sends " + name + ", and " + on
                        + " does not list it in @Publishes, so the message graph is missing the send: add " + name
                        + ".class to @Publishes on " + on, call, unit);
            }
        }

        /** Whether any of {@code arguments} is an {@code Out}. */
        private boolean handsOut(List<? extends ExpressionTree> arguments) {
            for (ExpressionTree a : arguments) {
                if (isClass(trees.getTypeMirror(new TreePath(getCurrentPath(), a)), OUT)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * A constructor called: refused if it is an effect, or in an asked half, which allocates nothing. A step's is
         * not read; a delivery's and a display function's is, when its source is here ({@link Holds#exact}).
         */
        @Override
        public Void visitNewClass(NewClassTree create, Void unused) {
            Element e = trees.getElement(getCurrentPath());
            if (step.holds().ledger() && handsOut(create.getArguments())) {
                sent.unsure();          // a constructor is not read, and this one is handed the Out
            }
            if (e instanceof ExecutableElement m && !effect(m, create)) {
                if (step.drawn()) {
                    error(step, "creates a new " + m.getEnclosingElement().getSimpleName() + ", which allocates in every"
                            + " frame", BUILT, create, unit);
                } else if (step.holds().exact()) {
                    follow(step, m, seen);
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

        /** A method reference in the body: a function, so a delivery does not read it, as it does not read a lambda. */
        @Override
        public Void visitMemberReference(MemberReferenceTree ref, Void unused) {
            if (!step.holds().deferred()) {
                return null;
            }
            Element e = trees.getElement(getCurrentPath());
            if (e instanceof ExecutableElement m) {
                effect(m, ref);
                if (step.holds().ledger() && (handsOut(List.of(ref.getQualifierExpression())) || takesOut(m))) {
                    sent.unsure();      // a method reference is not read, and this one sends or is handed an Out
                }
            }
            return super.visitMemberReference(ref, unused);
        }

        /** Refuse {@code m} if it is a listed effect the body is held to; @return whether it was. */
        private boolean effect(ExecutableElement m, Tree at) {
            if (!(m.getEnclosingElement() instanceof TypeElement owner)) {
                return false;
            }
            String method = m.getKind() == ElementKind.CONSTRUCTOR ? "<init>" : m.getSimpleName().toString();
            Effect found = effectOf(owner, method);
            if (found == null || !step.holds().refuses(found)) {
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

    /** The fix for a send in a display function, which sends nothing. */
    private static final String SILENT = "send from a delivery; a control in a row is described, nodes.button, and"
            + " the widget announces its press";

    /**
     * What a body is held to, beside its wording.
     *
     * @param ledger   it is a ledger's step, whose {@code out.send} calls are held to {@code @Publishes} (T4.5) and
     *                 counted for the converse
     * @param confined it sees only what it is handed: a capture of a local, of {@code this} or of the enclosing
     *                 object's members is refused, and so is a lock
     * @param shared   state another thread can see is refused: a static field written or read when not final, a
     *                 volatile field, the process's own streams
     * @param blocking only an effect that certainly makes its thread wait is refused ({@link Effect#blocking}); else
     *                 every listed effect is
     * @param deferred a lambda, a method reference or a class declared in the body is read as part of it; a delivery
     *                 leaves them, since one may be kept and run later, elsewhere
     * @param quiet    a call that sends on the bus is refused ({@link #SENDS})
     * @param exact    a method is followed only where the call certainly runs its body: not one an override may
     *                 replace, which a step's reading assumes away and a delivery's must not, since a refusal there
     *                 would be a guess
     */
    private record Holds(boolean ledger, boolean confined, boolean shared, boolean blocking, boolean deferred,
                         boolean quiet, boolean exact) {

        static final Holds STEP = new Holds(true, true, true, false, true, false, false);
        static final Holds DRAW = new Holds(false, true, true, false, true, false, false);
        static final Holds DELIVERY = new Holds(false, false, false, true, false, false, true);
        static final Holds DISPLAY = new Holds(false, false, true, false, true, true, true);

        boolean refuses(Effect effect) {
            return !blocking || effect.blocking;
        }

        /** The methods reached so far: one class's deliveries share theirs, and any other body starts its own. */
        Set<Element> seen(Set<Element> delivered) {
            return this == DELIVERY ? delivered : new HashSet<>();
        }
    }

    /**
     * What a body is read as, and so how its errors are worded: a ledger's step; a view's asked half, which is held to
     * everything a step is and to the frame's rules besides; a delivery, a {@code @Subscribe} method or a function a
     * view hands {@code Gui} to run as one, held to T5.8 only; or a display function, held to T7.2.
     *
     * @param drawn whether the body runs in every frame, so that allocating, logging, a thread or the bus is refused too
     * @param sends what its sends are held to: {@link Sends#NONE} for anything but a step
     */
    private record Subject(String name, String rule, String state, String carry, String noEffects, boolean drawn,
                           Sends sends, Holds holds) {

        static Subject step(String name, Sends sends) {
            return new Subject("step '" + name + "'",
                    "A step sees only its state, its arguments and its Out (docs/plans/chains.md, design F)",
                    "the ledger's state", "the step's arguments",
                    "a step makes no effects and never waits: send a message to a @Job that does it, and take its "
                            + "reply in a later step", false, sends, Holds.STEP);
        }

        static Subject draw(String where) {
            return new Subject("@Draw " + where,
                    "An asked half reads only its state, the Frame and its Drawn, and allocates nothing, since it runs"
                            + " in every frame (vexelray-gui docs/plans/frame-contract.md, B)",
                    "the view's state", "the view's state",
                    "an asked half makes no effects and never waits: do it in a @Subscribe method or a @Job, and keep "
                            + "what it found in the view's state", true, Sends.NONE, Holds.DRAW);
        }

        /**
         * A component's delivery, or a view's, which runs on the GUI thread, in the frame. A view may not take a job
         * (T7.4), so its fix is to send the request one hears.
         */
        static Subject delivery(String where, boolean view) {
            return view
                    ? new Subject(where,
                    "A view's delivery runs on the GUI thread, in the frame, and never blocks, sleeps or waits"
                            + " (docs/threading.md T5.8, T7.4)",
                    "the view's state", "the message",
                    "send the request a @Job hears (docs/components.md section 10), or a @Source's or a"
                            + " @Destination's if it reads or writes for as long as it is connected (sections 11 and"
                            + " 12), and show what the reply says when it arrives", false, Sends.NONE, Holds.DELIVERY)
                    : new Subject(where,
                    "A component's delivery never blocks, never sleeps and never waits for a reply"
                            + " (docs/threading.md T5.8)",
                    "the component's state", "the message",
                    "make it a @Job (docs/components.md section 10), a @Source if it reads for as long as it is"
                            + " connected (section 11), or a @Destination if it writes where a write may wait"
                            + " (section 12), send it a request, and take its reply in a later delivery", false,
                    Sends.NONE, Holds.DELIVERY);
        }

        static Subject display(String where) {
            return new Subject(where,
                    "A display function is presentation: it takes the item and returns a description, on the GUI"
                            + " thread, whenever the widget asks (docs/threading.md T7.2)",
                    "the view's state", "the item",
                    "a display function makes no effects and never waits: do it in a @Job, and hand the widget what"
                            + " it found in the item", false, Sends.NONE, Holds.DISPLAY);
        }
    }
}
