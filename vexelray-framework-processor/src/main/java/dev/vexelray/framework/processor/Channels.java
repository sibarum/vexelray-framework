package dev.vexelray.framework.processor;

import dev.vexelray.framework.api.BeforeFrame;
import dev.vexelray.framework.api.MainThread;
import dev.vexelray.framework.api.Overflow;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.api.Subscribe;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The message graph, as far as declarations reach: every {@code @Subscribe} mailbox, every {@code @Publishes}
 * sender, and the rules that need both ends of a channel in view.
 *
 * <p><b>Why this could not exist before {@code @Subscribe}.</b> A mailbox used to be a {@code Placement.subscribe}
 * call in a body and a send a {@code bus.publish}, so the processor saw neither end of any channel. Declaring the
 * receiver's policy and the sender's topics is what makes a channel a fact the compiler holds — {@code
 * docs/threading.md} T4.5, <i>an emergent graph cannot be checked; a declared one can</i>.
 *
 * <p><b>What is checked, and how far to trust it.</b>
 * <ul>
 *   <li><b>T4.1</b> — a topic subscribed both as an edge ({@code FAIL}, {@code BLOCK}) and as a sample (anything
 *       that loses) has no correct policy, and a topic name carrying two payload types is two channels one
 *       spelling apart.</li>
 *   <li><b>T4.4</b> — a cycle of declared sends between components that contains a blocking edge.</li>
 *   <li><b>T4.7</b> — main-thread code that declares a send on a topic any mailbox {@code BLOCK}s.</li>
 * </ul>
 * Both cover the compilation and what it declares. A subscriber in another module is not seen, and a send nobody
 * declared is not checked: this is a declared graph, not a proof about bodies.
 *
 * <p>Mailboxes are read only off live components — the ones the graph keeps after {@code @ConditionalOnType} —
 * so a component that does not exist in this build cannot make a channel inconsistent.
 */
final class Channels {

    /** One declared mailbox. */
    record Mailbox(TypeElement component, ExecutableElement method, String topic, TypeMirror payload,
                   Overflow overflow, int capacity) {

        String where() {
            return Mirrors.where(method);
        }
    }

    private final Mirrors mirrors;
    private final Elements elements;
    private final Messager messager;

    private final Set<Element> publishers = new LinkedHashSet<>();

    Channels(Mirrors mirrors, Elements elements, Messager messager) {
        this.mirrors = mirrors;
        this.elements = elements;
        this.messager = messager;
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
            String overflow = ((Element) mirrors.value(subscribe, "overflow")).getSimpleName().toString();
            out.add(new Mailbox(component, method, mirrors.string(subscribe, "topic"),
                    method.getParameters().get(0).asType(), Overflow.valueOf(overflow),
                    (Integer) mirrors.value(subscribe, "capacity")));
        }
        return out;
    }

    void check(Graph graph) {
        List<Mailbox> all = new ArrayList<>();
        for (TypeElement component : graph.componentsSeen()) {
            all.addAll(mailboxes(component));
        }
        Map<String, List<Mailbox>> byTopic = new LinkedHashMap<>();
        for (Mailbox m : all) {
            byTopic.computeIfAbsent(m.topic(), t -> new ArrayList<>()).add(m);
        }
        for (Map.Entry<String, List<Mailbox>> e : byTopic.entrySet()) {
            lossClasses(e.getKey(), e.getValue());
        }
        for (Element publisher : publishers) {
            mainThreadSends(publisher, byTopic);
        }
        blockingCycles(new LinkedHashSet<>(graph.componentsSeen()), byTopic);
    }

    /** T4.1. */
    private void lossClasses(String topic, List<Mailbox> mailboxes) {
        Mailbox first = mailboxes.get(0);
        for (Mailbox m : mailboxes.subList(1, mailboxes.size())) {
            if (!m.payload().toString().equals(first.payload().toString())) {
                error(m.method(), "T4.1: the topic \"" + topic + "\" carries " + first.payload() + " to "
                        + first.where() + " and " + m.payload() + " to " + m.where() + ". A topic is a name and a"
                        + " type, so these are two channels that happen to share a spelling: rename one");
            } else if (m.overflow().edge() != first.overflow().edge()) {
                Mailbox edge = first.overflow().edge() ? first : m;
                Mailbox sample = first.overflow().edge() ? m : first;
                error(m.method(), "T4.1: the topic \"" + topic + "\" is an edge to " + edge.where() + " ("
                        + edge.overflow() + ") and a sample to " + sample.where() + " (" + sample.overflow()
                        + "). A channel carrying both classes cannot be given a correct policy, because every"
                        + " choice is wrong for half the traffic: split the channel");
            }
        }
    }

    /** One declared send from a component to another's mailbox: the sender, the topic, and the mailbox it reaches. */
    private record Edge(TypeElement from, String topic, Mailbox to) {
        boolean blocks() {
            return to.overflow().blocks();
        }
    }

    /**
     * T4.4: no cycle in the message graph may contain a blocking edge.
     *
     * <p>Nodes are components; an edge is a component's declared send ({@code @Publishes} on the type or on one of
     * its methods) to a component's mailbox on that topic. A cycle is not the hazard, since request and response is
     * one and is ordinary with asynchronous mailboxes. A cycle with a {@link Overflow#BLOCK} edge is: the sender
     * waits for room in a mailbox whose owner cannot drain because it is waiting too. A self-send is the smallest
     * case, a component blocking on its own full mailbox, which no one else can ever drain.
     *
     * <p>This is the rule as threading.md states it, which is wider than the strict deadlock condition (every edge
     * on the cycle blocking). A cycle with a non-blocking hop does not deadlock; it fails when the mailbox on that
     * hop fills, which ends the process. It is rejected anyway, because the blocking hop is what turns a full
     * mailbox into a stall instead of an immediate, named failure. Sends nobody declared are not in the graph.
     */
    private void blockingCycles(Set<TypeElement> components, Map<String, List<Mailbox>> byTopic) {
        Map<TypeElement, List<Edge>> out = new LinkedHashMap<>();
        for (Element publisher : publishers) {
            TypeElement from = owner(publisher, components);
            if (from == null) {
                continue;
            }
            for (String topic : mirrors.strings(mirrors.find(publisher, Publishes.class), "value")) {
                for (Mailbox m : byTopic.getOrDefault(topic, List.of())) {
                    out.computeIfAbsent(from, c -> new ArrayList<>()).add(new Edge(from, topic, m));
                }
            }
        }
        Set<String> reported = new LinkedHashSet<>();
        for (List<Edge> edges : out.values()) {
            for (Edge blocking : edges) {
                if (!blocking.blocks()) {
                    continue;
                }
                List<Edge> back = path(out, blocking.to().component(), blocking.from());
                if (back == null) {
                    continue;
                }
                List<Edge> cycle = new ArrayList<>();
                cycle.add(blocking);
                cycle.addAll(back);
                Set<String> members = new java.util.TreeSet<>();
                cycle.forEach(e -> members.add(Mirrors.simple(e.from())));
                if (reported.add(String.join(",", members))) {
                    error(blocking.to().method(), "T4.4: " + describe(cycle) + ". " + blocking.to().where() + " has a "
                            + blocking.to().overflow() + " mailbox, so a sender on this cycle waits for room in a"
                            + " mailbox whose owner may be waiting on the next one: nothing drains and nothing fails."
                            + " Make that mailbox FAIL, so a full one is an immediate, named failure, or break the"
                            + " cycle");
                }
            }
        }
    }

    /** The shortest declared path from one component to another, or null; an empty path when they are the same. */
    private static List<Edge> path(Map<TypeElement, List<Edge>> out, TypeElement start, TypeElement goal) {
        if (start.equals(goal)) {
            return new ArrayList<>();
        }
        Map<TypeElement, Edge> reached = new LinkedHashMap<>();
        java.util.ArrayDeque<TypeElement> queue = new java.util.ArrayDeque<>();
        queue.add(start);
        reached.put(start, null);
        while (!queue.isEmpty()) {
            TypeElement at = queue.poll();
            for (Edge e : out.getOrDefault(at, List.of())) {
                TypeElement next = e.to().component();
                if (reached.containsKey(next)) {
                    continue;
                }
                reached.put(next, e);
                if (next.equals(goal)) {
                    java.util.LinkedList<Edge> found = new java.util.LinkedList<>();
                    for (Edge step = e; step != null; step = reached.get(step.from())) {
                        found.addFirst(step);
                    }
                    return found;
                }
                queue.add(next);
            }
        }
        return null;
    }

    private static String describe(List<Edge> cycle) {
        StringBuilder sb = new StringBuilder("a blocking cycle in the message graph: ");
        for (Edge e : cycle) {
            sb.append(Mirrors.simple(e.from())).append(" -[").append(e.topic()).append(e.blocks() ? ", BLOCK" : "")
                    .append("]-> ");
        }
        return sb.append(Mirrors.simple(cycle.get(0).from())).toString();
    }

    /** The component a send is declared on: the type itself, or the type a declaring method belongs to. */
    private static TypeElement owner(Element publisher, Set<TypeElement> components) {
        Element e = publisher;
        while (e != null && !(e instanceof TypeElement)) {
            e = e.getEnclosingElement();
        }
        return e instanceof TypeElement type && components.contains(type) ? type : null;
    }

    /** T4.7. */
    private void mainThreadSends(Element publisher, Map<String, List<Mailbox>> byTopic) {
        if (!onMainThread(publisher)) {
            return;
        }
        AnnotationMirror publishes = mirrors.find(publisher, Publishes.class);
        for (String topic : mirrors.strings(publishes, "value")) {
            for (Mailbox m : byTopic.getOrDefault(topic, List.of())) {
                if (m.overflow().blocks()) {
                    error(publisher, "T4.7: " + describe(publisher) + " runs on the main thread and declares a send"
                            + " on \"" + topic + "\", where " + m.where() + " has a " + m.overflow() + " mailbox."
                            + " A blocking send from the main thread to a lane that has stopped draining freezes"
                            + " the window. Make that mailbox FAIL, or coalesce it if it is a sample");
                    break;
                }
            }
        }
    }

    /**
     * Main-thread by declaration: a {@code @MainThread} type or method, a frame hook, or a method of a
     * {@code @MainThread} type.
     */
    private boolean onMainThread(Element element) {
        if (mirrors.has(element, MainThread.class) || mirrors.has(element, BeforeFrame.class)) {
            return true;
        }
        return element.getKind() == ElementKind.METHOD && mirrors.has(element.getEnclosingElement(), MainThread.class);
    }

    private static String describe(Element element) {
        return element instanceof ExecutableElement method ? Mirrors.where(method) : Mirrors.simple(element);
    }

    private void error(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
