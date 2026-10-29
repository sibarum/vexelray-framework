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
 * The message graph, as far as declarations reach: every {@code @Subscribe} mailbox, every {@code Sender} a part takes
 * and every {@code @Publishes}, and the rules that need both ends of a channel in view.
 *
 * <p><b>Why this could not exist before {@code @Subscribe}.</b> A mailbox used to be a {@code Placement.subscribe}
 * call in a body and a send a {@code bus.publish}, so the processor saw neither end of any channel. Declaring both ends
 * is what makes a channel a fact the compiler holds ({@code docs/threading.md} T4.5, <i>an emergent graph cannot be
 * checked; a declared one can</i>).
 *
 * <p><b>A send is declared by construction</b> (ROADMAP fork 5): a part that sends takes a {@code Sender<T>} in its
 * constructor, one per topic, and the wiring builds it. A ledger's steps send through their {@code Out}, which no
 * constructor shows, so a component with a ledger still lists those topics in {@code @Publishes}.
 *
 * <p><b>What is checked, and how far to trust it.</b>
 * <ul>
 *   <li><b>T4.1</b>: every topic a part hears or sends is a declared constant ({@link Topics}), one per payload, and
 *       declares its kind; a topic name carries one payload type; a key filter is on a state topic.</li>
 *   <li><b>T2.4</b>: a mailbox payload that is not deeply immutable.</li>
 *   <li>In an application, a job's reply that nobody hears.</li>
 * </ul>
 * 0.1 also checked a blocking edge in a cycle (T4.4) and a blocking send from the main thread (T4.7). Under the tick no
 * send blocks, so both hold by construction and their checks are gone with {@code Overflow}.
 *
 * <p>Mailboxes are read only off live components, the ones the graph keeps after {@code @ConditionalOnType}, so a
 * component that does not exist in this build cannot make a channel inconsistent.
 */
final class Channels {

    /** atchung's {@code Sender}: a part that takes one declares a send on its payload's topic. */
    static final String SENDER = "sibarum.atchung.Sender";

    /** One declared mailbox. {@code keys} is its key filter, empty for every key (T4.8). */
    record Mailbox(TypeElement component, ExecutableElement method, String topic, TypeMirror payload, int capacity,
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
    /** The topics components declare they hear through their inboxes ({@code @Hears}), read in {@link #check}. */
    private final Set<String> heard = new HashSet<>();
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
            out.add(new Mailbox(component, method, mirrors.string(subscribe, "topic"),
                    method.getParameters().get(0).asType(), (Integer) mirrors.value(subscribe, "capacity"),
                    mirrors.strings(subscribe, "key")));
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
     * One declared {@code @Job}: where its requests arrive and what they are, and where its answer goes. A job run
     * only for its effect has no reply, and {@code replyType} is then {@code null}.
     */
    record JobChannel(TypeElement job, ExecutableElement run, String topic, TypeMirror request, String reply,
                      TypeMirror replyType) {

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
        AnnotationMirror mirror = mirrors.find(job, dev.vexelray.framework.api.Job.class);
        String reply = mirrors.string(mirror, "reply");
        boolean effect = run.getReturnType().getKind() == javax.lang.model.type.TypeKind.VOID;
        return new JobChannel(job, run, mirrors.string(mirror, "topic"), run.getParameters().get(0).asType(), reply,
                effect || reply.isBlank() ? null : run.getReturnType());
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
        for (Mailbox m : all) {
            // A payload that is not a plain class or interface is already an error where @Subscribe is declared, and one
            // that cannot cross is the mailbox's first problem: its topic is asked about once it can carry anything.
            if (m.payload() instanceof DeclaredType d && d.getTypeArguments().isEmpty() && crossing(m)) {
                declared(m.method(), m.payload(), "@Subscribe " + m.where() + " hears "
                        + Mirrors.simple(Mirrors.element(m.payload())), m.keys().isEmpty() ? null : m);
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
        }
        Map<String, List<Mailbox>> byTopic = new LinkedHashMap<>();
        for (Mailbox m : all) {
            byTopic.computeIfAbsent(m.topic(), t -> new ArrayList<>()).add(m);
        }
        for (Map.Entry<String, List<Mailbox>> e : byTopic.entrySet()) {
            oneType(e.getKey(), e.getValue());
        }
        heard.clear();
        for (TypeElement component : graph.componentsSeen()) {
            if (mirrors.has(component, Hears.class)) {
                heard.addAll(mirrors.strings(mirrors.find(component, Hears.class), "value"));
            }
        }
        jobs(graph.jobsSeen(), byTopic, application);
    }

    /**
     * Every {@code Sender} parameter of a part the wiring builds: a component's or a view's constructor, and a
     * provider's method. A job's is refused by {@link Graph}, since a job answers with its reply.
     */
    List<VariableElement> senders(Graph graph) {
        List<VariableElement> out = new ArrayList<>();
        for (TypeElement component : graph.componentsSeen()) {
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
     * T4.1, the declaration: a channel of {@code payload} is one declared constant, which says its kind; and a key
     * filter, when {@code filtered} has one, is on a state topic.
     */
    private void declared(Element at, TypeMirror payload, String what, Mailbox filtered) {
        List<VariableElement> found = topics.declared(payload);
        TypeElement type = Mirrors.element(payload);
        if (found.size() != 1 && filtered == null && at instanceof ExecutableElement && !topics.inSource(type)) {
            // A library's payload carried by several of its topics (a widget library's Chosen, on every widget that has
            // a menu), or by none declared beside it: the @Subscribe names the channel, and the library's build was
            // the one to judge its kind. The wiring hears it as the edge it names.
            return;
        }
        if (found.isEmpty()) {
            error(at, "T4.1: " + what + ", and no topic of it is declared. Declare one beside the payload, as a"
                    + " static final Topic<" + Mirrors.simple(type) + "> in " + Mirrors.simple(type) + " or in the type"
                    + " that encloses it, made with Topic.edge or Topic.state: the constant is the channel, and it"
                    + " says whether every message counts or only the last one per key");
            return;
        }
        if (found.size() > 1) {
            List<String> names = new ArrayList<>();
            found.forEach(v -> names.add(Mirrors.simple(v)));
            error(at, "T4.1: " + what + ", and " + names.size() + " topics of it are declared (" + String.join(", ",
                    names) + "). A channel is found by its payload, so each topic carries a type of its own: give"
                    + " each a record of its own");
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
     * T4.1: a topic declared in this compilation's source says its kind. Every {@code static final Topic} the rounds
     * offer is judged, whether or not anything here hears it, and each once.
     */
    void kind(VariableElement constant) {
        if (!judged.add(constant)) {
            return;
        }
        String why = topics.undeclaredKind(constant);
        if (why != null) {
            error(constant, "T4.1: the topic " + Mirrors.simple(constant) + " does not declare its kind: " + why
                    + ". Make it with Topic.edge (every message, in order) or Topic.state (the last write per key in a"
                    + " tick), so every subscriber agrees on what the channel carries");
        }
    }

    /**
     * A job's two channels against everything else declared: one job per request topic, a topic carrying one type
     * whoever is at the other end (T4.1), and, in an application, a reply somebody hears.
     */
    private void jobs(List<TypeElement> declared, Map<String, List<Mailbox>> byTopic, boolean application) {
        Map<String, JobChannel> byRequest = new LinkedHashMap<>();
        for (TypeElement j : declared) {
            JobChannel job = job(j);
            if (job == null) {
                continue;
            }
            JobChannel first = byRequest.putIfAbsent(job.topic(), job);
            if (first != null) {
                error(j, "@Job " + Mirrors.simple(j) + " and @Job " + Mirrors.simple(first.job()) + " both take requests"
                        + " on \"" + job.topic() + "\". A request is answered once: give each job its own topic");
            }
            sameType(job.run(), job.topic(), job.request(), byTopic);
            if (job.replyType() == null) {
                continue;
            }
            sameType(job.run(), job.reply(), job.replyType(), byTopic);
            if (application && byTopic.getOrDefault(job.reply(), List.of()).isEmpty()
                    && !heard.contains(job.reply())) {
                error(job.run(), "@Job " + Mirrors.simple(j) + " replies on \"" + job.reply() + "\", and no component"
                        + " subscribes to it. A reply nobody hears is a lost result, which this framework refuses as"
                        + " it refuses a full mailbox: subscribe to it, or make the job void if it is run for its"
                        + " effect");
            }
        }
        for (JobChannel a : byRequest.values()) {
            for (JobChannel b : byRequest.values()) {
                if (a.replyType() != null && a.reply().equals(b.topic())
                        && !a.replyType().toString().equals(b.request().toString())) {
                    error(a.run(), "T4.1: @Job " + Mirrors.simple(a.job()) + " replies " + a.replyType() + " on \""
                            + a.reply() + "\", where @Job " + Mirrors.simple(b.job()) + " takes " + b.request()
                            + ". A topic is a name and a type: rename one");
                }
            }
        }
    }

    /** T4.1 for a job's end of a channel: the topic carries the same type to every mailbox on it. */
    private void sameType(ExecutableElement run, String topic, TypeMirror type, Map<String, List<Mailbox>> byTopic) {
        for (Mailbox m : byTopic.getOrDefault(topic, List.of())) {
            if (!m.payload().toString().equals(type.toString())) {
                error(run, "T4.1: the topic \"" + topic + "\" carries " + type + " at " + Mirrors.where(run) + " and "
                        + m.payload() + " to " + m.where() + ". A topic is a name and a type, so these are two"
                        + " channels that happen to share a spelling: rename one");
                return;
            }
        }
    }

    /** T4.1: one topic name, one payload type, across every mailbox on it. */
    private void oneType(String topic, List<Mailbox> mailboxes) {
        Mailbox first = mailboxes.get(0);
        for (Mailbox m : mailboxes.subList(1, mailboxes.size())) {
            if (!m.payload().toString().equals(first.payload().toString())) {
                error(m.method(), "T4.1: the topic \"" + topic + "\" carries " + first.payload() + " to "
                        + first.where() + " and " + m.payload() + " to " + m.where() + ". A topic is a name and a"
                        + " type, so these are two channels that happen to share a spelling: rename one");
            }
        }
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
            error(m.method(), "T2.4: " + m.where() + " receives " + m.payload() + " on \"" + m.topic() + "\", and"
                    + " it cannot cross a lane as itself: " + why + ". A value crossing a lane must share nothing"
                    + " with its sender, and the copier that would make a mutable one safe is not built, so the"
                    + " payload has to be deeply immutable: primitives, strings, enums, records and final classes"
                    + " of those, and lists, sets and maps of those. Send a record of the values instead of the"
                    + " object that holds them");
        }
        return why == null;
    }

    // --- the graph, as text ----------------------------------------------------------------------------------

    /**
     * T4.5's "exposing the graph": what this class holds, written as version 1 of the bus-graph text
     * (vexelray-inspector {@code docs/bus-circuit.md} §1), for the generated wiring's {@code BUS_GRAPH}.
     *
     * <p>A part that sends but hears nothing, a {@code Ui} or a recipe, is a {@code publisher}. A send on a component
     * this build does not construct ({@code @ConditionalOnType}) is left out, as its mailboxes are. Every mailbox's
     * {@code overflow} is {@code FAIL}, which is what the tick does with an edge one more than its capacity, and a state
     * topic never reaches. Each section is sorted, so two builds of the same declarations give the same text.
     */
    String text(Collection<TypeElement> components, Collection<TypeElement> jobs, Graph graph) {
        Set<TypeElement> live = new LinkedHashSet<>(components);
        List<String> chips = new ArrayList<>();
        List<String> pins = new ArrayList<>();
        Map<String, String> nameOf = new LinkedHashMap<>();
        for (TypeElement c : live) {
            chips.add("component " + c.getQualifiedName() + " lane=" + (mirrors.has(c, View.class) ? View.LANE
                    : mirrors.string(mirrors.find(c, Component.class), "lane")));
            for (Mailbox m : mailboxes(c)) {
                String type = Mirrors.element(m.payload()).getQualifiedName().toString();
                nameOf.putIfAbsent(type, m.topic());
                pins.add("mailbox " + m.name() + " topic=" + m.topic() + " type=" + type + " overflow=FAIL"
                        + " capacity=" + m.capacity());
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
            for (String topic : mirrors.strings(mirrors.find(publisher, Publishes.class), "value")) {
                sends.add("publishes " + type.getQualifiedName() + " topic=" + topic);
            }
        }
        for (VariableElement s : senders(graph)) {
            TypeElement type = enclosingType(s);
            TypeMirror payload = sent(s);
            VariableElement constant = payload == null ? null : topicOf(payload);
            if (type == null || constant == null) {
                continue;
            }
            String name = topics.name(constant);
            if (name == null) {
                name = nameOf.get(Mirrors.element(payload).getQualifiedName().toString());
            }
            if (name == null) {
                continue;   // a name built at run time, which nothing here hears: no line to draw it on
            }
            if (!live.contains(type)) {
                others.add("publisher " + type.getQualifiedName() + " lane=-");
            }
            sends.add("publishes " + type.getQualifiedName() + " topic=" + name);
        }
        // A job answers on its reply topic, so it is a sender the Inspector draws. Where its requests arrive has no
        // line in version 1 of the text: a mailbox there is a component's, drained on a lane, and a job has neither.
        for (TypeElement j : jobs) {
            JobChannel job = job(j);
            if (job != null && job.replyType() != null) {
                others.add("publisher " + job.name() + " lane=-");
                sends.add("publishes " + job.name() + " topic=" + job.reply());
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

    private static String describe(VariableElement parameter) {
        Element owner = parameter.getEnclosingElement();
        return owner instanceof ExecutableElement method ? Mirrors.where(method) : Mirrors.simple(owner);
    }

    private void error(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
