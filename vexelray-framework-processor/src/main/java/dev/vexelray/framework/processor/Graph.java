package dev.vexelray.framework.processor;

import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.ConditionalOnType;
import dev.vexelray.framework.api.Default;
import dev.vexelray.framework.api.MainThread;
import dev.vexelray.framework.api.OnMode;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.api.RunMode;
import dev.vexelray.framework.api.Setting;

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
    }

    private List<Provider> live = List.of();

    private final Mirrors mirrors;
    private final Declarations declarations;
    private final Elements elements;
    private final Types types;
    private final Messager messager;

    private final Set<TypeElement> configurations = new LinkedHashSet<>();
    private final Set<TypeElement> components = new LinkedHashSet<>();

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
        for (TypeElement component : components) {
            if (live(component)) {
                ExecutableElement constructor = constructor(component);
                if (constructor != null) {
                    for (VariableElement p : constructor.getParameters()) {
                        componentParameter(component, p, providers);
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
                if (!a.modes().contains(mode.name()) || !winsIn(a, mode, providers)) {
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
        if (!p.isDefault()) {
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
        String main = whyOnMain(parameter.asType(), providers);
        if (main != null) {
            error(parameter, "T2.2: " + name + " takes " + Mirrors.simple(parameter) + ", a main-thread value ("
                    + main + "), and " + name + " is a @Component, which runs on a lane of its own. A"
                    + " main-thread value may be injected only into something that is itself main-thread."
                    + " Publish to it instead of holding it");
        }
        TypeElement other = Mirrors.element(parameter.asType());
        if (other != null && mirrors.has(other, Component.class)) {
            String here = declarations.lane(component);
            String there = declarations.lane(other);
            if (!here.equals(there)) {
                error(parameter, "T2.3: " + name + " (lane \"" + here + "\") holds " + Mirrors.simple(other)
                        + " (lane \"" + there + "\"). A direct reference between two components is permitted"
                        + " only where they share a thread; across lanes, behaviour is reached by publishing,"
                        + " never by holding");
            }
        }
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
        String main = whyOnMain(parameter.asType(), providers);
        if (main != null && !provider.onMain()) {
            error(parameter, "T2.2: " + where + " takes " + Mirrors.simple(parameter) + ", a main-thread value ("
                    + main + "), and what it provides is not @MainThread. A main-thread value may be injected"
                    + " only into something that is itself main-thread: mark " + where + " @MainThread if its"
                    + " value lives there too");
        }
        TypeElement other = Mirrors.element(parameter.asType());
        if (other != null && mirrors.has(other, Component.class)) {
            error(parameter, "T3.7: " + where + " takes the component " + Mirrors.simple(other) + ". The"
                    + " container hands out a channel or a shareable value, never the object, and a provided"
                    + " value holding a component would carry it to whoever asks for that value");
        }
        if (other != null && other.getQualifiedName().contentEquals(Framework.PLACEMENT)) {
            error(parameter, where + " takes a Placement, which is a component's thread and mailboxes. Only a"
                    + " @Component's constructor is handed one — the placement of the lane it is declared on");
        }
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
    private static ExecutableElement constructor(TypeElement type) {
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
