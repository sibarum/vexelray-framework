package dev.vexelray.framework.processor;

import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.ConditionalOnType;
import dev.vexelray.framework.api.Default;
import dev.vexelray.framework.api.Hears;
import dev.vexelray.framework.api.MainThread;
import dev.vexelray.framework.api.OnMode;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.api.RunMode;
import dev.vexelray.framework.api.Setting;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.api.View;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The checks that need every provider and component in view at once: who supplies a type, and what colour the
 * value arrives in.
 *
 * <p><b>A whole-program view, and it says so.</b> The providers are the round's {@code @Configuration}
 * classes plus the starters {@code @VexelApp} names by class literal — resolved, never searched for — and the
 * checks run once: in the round the application is seen, so the wiring can be generated in a round javac still
 * compiles, or when the last round is over for a library with no application. That is sound because the build is
 * always a clean one: every source in the module is in the first round, so nothing the checks need can be sitting
 * in a stale class file.
 * It is the answer {@code docs/architecture.md} asked for before the processor was written, taken on the side
 * that costs nothing to state.
 *
 * <p>A type nothing here provides is not this class's error. The framework's own values — {@code Gui},
 * {@code GuiApp}, the {@code Shell} — are in {@link Framework}'s table, and whether everything else a parameter
 * names is supplied is a question only an application has an answer to, so {@link Generator} asks it, and only
 * when there is a {@code @VexelApp} to generate for. A starter compiled on its own is allowed to need things.
 */
final class Graph {

    /** One {@code @Provides} method, with what the processor settles about it before any check runs. */
    record Provider(TypeElement configuration, ExecutableElement method, TypeMirror type, boolean isDefault,
                    boolean onMain, Set<String> modes) {

        /** A recipe run for its effect: it returns void, so it is called and provides nothing. */
        boolean effect() {
            return type.getKind() == TypeKind.VOID;
        }
    }

    private List<Provider> live = List.of();

    private final Mirrors mirrors;
    private final Declarations declarations;
    private final Elements elements;
    private final Types types;
    private final Messager messager;

    private final Set<TypeElement> configurations = new LinkedHashSet<>();
    private final Set<TypeElement> components = new LinkedHashSet<>();
    private final Set<TypeElement> jobs = new LinkedHashSet<>();
    /** The sources among {@link #jobs}. */
    private final Set<TypeElement> sources = new LinkedHashSet<>();

    Graph(Mirrors mirrors, Declarations declarations, Elements elements, Types types, Messager messager) {
        this.mirrors = mirrors;
        this.declarations = declarations;
        this.elements = elements;
        this.types = types;
        this.messager = messager;
    }

    void configuration(TypeElement type) {
        configurations.add(type);
    }

    void component(TypeElement type) {
        components.add(type);
    }

    void job(TypeElement type) {
        jobs.add(type);
    }

    /**
     * A source, which is a job in everything the graph asks (constructed by its one constructor, final fields, held by
     * nothing, reached by its request's topic), and is in {@link #jobsSeen} with them. What differs, the senders it may
     * take and how the wiring starts it, asks {@link #isSource}.
     */
    void source(TypeElement type) {
        jobs.add(type);
        sources.add(type);
    }

    /** Whether {@code job}, one of {@link #jobsSeen}, is a {@code @Source}. */
    boolean isSource(TypeElement job) {
        return sources.contains(job);
    }

    /** Every live job, in the order the rounds and the configurations offered them. */
    List<TypeElement> jobsSeen() {
        List<TypeElement> out = new ArrayList<>();
        for (TypeElement j : jobs) {
            if (live(j)) {
                out.add(j);
            }
        }
        return out;
    }

    /** The live providers, as the last {@link #check()} found them — what the generator builds from. */
    List<Provider> providersSeen() {
        return live;
    }

    /** Every live component, in the order the rounds offered them. */
    List<TypeElement> componentsSeen() {
        List<TypeElement> out = new ArrayList<>();
        for (TypeElement c : components) {
            if (live(c)) {
                out.add(c);
            }
        }
        return out;
    }

    void check() {
        List<Provider> providers = providers();
        live = providers;
        conflicts(providers);
        VariableElement lentTo = null;
        for (TypeElement component : components) {
            if (live(component)) {
                ExecutableElement constructor = constructor(component);
                if (constructor != null && mirrors.has(component, Hears.class)
                        && constructor.getParameters().stream().noneMatch(Graph::isInbox)) {
                    error(component, Mirrors.simple(component) + " declares @Hears and takes no Inbox. @Hears lists what a"
                            + " component hears through its inbox on the logic lane; a component on a lane of its own"
                            + " declares its mailboxes with @Subscribe");
                }
                if (constructor != null) {
                    for (VariableElement p : constructor.getParameters()) {
                        componentParameter(component, p, providers);
                        if (isComputeQueue(p)) {
                            if (lentTo != null) {
                                error(p, "T3.1: " + Mirrors.simple(component) + " takes the ComputeQueue, and so does "
                                        + Mirrors.simple(lentTo.getEnclosingElement().getEnclosingElement()) + ". The"
                                        + " queue is lent to one component, whose lane alone submits to it; a queue two"
                                        + " lanes submit to is a race the driver does not forgive. Give it to one, and"
                                        + " reach that one by publishing");
                            } else {
                                lentTo = p;
                            }
                        }
                    }
                }
            }
        }
        for (Provider provider : providers) {
            if (mirrors.owned(provider.method())) {
                for (VariableElement p : provider.method().getParameters()) {
                    providerParameter(provider, p, providers);
                }
            }
        }
        for (TypeElement job : jobsSeen()) {
            ExecutableElement constructor = constructor(job);
            if (constructor != null) {
                for (VariableElement p : constructor.getParameters()) {
                    jobParameter(job, p, providers);
                }
            }
            for (Element e : job.getEnclosedElements()) {
                if (e.getKind() == ElementKind.FIELD && !e.getModifiers().contains(Modifier.STATIC)) {
                    jobField(job, (VariableElement) e, providers);
                }
            }
        }
    }

    // --- who supplies a type ---------------------------------------------------------------------------------

    /** Every live provider: its guards passed, so it exists in this build at all. */
    private List<Provider> providers() {
        List<Provider> out = new ArrayList<>();
        for (TypeElement configuration : configurations) {
            if (!live(configuration)) {
                continue;
            }
            for (Element e : configuration.getEnclosedElements()) {
                if (e.getKind() != ElementKind.METHOD || !mirrors.has(e, Provides.class) || !live(e)) {
                    continue;
                }
                ExecutableElement method = (ExecutableElement) e;
                TypeMirror type = method.getReturnType();
                TypeElement returned = Mirrors.element(type);
                boolean main = mirrors.has(method, MainThread.class)
                        || returned != null && mirrors.has(returned, MainThread.class);
                out.add(new Provider(configuration, method, type, mirrors.has(method, Default.class), main,
                        modes(configuration, method)));
            }
        }
        return out;
    }

    /**
     * <b>Exactly one provider wins, in every mode, or it is an error naming both.</b>
     *
     * <p>{@code @Default}'s rule — one non-default wins, and failing that one default does — asked once per
     * {@link RunMode} rather than once overall, because {@code @OnMode} makes the question different in each:
     * two non-defaults guarded to disjoint modes never meet, and a non-default that exists only while windowed
     * backs a default off only while windowed. A provider whose {@code @ConditionalOnType} guard failed is not
     * in the count, because it is not in the build.
     */
    private void conflicts(List<Provider> providers) {
        Set<String> reported = new HashSet<>();
        for (RunMode mode : RunMode.values()) {
            for (int i = 0; i < providers.size(); i++) {
                Provider a = providers.get(i);
                // A recipe run for its effect provides nothing, so two of them supply nothing twice.
                if (a.effect() || !a.modes().contains(mode.name()) || !winsIn(a, mode, providers)) {
                    continue;
                }
                for (int j = i + 1; j < providers.size(); j++) {
                    Provider b = providers.get(j);
                    if (!b.modes().contains(mode.name()) || !types.isSameType(a.type(), b.type())
                            || !winsIn(b, mode, providers)) {
                        continue;
                    }
                    if (reported.add(Mirrors.where(a.method()) + "|" + Mirrors.where(b.method()))) {
                        conflict(a, b, mode);
                    }
                }
            }
        }
    }

    /** Whether {@code p} is a candidate in {@code mode}: a non-default always is, a default only unopposed. */
    boolean winsIn(Provider p, RunMode mode, List<Provider> providers) {
        if (!p.isDefault() || p.effect()) {
            return true;
        }
        for (Provider other : providers) {
            if (!other.isDefault() && other.modes().contains(mode.name())
                    && types.isSameType(other.type(), p.type())) {
                return false;
            }
        }
        return true;
    }

    private void conflict(Provider a, Provider b, RunMode mode) {
        String both = a.isDefault()
                ? "Two @Default providers, and nothing of the application's own to back either of them off"
                : "Two providers, and neither is a @Default";
        String message = both + ": " + Mirrors.where(a.method()) + " and " + Mirrors.where(b.method())
                + " both provide " + a.type() + " in " + mode + ". The framework has no business picking a"
                + " winner — remove one, mark one @Default, or guard them to different modes with @OnMode";
        // On whichever of the two is this build's own, so the message has a line to point at.
        Element at = mirrors.owned(b.method()) ? b.method() : a.method();
        messager.printMessage(Diagnostic.Kind.ERROR, message, at);
    }

    // --- what colour a value arrives in ----------------------------------------------------------------------

    /**
     * A component's constructor parameter: never a main-thread value (T2.2, since a component is never
     * main-thread), and another component only on the same lane (T2.3).
     */
    private void componentParameter(TypeElement component, VariableElement parameter, List<Provider> providers) {
        if (mirrors.has(parameter, Setting.class)) {
            return;
        }
        String name = Mirrors.simple(component);
        if (refused(name, parameter)) {
            return;
        }
        boolean view = mirrors.has(component, View.class);
        TypeElement held = Mirrors.element(parameter.asType());
        if (held != null && mirrors.has(held, View.class) && !view) {
            error(parameter, "T7.4: " + name + " takes the view " + Mirrors.simple(held) + ". A view is presentation"
                    + " on the GUI thread, and only another view may hold it: send it a message instead");
        }
        if (view && isInbox(parameter)) {
            error(parameter, "@View " + name + " takes an Inbox, which is a logic component's. A view hears in the"
                    + " frame, through its @Subscribe methods");
            return;
        }
        if (isInbox(parameter)) {
            for (Element member : component.getEnclosedElements()) {
                if (mirrors.has(member, Subscribe.class)) {
                    error(member, name + " takes its Inbox and also declares a @Subscribe mailbox. A component hears"
                            + " one way: a component that takes its inbox subscribes there itself and lists what it"
                            + " hears in @Hears, which a mailbox the wiring adds would contradict. Hear the topic in"
                            + " the inbox instead (a ledger's Builder.on, or Inbox.subscribe)");
                    break;
                }
            }
            return;
        }
        // A view is the GUI thread's (T7.4), so a main-thread value may go into it as into any main-thread part.
        String main = view ? null : whyOnMain(parameter.asType(), providers);
        if (main != null) {
            error(parameter, "T2.2: " + name + " takes " + Mirrors.simple(parameter) + ", a main-thread value ("
                    + main + "), and " + name + " is a @Component, which runs in the logic's ticks. A"
                    + " main-thread value is the framework's, and no application part but a view takes one."
                    + " Publish to it instead of holding it");
        }
        TypeElement other = Mirrors.element(parameter.asType());
        if (other != null && mirrors.has(other, Component.class)) {
            error(parameter, "T2.3: " + name + " holds " + Mirrors.simple(other) + ". A component never holds another,"
                    + " on one lane or two: two components talk by messages whether or not they share a thread, so"
                    + " moving one to a lane of its own costs capacity and never behaviour. Send it a message");
        }
        if (!view && isComputeQueue(parameter) && declarations.lane(component).equals(Component.DEFAULT_LANE)) {
            error(parameter, "T3.1: " + name + " takes the ComputeQueue and runs on the logic lane, so every other"
                    + " component's tick would wait on its submissions. Declare a lane of its own,"
                    + " @Component(lane = ...), whose thread alone submits to the queue");
        }
        if (!view && main == null && other != null && !mirrors.has(other, Component.class)) {
            provided(name, parameter, providers);
        }
        heldJob(name, parameter);
    }

    /**
     * T2.6: a value provided to a component is shareable, or a framework service. A provided value can be injected into
     * any number of parts, so one that is not deeply immutable is state two owners hold. The framework's own values are
     * its table's ({@link Framework#ROOTS}), and a component's own sender and inbox are the wiring's, made for it alone.
     */
    private void provided(String component, VariableElement parameter, List<Provider> providers) {
        TypeMirror type = parameter.asType();
        TypeElement element = Mirrors.element(type);
        if (isInbox(parameter) || Channels.isSender(parameter) || element == null
                || Framework.root(element.getQualifiedName().toString()) != null) {
            return;
        }
        for (Provider p : providers) {
            if (types.isSameType(p.type(), type)) {
                String why = Shareable.why(type);
                if (why != null) {
                    error(parameter, "T2.6: " + component + " takes " + Mirrors.simple(parameter) + ", which "
                            + Mirrors.where(p.method()) + " provides, and it is not shareable: " + why + ". A provided"
                            + " value can reach any number of parts, so one that can change is state they all hold."
                            + " Provide a record of immutable values, or make the state a component's and reach it"
                            + " by messages");
                }
                return;
            }
        }
    }

    /**
     * T2.5 and T4.5: what no application part takes ({@link Framework#REFUSED}), and an executor of any kind. Whether
     * {@code parameter} was one, so the caller says nothing more about it.
     */
    private boolean refused(String who, VariableElement parameter) {
        TypeElement type = Mirrors.element(parameter.asType());
        if (type == null) {
            return false;
        }
        String why = Framework.REFUSED.get(type.getQualifiedName().toString());
        if (why == null && type.getQualifiedName().contentEquals(Framework.SHELL)
                && !elements.getPackageOf(parameter).getQualifiedName().toString().startsWith(Framework.OWN)) {
            why = Framework.SHELL_REFUSED;
        }
        if (why == null) {
            TypeElement executor = elements.getTypeElement("java.util.concurrent.Executor");
            if (executor != null && types.isAssignable(types.erasure(parameter.asType()), executor.asType())) {
                why = "T2.5: an executor runs whatever it is handed on a thread nothing declares. Work that blocks is"
                        + " a @Job, and a component that needs a core declares @Component(lane = ...)";
            }
        }
        if (why != null) {
            error(parameter, who + " takes " + Mirrors.simple(parameter) + ", a " + Mirrors.simple(type) + ". " + why);
            return true;
        }
        return false;
    }

    /**
     * A provider's parameter: a main-thread value only into a main-thread value (T2.2), and never a component
     * (T3.7) — a value holding a component can be injected anywhere, which would carry the component across
     * lanes without a check ever seeing it.
     */
    private void providerParameter(Provider provider, VariableElement parameter, List<Provider> providers) {
        if (mirrors.has(parameter, Setting.class)) {
            return;
        }
        String where = Mirrors.where(provider.method());
        if (refused(where, parameter)) {
            return;
        }
        String main = whyOnMain(parameter.asType(), providers);
        if (main != null) {
            error(parameter, "T2.2: " + where + " takes " + Mirrors.simple(parameter) + ", a main-thread value ("
                    + main + "). A main-thread value is the framework's, and no application code runs on the main"
                    + " thread (T7.1): reach it by a message, or show what the application decided through a @View");
        }
        if (isComputeQueue(parameter)) {
            error(parameter, "T3.1: " + where + " takes the ComputeQueue, which is lent to one @Component and to"
                    + " nothing else: a provided value can be injected anywhere, and the queue would go with it to"
                    + " whatever thread asked. Take it in the component that submits to it");
        }
        TypeElement other = Mirrors.element(parameter.asType());
        if (other != null && (mirrors.has(other, Component.class) || mirrors.has(other, View.class))) {
            error(parameter, "T3.7: " + where + " takes the component " + Mirrors.simple(other) + ". The"
                    + " container hands out a channel or a shareable value, never the object, and a provided"
                    + " value holding a component would carry it to whoever asks for that value");
        }
        if (isInbox(parameter)) {
            error(parameter, where + " takes an Inbox, which is a component's own: what arrives there runs on the"
                    + " component's turn on the logic lane, and a provided value can be injected anywhere. Take it in"
                    + " the @Component's constructor");
        }
        heldJob(where, parameter);
    }

    /** Whether {@code parameter} is an atchung {@code Inbox}: the logic lane's, and only a component's to take. */
    private static boolean isInbox(VariableElement parameter) {
        TypeElement type = Mirrors.element(parameter.asType());
        return type != null && type.getQualifiedName().contentEquals(Framework.INBOX);
    }

    /**
     * A job's constructor parameter. A job runs on the job lane, a pool, so it is a crossing on every request:
     * never a main-thread value (T2.2), never a component (T2.3, which a pool has no lane to share), never the compute
     * queue (T3.1). And never another job, which is reached by publishing like any.
     */
    private void jobParameter(TypeElement job, VariableElement parameter, List<Provider> providers) {
        if (mirrors.has(parameter, Setting.class)) {
            return;
        }
        String name = Mirrors.simple(job);
        String kind = isSource(job) ? "@Source" : "@Job";
        if (refused(kind + " " + name, parameter)) {
            return;
        }
        if (Channels.isSender(parameter)) {
            // A source sends as it goes, which is what makes it one; a job's only word is its reply.
            if (!isSource(job)) {
                error(parameter, kind + " " + name + " takes " + Mirrors.simple(parameter) + ", a Sender. A job answers"
                        + " with its reply, which the wiring publishes, and sends nothing else: a request that leads to"
                        + " more is a component's to follow, and reading that keeps sending is a @Source");
            }
            return;
        }
        String lane = isSource(job) ? "a source runs on a thread of its own" : "a job runs on the job lane";
        String main = whyOnMain(parameter.asType(), providers);
        if (main != null) {
            error(parameter, "T2.2: " + kind + " " + name + " takes " + Mirrors.simple(parameter) + ", a main-thread"
                    + " value (" + main + "), and " + lane + ". Answer with what the main thread needs, and let"
                    + " a subscriber that is the main thread's act on it");
        }
        TypeElement other = Mirrors.element(parameter.asType());
        if (other != null && (mirrors.has(other, Component.class) || mirrors.has(other, View.class))) {
            error(parameter, "T2.3: " + kind + " " + name + " takes the component " + Mirrors.simple(other) + ". "
                    + Character.toUpperCase(lane.charAt(0)) + lane.substring(1) + ", which shares a thread with no"
                    + " component: carry what it needs in the request, and answer in the reply");
        }
        if (isComputeQueue(parameter)) {
            error(parameter, "T3.1: " + kind + " " + name + " takes the ComputeQueue, which is lent to one @Component"
                    + " and to nothing else");
        }
        if (isInbox(parameter)) {
            error(parameter, kind + " " + name + " takes an Inbox, which is a component's own. It hears its request"
                    + " and answers with its reply; it has no inbox to hear in");
        }
        heldJob(kind + " " + name, parameter);
    }

    /**
     * A job's field: final ({@link Declarations#job}), and of a type that is shareable or that the container supplies.
     * A field of any other type is state the job made for itself, which two requests running at once would share.
     */
    private void jobField(TypeElement job, VariableElement field, List<Provider> providers) {
        TypeMirror type = field.asType();
        if (Shareable.why(type) == null) {
            return;
        }
        TypeElement element = Mirrors.element(type);
        if (element != null && Framework.root(element.getQualifiedName().toString()) != null) {
            return;
        }
        if (isSource(job) && element != null && element.getQualifiedName().contentEquals(Channels.SENDER)) {
            return;     // what its constructor took, kept: an outside sender holds nothing that changes
        }
        for (Provider p : providers) {
            if (types.isSameType(p.type(), type)) {
                return;
            }
        }
        error(field, (isSource(job) ? "@Source " : "@Job ") + Mirrors.simple(job) + " has the field "
                + Mirrors.simple(field) + ", a " + type
                + ", which is neither shareable nor something the container supplies. Two requests may run at once,"
                + " so a job keeps no state of its own: take the part from the container, or make the value a record"
                + " of immutable values");
    }

    /** A job named as a parameter: it is reached by publishing its topic, and nothing holds one. */
    private void heldJob(String who, VariableElement parameter) {
        TypeElement other = Mirrors.element(parameter.asType());
        if (other != null && mirrors.has(other, dev.vexelray.framework.api.Job.class)) {
            error(parameter, who + " takes the job " + Mirrors.simple(other) + ". A job is reached by publishing its"
                    + " topic, never by holding it: publish the request and subscribe to the reply");
        }
        if (other != null && mirrors.has(other, dev.vexelray.framework.api.Source.class)) {
            error(parameter, who + " takes the source " + Mirrors.simple(other) + ". A source is reached by publishing"
                    + " its request, never by holding it: publish the request and subscribe to what it sends");
        }
    }

    /** Whether {@code parameter} is the compute queue a component is lent (T3.1). */
    private static boolean isComputeQueue(VariableElement parameter) {
        TypeElement type = Mirrors.element(parameter.asType());
        return type != null && type.getQualifiedName().contentEquals(Framework.COMPUTE_QUEUE);
    }

    /** Why a value of this type is main-thread, or {@code null} when it is not. */
    private String whyOnMain(TypeMirror type, List<Provider> providers) {
        if (type.getKind() != TypeKind.DECLARED) {
            return null;
        }
        TypeElement element = Mirrors.element(type);
        if (mirrors.has(element, MainThread.class)) {
            return Mirrors.simple(element) + " is @MainThread";
        }
        Framework.Root root = Framework.root(element.getQualifiedName().toString());
        if (root != null && root.onMain()) {
            return Mirrors.simple(element) + " is the main thread's, and the framework hands it out from "
                    + root.phase();
        }
        for (Provider p : providers) {
            if (mirrors.has(p.method(), MainThread.class) && types.isSameType(p.type(), type)) {
                return Mirrors.where(p.method()) + " provides it @MainThread";
            }
        }
        return null;
    }

    // --- guards ----------------------------------------------------------------------------------------------

    /**
     * Whether every {@code @ConditionalOnType} guard on the element and on its enclosing type passes: each
     * named type resolves, by name, on this compile's classpath. A guard that fails is not a {@code false}
     * branch — it is code that does not exist.
     */
    boolean live(Element element) {
        for (Element e = element; e != null && e.getKind() != ElementKind.PACKAGE; e = e.getEnclosingElement()) {
            AnnotationMirror guard = mirrors.find(e, ConditionalOnType.class);
            if (guard != null) {
                for (String name : mirrors.strings(guard, "value")) {
                    if (elements.getTypeElement(name) == null) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** The modes a provider exists in: its own {@code @OnMode} intersected with its configuration's. */
    private Set<String> modes(TypeElement configuration, ExecutableElement method) {
        Set<String> out = new LinkedHashSet<>();
        for (RunMode mode : RunMode.values()) {
            out.add(mode.name());
        }
        restrict(out, mirrors.find(configuration, OnMode.class));
        restrict(out, mirrors.find(method, OnMode.class));
        return out;
    }

    /** An empty {@code @OnMode} means every mode, as leaving it off does. */
    private void restrict(Set<String> modes, AnnotationMirror onMode) {
        if (onMode == null) {
            return;
        }
        Set<String> listed = mirrors.constants(onMode, "value");
        if (!listed.isEmpty()) {
            modes.retainAll(listed);
        }
    }

    /** The one non-private constructor, or {@code null} when there is not exactly one — already reported. */
    static ExecutableElement constructor(TypeElement type) {
        ExecutableElement found = null;
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() == ElementKind.CONSTRUCTOR && !e.getModifiers().contains(Modifier.PRIVATE)) {
                if (found != null) {
                    return null;
                }
                found = (ExecutableElement) e;
            }
        }
        return found;
    }

    private void error(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
