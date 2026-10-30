package dev.vexelray.framework.processor;

import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Hears;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.api.View;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The message graph, as far as declarations reach: every {@code @Subscribe} mailbox, every {@code Sender} a part
 * takes, every job's request and reply, and what {@code @Hears} and {@code @Publishes} list, with the rules that need
 * both ends of a channel in view.
 *
 * <p><b>A channel is a payload type.</b> Each is declared once, as the payload's {@code static final Topic}, named by the
 * payload ({@link Topics}), and every end refers to it by the type: a mailbox's parameter, a sender's type argument, a
 * job's {@code run}, a class literal in {@code @Hears} or {@code @Publishes}. So no end names a channel with a string,
 * and a reference to one that is not declared is a compile error rather than a message heard by nobody (T4.1).
 *
 * <p><b>A send is declared by construction</b> (ROADMAP fork 5): a part that sends takes a {@code Sender<T>} in its
 * constructor, and the wiring builds it. A ledger's steps send through their {@code Out}, which no constructor shows, so
 * a component with a ledger lists those payloads in {@code @Publishes}.
 *
 * <p><b>What is checked.</b>
 * <ul>
 *   <li><b>T4.1</b>: every channel a part hears or sends is one declared constant, named by its payload, that
 *       declares its kind; a key filter is on a state topic (T4.8).</li>
 *   <li><b>T2.4</b>: a mailbox payload that is not deeply immutable.</li>
 *   <li>One job per request type, and, in an application, a job's reply that somebody hears.</li>
 * </ul>
 * 0.1 also checked a blocking edge in a cycle (T4.4) and a blocking send from the main thread (T4.7); under the tick no
 * send blocks, so both hold by construction. It checked that a topic name carried one type, which a channel named by its
 * type makes true by construction too.
 *
 * <p>Mailboxes are read only off live components, the ones the graph keeps after {@code @ConditionalOnType}, so a
 * component that does not exist in this build cannot make a channel inconsistent.
 */
final class Channels {

    /** atchung's {@code Sender}: a part that takes one declares a send on its payload's topic. */
    static final String SENDER = "sibarum.atchung.Sender";

    /** One declared mailbox. {@code keys} is its key filter, empty for every key (T4.8). */
    record Mailbox(TypeElement component, ExecutableElement method, TypeMirror payload, int capacity,
                   List<String> keys) {

        String where() {
            return Mirrors.where(method);
        }

        /**
         * {@code class#method}: the component that owns the mailbox, which for an inherited method is the subclass
         * the wiring constructs, not the type that declares it. The graph's name for it.
         */
        String name() {
            return component.getQualifiedName() + "#" + Mirrors.simple(method);
        }
    }

    private final Mirrors mirrors;
    private final Elements elements;
    private final Messager messager;
    private final Topics topics;

    private final Set<Element> publishers = new LinkedHashSet<>();
    /** The constants already judged, so one used in ten places is reported once. */
    private final Set<VariableElement> judged = new HashSet<>();
    /** The widget library's package: what a widget publishes, and a view hears on the same thread. */
    private static final String GUI_PACKAGE = "dev.vexelray.gui.";

    Channels(Mirrors mirrors, Elements elements, Messager messager, Topics topics) {
        this.mirrors = mirrors;
        this.elements = elements;
        this.messager = messager;
        this.topics = topics;
    }

    void publishes(Element element) {
        publishers.add(element);
    }

    /** The mailboxes a component declares, its inherited ones included, in declaration order. */
    List<Mailbox> mailboxes(TypeElement component) {
        List<Mailbox> out = new ArrayList<>();
        for (Element member : elements.getAllMembers(component)) {
            AnnotationMirror subscribe = mirrors.find(member, Subscribe.class);
            if (member.getKind() != ElementKind.METHOD || subscribe == null) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) member;
            if (method.getParameters().size() != 1) {
                continue; // Declarations has already said so.
            }
            out.add(new Mailbox(component, method, method.getParameters().get(0).asType(),
                    (Integer) mirrors.value(subscribe, "capacity"), mirrors.strings(subscribe, "key")));
        }
        return out;
    }

    /** Whether {@code parameter} is a {@code Sender}: a declared send. */
    static boolean isSender(VariableElement parameter) {
        TypeElement type = Mirrors.element(parameter.asType());
        return type != null && type.getQualifiedName().contentEquals(SENDER);
    }

    /** What a {@code Sender<T>} parameter sends: its {@code T}, or {@code null} for a raw one. */
    static TypeMirror sent(VariableElement sender) {
        return sender.asType() instanceof DeclaredType d && d.getTypeArguments().size() == 1
                && d.getTypeArguments().get(0) instanceof DeclaredType t ? t : null;
    }

    /**
     * The one declared constant a channel of {@code payload} is, or {@code null} when there is not exactly one: what
     * the generated wiring names. {@link #check} has reported why when it is null.
     */
    VariableElement topicOf(TypeMirror payload) {
        List<VariableElement> found = topics.declared(payload);
        return found.size() == 1 ? found.get(0) : null;
    }

    /**
     * One declared {@code @Job}: its request type, which names the channel requests arrive on, and its reply type,
     * which names the one its answer goes to; {@code replyType} is {@code null} for a job run only for its effect.
     */
    record JobChannel(TypeElement job, ExecutableElement run, TypeMirror request, TypeMirror replyType) {

        String name() {
            return job.getQualifiedName().toString();
        }
    }

    /** A job's channels, or {@code null} when its shape is wrong (already reported by {@code Declarations}). */
    JobChannel job(TypeElement job) {
        ExecutableElement run = Declarations.run(job);
        if (run == null) {
            return null;
        }
        boolean effect = run.getReturnType().getKind() == TypeKind.VOID;
        return new JobChannel(job, run, run.getParameters().get(0).asType(), effect ? null : run.getReturnType());
    }

    /** Whether {@code type} can name a channel at all: a class or interface with no type arguments. */
    static boolean plain(TypeMirror type) {
        return type instanceof DeclaredType d && d.getTypeArguments().isEmpty();
    }

    /**
     * @param application whether this compilation holds the {@code @VexelApp}, so every channel is in view: only then
     *                    can a reply nobody hears be told from one heard in another module
     */
    void check(Graph graph, boolean application) {
        List<Mailbox> all = new ArrayList<>();
        for (TypeElement component : graph.componentsSeen()) {
            all.addAll(mailboxes(component));
        }
        Set<String> heard = new HashSet<>();
        Set<String> sent = new HashSet<>();
        Set<TypeElement> closeHearers = new LinkedHashSet<>();
        for (Mailbox m : all) {
            // A payload that is not a plain class or interface is already an error where @Subscribe is declared, and one
            // that cannot cross is the mailbox's first problem: its channel is asked about once it can carry anything.
            if (plain(m.payload()) && crossing(m)) {
                declared(m.method(), m.payload(), "@Subscribe " + m.where() + " hears "
                        + Mirrors.simple(Mirrors.element(m.payload())), m.keys().isEmpty() ? null : m);
                heard.add(Topics.name(m.payload()));
                if (Topics.name(m.payload()).equals(Framework.CLOSE_REQUESTED)) {
                    closeHearers.add(m.component());
                }
            }
        }
        for (VariableElement s : senders(graph)) {
            TypeMirror payload = sent(s);
            if (payload == null) {
                error(s, Mirrors.simple(s) + " is a Sender with no type argument. A sender is declared by what it"
                        + " sends: take a Sender<T> of the payload, and the wiring finds T's topic");
                continue;
            }
            declared(s, payload, describe(s) + " sends " + Mirrors.simple(Mirrors.element(payload)), null);
            sent.add(Topics.name(payload));
        }
        for (TypeElement component : graph.componentsSeen()) {
            AnnotationMirror hears = mirrors.find(component, Hears.class);
            if (hears != null) {
                for (TypeMirror payload : mirrors.types(hears, "value")) {
                    if (plain(payload)) {
                        declared(component, payload, "@Hears on " + Mirrors.simple(component) + " lists "
                                + Mirrors.simple(Mirrors.element(payload)), null);
                        heard.add(Topics.name(payload));
                        if (Topics.name(payload).equals(Framework.CLOSE_REQUESTED)) {
                            closeHearers.add(component);
                        }
                    }
                }
            }
        }
        for (Element publisher : publishers) {
            for (TypeMirror payload : mirrors.types(mirrors.find(publisher, Publishes.class), "value")) {
                if (plain(payload)) {
                    declared(publisher, payload, "@Publishes on " + describe(publisher) + " lists "
                            + Mirrors.simple(Mirrors.element(payload)), null);
                    sent.add(Topics.name(payload));
                }
            }
        }
        jobs(graph.jobsSeen(), heard, application);
        closes(closeHearers, sent, application);
    }

    /** Whether a part hears {@code CloseRequested}, so a window's close is asked of the application. After {@link #check}. */
    boolean answersClose() {
        return answersClose;
    }

    private boolean answersClose;

    /**
     * The close gate: a window's close is one question, answered once. So at most one part hears
     * {@code CloseRequested}, and, in an application, something sends {@code CloseAnswer} when one does: without it,
     * every window would stay open for good.
     */
    private void closes(Set<TypeElement> hearers, Set<String> sent, boolean application) {
        answersClose = !hearers.isEmpty();
        if (hearers.size() > 1) {
            List<String> names = new ArrayList<>();
            hearers.forEach(h -> names.add(Mirrors.simple(h)));
            for (TypeElement h : hearers) {
                error(h, Mirrors.simple(h) + " hears CloseRequested, and so do " + (names.size() - 1) + " other parts ("
                        + String.join(", ", names) + "). A window's close is answered once: let one part hear it and"
                        + " decide, and tell the others by a message of the application's own");
            }
        }
        if (application && answersClose && !sent.contains(Framework.CLOSE_ANSWER)) {
            TypeElement h = hearers.iterator().next();
            error(h, Mirrors.simple(h) + " hears CloseRequested, and nothing sends CloseAnswer. Every window's close"
                    + " now waits for an answer, so with no answer no window ever closes: take a Sender<CloseAnswer>,"
                    + " or list CloseAnswer in @Publishes if a ledger's step sends it");
        }
    }

    /**
     * Every {@code Sender} parameter of a part the wiring builds: a component's or a view's constructor, and a
     * provider's method, and a source's constructor. A job's is refused by {@link Graph}, since a job answers with its
     * reply.
     */
    List<VariableElement> senders(Graph graph) {
        List<VariableElement> out = new ArrayList<>();
        List<TypeElement> parts = new ArrayList<>(graph.componentsSeen());
        for (TypeElement j : graph.jobsSeen()) {
            if (graph.isSource(j)) {
                parts.add(j);
            }
        }
        for (TypeElement component : parts) {
            ExecutableElement constructor = Graph.constructor(component);
            if (constructor != null) {
                for (VariableElement p : constructor.getParameters()) {
                    if (isSender(p)) {
                        out.add(p);
                    }
                }
            }
        }
        for (Graph.Provider p : graph.providersSeen()) {
            for (VariableElement v : p.method().getParameters()) {
                if (isSender(v)) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    /**
     * T4.1, the declaration: a channel of {@code payload} is one declared constant, named by the payload, which says its
     * kind; and a key filter, when {@code filtered} has one, is on a state topic.
     */
    private void declared(Element at, TypeMirror payload, String what, Mailbox filtered) {
        List<VariableElement> found = topics.declared(payload);
        TypeElement type = Mirrors.element(payload);
        if (found.isEmpty()) {
            error(at, "T4.1: " + what + ", and no topic of it is declared. Declare one beside the payload, as a static"
                    + " final Topic<" + Mirrors.simple(type) + "> in " + Mirrors.simple(type) + " or in the type that"
                    + " encloses it, made with Topic.edge(" + Mirrors.simple(type) + ".class) or Topic.state("
                    + Mirrors.simple(type) + ".class, key): the constant is the channel, and it says whether every"
                    + " message counts or only the last one per key");
            return;
        }
        if (found.size() > 1) {
            List<String> names = new ArrayList<>();
            found.forEach(v -> names.add(Mirrors.simple(v)));
            error(at, "T4.1: " + what + ", and " + names.size() + " topics of it are declared (" + String.join(", ",
                    names) + "). A channel is its payload type, so each topic carries a type of its own: give each a"
                    + " record of its own");
            return;
        }
        VariableElement constant = found.get(0);
        kind(constant);
        if (filtered != null && topics.readAsEdge(constant)) {
            error(filtered.method(), "T4.8: @Subscribe " + filtered.where() + " filters by key " + filtered.keys()
                    + ", and " + Mirrors.simple(constant) + " is an edge. Only a state topic has keys: hear every"
                    + " message, or declare the topic with Topic.state and the key it is written by");
        }
    }

    /**
     * T4.1: a topic declared in this compilation's source is named by its payload and says its kind. Every
     * {@code static final Topic} the rounds offer is judged, whether or not anything here hears it, and each once.
     */
    void kind(VariableElement constant) {
        if (!judged.add(constant)) {
            return;
        }
        String why = topics.undeclared(constant);
        if (why != null) {
            error(constant, "T4.1: the topic " + Mirrors.simple(constant) + " is not declared as a channel is: " + why
                    + ". Make it with Topic.edge(P.class) (every message, in order) or Topic.state(P.class, key) (the"
                    + " last write per key in a tick): named by its payload, so every end refers to it by the type,"
                    + " and saying what kind it is, so every subscriber agrees on what it carries");
        }
    }

    /**
     * A job's two channels against everything else declared: each is declared (T4.1), one job takes each request type,
     * and, in an application, somebody hears the reply.
     */
    private void jobs(List<TypeElement> declared, Set<String> heard, boolean application) {
        Map<String, JobChannel> byRequest = new LinkedHashMap<>();
        for (TypeElement j : declared) {
            JobChannel job = job(j);
            if (job == null || !plain(job.request())) {
                continue;
            }
            String kind = kind(j);
            if (kind.equals("@Source")) {
                stop(j);
            }
            declared(job.run(), job.request(), kind + " " + Mirrors.simple(j) + " takes "
                    + Mirrors.simple(Mirrors.element(job.request())), null);
            JobChannel first = byRequest.putIfAbsent(Topics.name(job.request()), job);
            if (first != null) {
                error(j, kind + " " + Mirrors.simple(j) + " and " + kind(first.job()) + " "
                        + Mirrors.simple(first.job()) + " both take " + Mirrors.simple(Mirrors.element(job.request()))
                        + ". A request is answered once: give each a request of its own");
            }
            if (job.replyType() == null || !plain(job.replyType())) {
                continue;
            }
            declared(job.run(), job.replyType(), kind + " " + Mirrors.simple(j) + " answers "
                    + Mirrors.simple(Mirrors.element(job.replyType())), null);
            if (application && !heard.contains(Topics.name(job.replyType()))) {
                error(job.run(), kind + " " + Mirrors.simple(j) + " answers with "
                        + Mirrors.simple(Mirrors.element(job.replyType())) + ", and no component hears it. A reply"
                        + " nobody hears is a lost result, which this framework refuses as it refuses a full mailbox:"
                        + " subscribe to it" + (kind.equals("@Job")
                        ? ", or make the job void if it is run for its effect" : ""));
            }
        }
    }

    private String kind(TypeElement part) {
        return mirrors.has(part, dev.vexelray.framework.api.Source.class) ? "@Source" : "@Job";
    }

    /**
     * A source's stop message, the type {@code @Source(stop = ...)} names, or {@code null} for none ({@code void.class},
     * the default).
     */
    TypeMirror stopType(TypeElement source) {
        AnnotationMirror annotation = mirrors.find(source, dev.vexelray.framework.api.Source.class);
        Object stop = annotation == null ? null : mirrors.value(annotation, "stop");
        return stop instanceof TypeMirror type && type.getKind() != javax.lang.model.type.TypeKind.VOID ? type : null;
    }

    /** A source's stop message is a channel like any (T4.1), and crosses from its sender's lane to whoever stops. */
    private void stop(TypeElement source) {
        TypeMirror stop = stopType(source);
        if (stop == null) {
            return;
        }
        String what = "@Source " + Mirrors.simple(source) + " is stopped by "
                + (Mirrors.element(stop) != null ? Mirrors.simple(Mirrors.element(stop)) : stop.toString());
        if (!plain(stop)) {
            error(source, what + ", which cannot name a channel. A message is a class or interface with no type"
                    + " arguments: wrap it in a record");
            return;
        }
        String why = Shareable.crossing(stop);
        if (why != null) {
            error(source, "T2.4: " + what + ", and it cannot cross a lane as itself: " + why);
        }
        declared(source, stop, what, null);
    }

    /**
     * T2.4, the check half: a value crossing a lane must be one that can cross as itself.
     *
     * <p>Every mailbox is a crossing in principle: a publisher on another lane, or on the main thread, hands it a
     * value, and which publishers there are is not something declarations can bound. So the payload has to be
     * deeply immutable ({@link Shareable}). The copier that would let a mutable payload cross as a copy is not
     * built, so for now it is refused here, at the receiving method, naming the first thing that stops it. Whether it
     * can cross.
     */
    private boolean crossing(Mailbox m) {
        // A view's mailbox is drained on the GUI thread, where the widgets publish, so a widget library message heard
        // there has crossed nothing (T7.4). What else a view hears, a controller's message, is checked as any payload.
        if (mirrors.has(m.component(), View.class) && Mirrors.element(m.payload()).getQualifiedName().toString()
                .startsWith(GUI_PACKAGE)) {
            return true;
        }
        String why = Shareable.crossing(m.payload());
        if (why != null) {
            error(m.method(), "T2.4: " + m.where() + " receives " + m.payload() + ", and it cannot cross a lane as"
                    + " itself: " + why + ". A value crossing a lane must share nothing with its sender, and the copier"
                    + " that would make a mutable one safe is not built, so the payload has to be deeply immutable:"
                    + " primitives, strings, enums, records and final classes of those, and lists, sets and maps of"
                    + " those. Send a record of the values instead of the object that holds them");
        }
        return why == null;
    }

    // --- the graph, as text ----------------------------------------------------------------------------------

    /**
     * T4.5's "exposing the graph": what this class holds, written as version 1 of the bus-graph text
     * (vexelray-inspector {@code docs/bus-circuit.md} §1), for the generated wiring's {@code BUS_GRAPH}.
     *
     * <p>Each channel is named as it is at run time, by its payload's canonical name ({@link Topics#name}). A part that
     * sends but hears nothing, a {@code Ui} or a recipe, is a {@code publisher}. A send on a component this build does
     * not construct ({@code @ConditionalOnType}) is left out, as its mailboxes are. Every mailbox's {@code overflow} is
     * {@code FAIL}, which is what the tick does with an edge one more than its capacity, and a state topic never reaches.
     * Each section is sorted, so two builds of the same declarations give the same text.
     */
    String text(Collection<TypeElement> components, Collection<TypeElement> jobs, Graph graph) {
        Set<TypeElement> live = new LinkedHashSet<>(components);
        List<String> chips = new ArrayList<>();
        List<String> pins = new ArrayList<>();
        for (TypeElement c : live) {
            chips.add("component " + c.getQualifiedName() + " lane=" + (mirrors.has(c, View.class) ? View.LANE
                    : mirrors.string(mirrors.find(c, Component.class), "lane")));
            for (Mailbox m : mailboxes(c)) {
                if (plain(m.payload())) {
                    String name = Topics.name(m.payload());
                    pins.add("mailbox " + m.name() + " topic=" + name + " type=" + name + " overflow=FAIL capacity="
                            + m.capacity());
                }
            }
        }
        Set<String> others = new TreeSet<>();
        Set<String> sends = new TreeSet<>();
        for (Element publisher : publishers) {
            TypeElement type = enclosingType(publisher);
            if (type == null || (!live.contains(type) && mirrors.has(type, Component.class))) {
                continue;
            }
            if (!live.contains(type)) {
                others.add("publisher " + type.getQualifiedName() + " lane=-");
            }
            for (TypeMirror payload : mirrors.types(mirrors.find(publisher, Publishes.class), "value")) {
                if (plain(payload)) {
                    sends.add("publishes " + type.getQualifiedName() + " topic=" + Topics.name(payload));
                }
            }
        }
        for (VariableElement s : senders(graph)) {
            TypeElement type = enclosingType(s);
            TypeMirror payload = sent(s);
            if (type == null || payload == null) {
                continue;
            }
            if (!live.contains(type)) {
                others.add("publisher " + type.getQualifiedName() + " lane=-");
            }
            sends.add("publishes " + type.getQualifiedName() + " topic=" + Topics.name(payload));
        }
        // A job answers on its reply's channel, so it is a sender the Inspector draws. Where its requests arrive has no
        // line in version 1 of the text: a mailbox there is a component's, drained on a lane, and a job has neither.
        for (TypeElement j : jobs) {
            JobChannel job = job(j);
            if (job != null && job.replyType() != null && plain(job.replyType())) {
                others.add("publisher " + job.name() + " lane=-");
                sends.add("publishes " + job.name() + " topic=" + Topics.name(job.replyType()));
            }
        }
        Collections.sort(chips);
        Collections.sort(pins);
        StringBuilder out = new StringBuilder("# vexelray bus graph 1\n");
        for (List<String> section : List.of(chips, List.copyOf(others), pins, List.copyOf(sends))) {
            for (String line : section) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static TypeElement enclosingType(Element element) {
        Element e = element;
        while (e != null && !(e instanceof TypeElement)) {
            e = e.getEnclosingElement();
        }
        return (TypeElement) e;
    }

    private static String describe(Element element) {
        if (element instanceof VariableElement parameter
                && parameter.getEnclosingElement() instanceof ExecutableElement method) {
            return Mirrors.where(method);
        }
        return element instanceof ExecutableElement method ? Mirrors.where(method) : Mirrors.simple(element);
    }

    private void error(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
