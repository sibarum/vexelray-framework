package dev.vexelray.framework.processor;

import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.api.Setting;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.api.View;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The checks that need one declaration and nothing else: its kind, its modifiers, its parameters, its return
 * type. None of them resolves a dependency, so each is reported where it is found, in the round it is found in.
 *
 * <p>Every message names the rule it enforces and says what to write instead, because a compile error is the
 * one moment a reader is certain to be looking at the rule. The shape is the one {@code FrameStage.whenSlow}
 * set: where to look, stated by the thing that knows.
 */
final class Declarations {

    private final Mirrors mirrors;
    private final Messager messager;

    Declarations(Mirrors mirrors, Messager messager) {
        this.mirrors = mirrors;
        this.messager = messager;
    }

    // --- @Component ------------------------------------------------------------------------------------------

    /**
     * A component is an actor: a class the wiring constructs, with one constructor, on one declared lane, and
     * never main-thread.
     */
    void component(TypeElement type) {
        String name = Mirrors.simple(type);
        if (type.getKind() == ElementKind.RECORD) {
            error(type, kind(type) + " " + name + " is a record, and a value is not the container's business: a"
                    + " component has a thread and a mailbox. Reach a value through a Shell accessor, or make"
                    + " it configuration with @Setting");
            return;
        }
        if (type.getKind() != ElementKind.CLASS) {
            error(type, kind(type) + " " + name + " is not a class. A component is constructed by the wiring,"
                    + " so it has to be something the wiring can call new on");
            return;
        }
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            error(type, kind(type) + " " + name + " is abstract, and the wiring cannot construct it");
        }
        if (type.getNestingKind() == NestingKind.MEMBER && !type.getModifiers().contains(Modifier.STATIC)) {
            error(type, kind(type) + " " + name + " is an inner class, so constructing it needs an instance of"
                    + " the class around it. Make it static, or top-level");
        }
        int constructors = 0;
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() == ElementKind.CONSTRUCTOR && !e.getModifiers().contains(Modifier.PRIVATE)) {
                constructors++;
            }
        }
        if (constructors != 1) {
            error(type, kind(type) + " " + name + " has " + constructors + " non-private constructors, and must"
                    + " have exactly one. The container does not pick between them, and there is no @Inject"
                    + " to break a tie that is better not created");
        }
        if (mirrors.has(type, View.class)) {
            if (mirrors.has(type, Component.class)) {
                error(type, name + " is both a @Component and a @View. A component's mailboxes run on a lane, a"
                        + " view's on the GUI thread in the frame, and one class cannot be both: a view shows and"
                        + " tells, and a component decides");
            }
            return;
        }
        String lane = lane(type);
        if (lane.isBlank()) {
            error(type, "@Component " + name + " has a blank lane. A declared lane is a thread of its own, named"
                    + " vexel-component-<lane> in a thread dump; leave the lane out to run on the logic lane with"
                    + " every other component");
        } else if (!lane.equals(Component.DEFAULT_LANE) && !LANE_NAME.matcher(lane).matches()) {
            error(type, "@Component " + name + " has the lane \"" + lane + "\", which is not a name. A lane is a"
                    + " letter followed by letters, digits, '.', '_' or '-'; that is what keeps the default lane's"
                    + " reserved name from being something an application could write by accident");
        }
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() == ElementKind.FIELD && !e.getModifiers().contains(Modifier.STATIC)) {
                synchronised(name, (VariableElement) e);
            }
        }
    }

    /**
     * T2.7: a component's state is touched by its own deliveries and nothing else, one at a time, so a field asking for
     * synchronisation is evidence that something else touches it. Decidable from the declaration: a {@code volatile}
     * field, an {@code Atomic*}, anything of {@code java.util.concurrent} (its locks included), or a functional
     * interface, which is code somebody else will run.
     */
    private void synchronised(String component, VariableElement field) {
        String why = null;
        if (field.getModifiers().contains(Modifier.VOLATILE)) {
            why = "is volatile";
        } else if (field.asType() instanceof DeclaredType d && d.asElement() instanceof TypeElement t) {
            String qualified = t.getQualifiedName().toString();
            if (qualified.startsWith("java.util.concurrent.")) {
                why = "is a " + Mirrors.simple(t) + ", from java.util.concurrent";
            } else if (functional(t)) {
                why = "is a " + Mirrors.simple(t) + ", a functional interface: code that some other caller runs";
            }
        }
        if (why != null) {
            error(field, "T2.7: " + component + "'s field " + Mirrors.simple(field) + " " + why + ". A component's"
                    + " state is changed by its own deliveries, one at a time, and by nothing else, so it needs no"
                    + " synchronisation and holds no callback: keep a plain field, and reach anything else by a"
                    + " message");
        }
    }

    /** A functional interface by declaration, or one of the JDK's: what a field holding code looks like. */
    private boolean functional(TypeElement type) {
        if (type.getKind() != ElementKind.INTERFACE) {
            return false;
        }
        String qualified = type.getQualifiedName().toString();
        return qualified.equals("java.lang.Runnable") || qualified.startsWith("java.util.function.")
                || mirrors.find(type, FunctionalInterface.class) != null;
    }

    private static final java.util.regex.Pattern LANE_NAME = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9._-]*");

    /** The lane a component was declared on; a view's is {@link View#LANE}, the GUI thread's widget tick. */
    String lane(TypeElement component) {
        if (mirrors.has(component, View.class)) {
            return View.LANE;
        }
        return mirrors.string(mirrors.find(component, Component.class), "lane");
    }

    /** What the class was declared as, for a message: {@code @View} or {@code @Component}. */
    private String kind(TypeElement type) {
        return mirrors.has(type, View.class) ? "@View" : "@Component";
    }

    // --- @Provides, @Default ---------------------------------------------------------------------------------

    void provides(ExecutableElement method) {
        String where = Mirrors.where(method);
        Element owner = method.getEnclosingElement();
        if (!mirrors.has(owner, Configuration.class)) {
            error(method, "@Provides " + where + " is not inside a @Configuration class, which is the only"
                    + " place the wiring looks for one");
        }
        if (method.getModifiers().contains(Modifier.PRIVATE)) {
            error(method, "@Provides " + where + " is private, so the generated wiring cannot call it");
        }
        if (method.getModifiers().contains(Modifier.ABSTRACT)) {
            error(method, "@Provides " + where + " is abstract, and has no recipe to call");
        }
        returnsAnInterface(method);
    }

    /**
     * A configuration is instantiated by generated wiring, once, so an instance provider needs a class with a
     * non-private constructor that takes nothing. Static providers are called on the class, and need no instance.
     */
    void configuration(TypeElement type) {
        String name = Mirrors.simple(type);
        if (type.getKind() != ElementKind.CLASS) {
            error(type, "@Configuration " + name + " is not a class, and the wiring calls its providers on one");
            return;
        }
        boolean instance = false;
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() == ElementKind.METHOD && mirrors.has(e, Provides.class)
                    && !e.getModifiers().contains(Modifier.STATIC)) {
                instance = true;
            }
        }
        if (!instance) {
            return;
        }
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            error(type, "@Configuration " + name + " is abstract, and the wiring constructs it to call its"
                    + " providers");
            return;
        }
        boolean noArgs = false;
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() == ElementKind.CONSTRUCTOR && !e.getModifiers().contains(Modifier.PRIVATE)
                    && ((ExecutableElement) e).getParameters().isEmpty()) {
                noArgs = true;
            }
        }
        if (!noArgs) {
            error(type, "@Configuration " + name + " has no non-private constructor taking nothing, and the"
                    + " wiring constructs it to call its providers. A configuration's dependencies are its"
                    + " providers' parameters, not its own");
        }
    }

    /**
     * <b>A {@code @Provides} returns an interface</b>, when the type is the application's own and public. A
     * package-private class is exempt: nothing outside its package can hold a call site against it, so swapping
     * it is always the application's own edit.
     *
     * <p>The rule buys one thing: generated code can swap what it constructs without touching a call site, but
     * only if the call sites were written against something that can have a second implementation. A type that
     * arrives as a class file from another jar — {@code Tactroller}, {@code Clipboard}, {@code Settings}, all
     * {@code final} — cannot be given one by the application at all, and those are exactly the types
     * {@link Configuration}'s own note says a provider is for. So the rule applies where it can be kept: to
     * types compiled from source in this build.
     */
    private void returnsAnInterface(ExecutableElement method) {
        String where = Mirrors.where(method);
        TypeMirror type = method.getReturnType();
        if (type.getKind() == TypeKind.VOID) {
            // A recipe run for its effect: arming a close gate, restoring a session, binding a part to the device.
            // The wiring calls it in the phase its parameters put it in, and nothing can take what it returns.
            // Until 2026-10-24 this was an error, and three applications each returned a marker class to get past it.
            return;
        }
        TypeElement element = Mirrors.element(type);
        if (element == null) {
            error(method, "@Provides " + where + " returns " + type + ", which is a value rather than"
                    + " something with behaviour and a lifetime. A value is not the container's business:"
                    + " make it a @Setting, or an attribute on @VexelApp");
            return;
        }
        if (element.getKind() == ElementKind.INTERFACE || !mirrors.owned(element)) {
            return;
        }
        if (element.getKind() == ElementKind.CLASS && !element.getModifiers().contains(Modifier.PUBLIC)) {
            // Package-private: nothing outside the package can hold a call site against it, so a second
            // implementation is always the application's own edit and the rule has nothing to protect. A record
            // stays refused whatever its visibility -- that objection is that it is a value, not who can see it.
            return;
        }
        if (element.getKind() == ElementKind.RECORD && element.getRecordComponents().isEmpty()) {
            // A record with nothing in it carries no value and no behaviour: it is a marker for a recipe run for
            // its effect, and an interface would be the wrong advice -- there is nothing to implement twice.
            error(method, "@Provides " + where + " returns " + Mirrors.simple(element) + ", an empty record,"
                    + " which reads as a recipe run for its effect. A record is refused as a value whatever is in"
                    + " it, so give the marker another shape: " + EFFECT_SHAPE);
            return;
        }
        String kind = element.getKind() == ElementKind.RECORD
                ? "a record, and a record behind @Provides is the smell rather than the exception — a value is"
                  + " not the container's business"
                : "a concrete " + element.getKind().toString().toLowerCase() + " of this application's own";
        error(method, "@Provides " + where + " returns " + Mirrors.simple(element) + ", " + kind + ". Return"
                + " an interface, so the wiring can construct something else without touching a call site."
                + " If the recipe is run for its effect rather than for what it returns, " + EFFECT_SHAPE);
    }

    /**
     * What a recipe run only for its effect returns until the wiring can run one for effect: the shape the
     * applications already use (VexelRay IDE's {@code Recipes.Restored}, Pix's window binding). Shared by the messages that
     * would otherwise point such a recipe at an interface.
     */
    private static final String EFFECT_SHAPE = "return void, and the wiring calls it in the phase its parameters"
            + " put it in; or return an AutoCloseable if the effect has to be undone when the application closes";

    void orphanDefault(ExecutableElement method) {
        if (!mirrors.has(method, Provides.class)) {
            error(method, "@Default on " + Mirrors.where(method) + ", which is not a @Provides method. A"
                    + " default is a provider that backs off, so it has to be a provider");
        }
    }

    // --- @Setting --------------------------------------------------------------------------------------------

    /**
     * A setting binds by key to one of {@code Settings}' typed accessors, chosen by the parameter's type, and
     * its default is parsed here rather than at startup.
     */
    void setting(VariableElement parameter) {
        AnnotationMirror mirror = mirrors.find(parameter, Setting.class);
        String key = mirrors.string(mirror, "value");
        String def = mirrors.string(mirror, "def");
        String name = "@Setting(\"" + key + "\") " + Mirrors.simple(parameter);

        Element executable = parameter.getEnclosingElement();
        boolean injected = executable.getKind() == ElementKind.CONSTRUCTOR
                ? mirrors.has(executable.getEnclosingElement(), Component.class)
                : mirrors.has(executable, Provides.class);
        if (!injected) {
            error(parameter, name + " is not on a parameter the container supplies. It binds a @Component's"
                    + " constructor parameter or a @Provides method's");
        }
        if (key.isBlank()) {
            error(parameter, name + " has a blank key, which --key= and -Dkey= cannot name");
        }
        SettingType type = SettingType.of(parameter.asType());
        if (type == null) {
            error(parameter, name + " is a " + parameter.asType() + ", and Settings has no accessor for one."
                    + " A setting is a String, int, long, float, boolean or List<String>");
            return;
        }
        String refused = type.refuse(def);
        if (refused != null) {
            error(parameter, name + " has def = \"" + def + "\", " + refused + ". A default that does not"
                    + " parse is a build error rather than a fallback that silently never worked");
        }
    }

    /**
     * The types {@code Settings} has a typed accessor for, each with its own idea of what a default is.
     *
     * <p>Stricter than the accessors, on purpose, and in one place only: {@code getBoolean} reads with
     * {@code Boolean.parseBoolean}, which calls {@code "yes"} false without complaint. That leniency is right
     * for a preferences file somebody edited by hand and wrong for a default in source, which is written once
     * and read by nobody after.
     */
    private enum SettingType {
        STRING {
            @Override
            String refuse(String def) {
                return null;
            }
        },
        INT {
            @Override
            String refuse(String def) {
                try {
                    Integer.parseInt(def);
                    return null;
                } catch (NumberFormatException e) {
                    return "which is not an int";
                }
            }
        },
        LONG {
            @Override
            String refuse(String def) {
                try {
                    Long.parseLong(def);
                    return null;
                } catch (NumberFormatException e) {
                    return "which is not a long";
                }
            }
        },
        FLOAT {
            @Override
            String refuse(String def) {
                try {
                    Float.parseFloat(def);
                    return null;
                } catch (NumberFormatException e) {
                    return "which is not a float";
                }
            }
        },
        BOOLEAN {
            @Override
            String refuse(String def) {
                return def.equals("true") || def.equals("false") ? null : "which is neither true nor false";
            }
        },
        LIST {
            @Override
            String refuse(String def) {
                return def.isEmpty() ? null : "and a list has no default: getList takes none, and an absent"
                        + " list is empty";
            }
        };

        /** Why {@code def} cannot be this type's default, or {@code null} when it can. */
        abstract String refuse(String def);

        static SettingType of(TypeMirror type) {
            TypeKind kind = type.getKind();
            if (kind == TypeKind.INT) {
                return INT;
            }
            if (kind == TypeKind.LONG) {
                return LONG;
            }
            if (kind == TypeKind.FLOAT) {
                return FLOAT;
            }
            if (kind == TypeKind.BOOLEAN) {
                return BOOLEAN;
            }
            TypeElement element = Mirrors.element(type);
            if (element == null) {
                return null;
            }
            String name = element.getQualifiedName().toString();
            if (name.equals("java.lang.String")) {
                return STRING;
            }
            if (name.equals("java.util.List")) {
                var args = ((DeclaredType) type).getTypeArguments();
                TypeElement arg = args.size() == 1 ? Mirrors.element(args.get(0)) : null;
                return arg != null && arg.getQualifiedName().contentEquals("java.lang.String") ? LIST : null;
            }
            return null;
        }
    }

    // --- @BeforeFrame ----------------------------------------------------------------------------------------

    /**
     * Refused (T7.1, ROADMAP fork 4): a frame hook is application code on the main thread, in the frame budget, and
     * none runs there. The framework keeps its own hooks ({@code FrameHooks}), which no application part is handed.
     */
    void beforeFrame(ExecutableElement method) {
        error(method, "@BeforeFrame " + Mirrors.where(method) + ": no application code runs on the main thread"
                + " (T7.1), and a frame hook is exactly that. Work done over time belongs to a @Component that hears a"
                + " Timer's beat; what changes on screen every frame is a view's, told by messages, or a widget's"
                + " motion (vexelray-gui's Tween); and a picture paced by the display is the frame contract's"
                + " (vexelray-gui docs/plans/frame-contract.md)");
    }

    /**
     * Refused (T7.1, ROADMAP fork 4): a {@code @MainThread} part is application code on the main thread. The main
     * thread's values are the framework's, marked in its own table ({@code Framework.ROOTS}), and no part of an
     * application is one.
     */
    void mainThread(Element element) {
        error(element, "@MainThread on " + (element instanceof ExecutableElement method ? Mirrors.where(method)
                : Mirrors.simple(element)) + ": no application code runs on the main thread (T7.1). The window, Vulkan"
                + " and present are the framework's; an application reaches them by messages, and shows what it"
                + " decided through a @View");
    }

    // --- @Subscribe ------------------------------------------------------------------------------------------

    /**
     * A mailbox is a method the wiring can hand to a placement: on a component, taking the payload, returning
     * nothing, and throwing nothing a delivery could not deliver anywhere.
     */
    void subscribe(ExecutableElement method) {
        String where = Mirrors.where(method);
        AnnotationMirror subscribe = mirrors.find(method, Subscribe.class);
        if (!mirrors.has(method.getEnclosingElement(), Component.class)
                && !mirrors.has(method.getEnclosingElement(), View.class)) {
            error(method, "@Subscribe " + where + " is not on a @Component or a @View. A mailbox belongs to something"
                    + " with a thread to drain it: a component's lane, or a view's frame");
        }
        if (method.getModifiers().contains(Modifier.PRIVATE) || method.getModifiers().contains(Modifier.STATIC)) {
            error(method, "@Subscribe " + where + " is private or static, so the generated wiring cannot hand it to"
                    + " the component's mailbox as an instance method");
        }
        if (method.getReturnType().getKind() != TypeKind.VOID) {
            error(method, "@Subscribe " + where + " returns a value, and a delivery has nowhere to put one."
                    + " Publish the result on a topic instead");
        }
        if (!method.getThrownTypes().isEmpty()) {
            error(method, "@Subscribe " + where + " declares " + method.getThrownTypes() + ". A delivery has nowhere"
                    + " to send a checked exception: handle it here, or publish what went wrong on a topic someone"
                    + " subscribes to. Wrapping it in an unchecked exception ends the application, as any failure"
                    + " nothing declared does");
        }
        if (method.getParameters().size() != 1) {
            error(method, "@Subscribe " + where + " takes " + method.getParameters().size() + " parameters, and"
                    + " must take exactly one: the payload, whose type names the channel");
        } else {
            TypeMirror payload = method.getParameters().get(0).asType();
            if (payload.getKind() != TypeKind.DECLARED || !((DeclaredType) payload).getTypeArguments().isEmpty()) {
                error(method, "@Subscribe " + where + " takes " + payload + ", which cannot name a channel."
                        + " A payload is a class or interface with no type arguments — wrap a primitive or a"
                        + " generic in a record, which is also what makes the channel readable");
            }
        }
        if ((Integer) mirrors.value(subscribe, "capacity") < 1) {
            error(method, "@Subscribe " + where + " has a capacity below one. A mailbox holds at least a message");
        }
        List<String> keys = mirrors.strings(subscribe, "key");
        if (keys.stream().anyMatch(String::isBlank) || keys.size() != new java.util.HashSet<>(keys).size()) {
            error(method, "@Subscribe " + where + " filters by the keys " + keys + ", which has a blank or a repeated"
                    + " one. Name each key it is handed once, or leave the filter out to be handed every key");
        }
    }

    // --- @Draw -----------------------------------------------------------------------------------------------

    /**
     * A view's asked half (T7.6): on a {@code @View}, one per view, {@code static long draw(S state, Frame, Drawn)},
     * with {@code S} deeply immutable and held by exactly one of the view's instance fields. What its body may do is
     * {@link StepCheck}'s.
     */
    void draw(ExecutableElement method) {
        String where = Mirrors.where(method);
        Element owner = method.getEnclosingElement();
        if (!mirrors.has(owner, View.class)) {
            error(method, "@Draw " + where + " is not on a @View. An asked half belongs to a view, which the GUI thread"
                    + " runs; a component decides on its lane and has nothing drawn to choose");
            return;
        }
        if (drawOf((TypeElement) owner) == null) {
            error(method, "@Draw " + where + ": " + Mirrors.simple(owner) + " has more than one. A view has one asked"
                    + " half, which sets everything it shows that changes from frame to frame");
            return;
        }
        if (!method.getModifiers().contains(Modifier.STATIC)) {
            error(method, "@Draw " + where + " is not static. An asked half reads the view's state and the frame and"
                    + " nothing else, so it has no view to read: make it static, taking the state as its first parameter");
        }
        if (method.getReturnType().getKind() != TypeKind.LONG) {
            error(method, "@Draw " + where + " returns " + method.getReturnType() + ", and must return a long: when the"
                    + " view next needs asking, on the frame's clock, or Frame.NEVER once it is at rest");
        }
        if (!method.getThrownTypes().isEmpty()) {
            error(method, "@Draw " + where + " declares " + method.getThrownTypes() + ". A frame has nowhere to send"
                    + " a checked exception; an asked half that can fail is doing work the told half should have done");
        }
        List<? extends VariableElement> params = method.getParameters();
        if (params.size() != 3 || !named(params.get(1).asType(), Framework.FRAME)
                || !named(params.get(2).asType(), Framework.DRAWN)) {
            error(method, "@Draw " + where + " must take (state, Frame, Drawn): the view's state, then vexelray-gui's"
                    + " dev.vexelray.gui.core.Frame and dev.vexelray.gui.core.Drawn");
            return;
        }
        TypeMirror state = params.get(0).asType();
        String why = Shareable.drawnState(state);
        if (why != null) {
            error(method, "@Draw " + where + " takes its state as " + state + ", which is not deeply immutable: " + why
                    + ". The view replaces its state with a new value and never changes it in place, so what one frame"
                    + " is asked with is what it was told: a record of primitives, strings, enums,"
                    + " lists of those and records of those, and the Nodes it shows, which it holds as handles");
        }
        int holders = stateFields((TypeElement) owner, state).size();
        if (holders != 1) {
            error(method, "@Draw " + where + " takes a " + state + ", and " + Mirrors.simple(owner) + " has " + holders
                    + " instance fields of that type. A view keeps its state in exactly one field, which its @Subscribe"
                    + " methods replace and the generated wiring hands to this method on every frame");
        }
    }

    /** A view's one {@code @Draw} method; null when it has none, or more than one (reported by {@link #draw}). */
    ExecutableElement drawOf(TypeElement view) {
        ExecutableElement found = null;
        for (Element e : view.getEnclosedElements()) {
            if (e.getKind() == ElementKind.METHOD && mirrors.has(e, dev.vexelray.framework.api.Draw.class)) {
                if (found != null) {
                    return null;
                }
                found = (ExecutableElement) e;
            }
        }
        return found;
    }

    /** The instance fields of {@code view} whose type is exactly {@code state}. */
    static List<VariableElement> stateFields(TypeElement view, TypeMirror state) {
        List<VariableElement> out = new java.util.ArrayList<>();
        for (Element e : view.getEnclosedElements()) {
            if (e.getKind() == ElementKind.FIELD && !e.getModifiers().contains(Modifier.STATIC)
                    && e.asType().toString().equals(state.toString())) {
                out.add((VariableElement) e);
            }
        }
        return out;
    }

    private static boolean named(TypeMirror type, String qualified) {
        return type instanceof DeclaredType d && d.asElement() instanceof TypeElement t
                && t.getQualifiedName().contentEquals(qualified);
    }

    // --- @Job ------------------------------------------------------------------------------------------------

    /**
     * A job is slow work as a declaration: a final class the wiring constructs, holding no state of its own, with one
     * {@code run} taking the request and returning the reply. Everything here is the declaration's; whether a field's
     * type is something the container supplies is {@link Graph}'s, and whether anyone hears the reply is the
     * application's ({@link Channels}).
     */
    void job(TypeElement type) {
        part(type, false);
    }

    /**
     * A source is a job that sends while it runs ({@code docs/components.md}, section 11): held to everything a job is,
     * except that its {@code run} may take a {@code Closer} after the request and must return a reply, since a run's
     * end is always news. Its senders are {@link Graph}'s, and its stop message {@link Channels}'.
     */
    void source(TypeElement type) {
        if (mirrors.has(type, dev.vexelray.framework.api.Destination.class)) {
            return;     // a connection read and written: its run takes the connection, and destination() checks it
        }
        part(type, true);
        if (mirrors.has(type, dev.vexelray.framework.api.Job.class)) {
            error(type, "@Source " + Mirrors.simple(type) + " is also a @Job. A job answers one request on the job lane;"
                    + " a source runs on a thread of its own for as long as what it reads from lasts");
        }
    }

    /**
     * What every part the wiring constructs and the job lane or a thread of its own runs is: a final, static class with
     * one constructor and final fields. Whether it is a class at all, so the rest can be asked.
     */
    private boolean shape(TypeElement type, String kind) {
        String name = Mirrors.simple(type);
        if (type.getKind() != ElementKind.CLASS) {
            error(type, kind + " " + name + " is not a class. A job is constructed by the wiring and holds no state, so"
                    + " it is a final class whose run method is the work");
            return false;
        }
        if (!type.getModifiers().contains(Modifier.FINAL)) {
            error(type, kind + " " + name + " is not final. A job holds no mutable state, and a subclass could add"
                    + " some the checks never see");
        }
        if (type.getNestingKind() == NestingKind.MEMBER && !type.getModifiers().contains(Modifier.STATIC)) {
            error(type, kind + " " + name + " is an inner class, so constructing it needs an instance of the class"
                    + " around it, and it would hold that instance. Make it static, or top-level");
        }
        if (mirrors.has(type, Component.class)) {
            error(type, kind + " " + name + " is also a @Component. A job runs on the job lane, a pool with no order and"
                    + " no state; a component runs in the logic's ticks, one delivery at a time");
        }
        int constructors = 0;
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() == ElementKind.CONSTRUCTOR && !e.getModifiers().contains(Modifier.PRIVATE)) {
                constructors++;
            }
            if (e.getKind() == ElementKind.FIELD && !e.getModifiers().contains(Modifier.STATIC)
                    && !e.getModifiers().contains(Modifier.FINAL)) {
                error(e, kind + " " + name + " has the field " + Mirrors.simple(e) + ", which is not final. Two requests"
                        + " may run at once on the job lane, so a job holds no state that changes: keep it in the"
                        + " component that sent the request, and carry what the job needs in the request");
            }
        }
        if (constructors != 1) {
            error(type, kind + " " + name + " has " + constructors + " non-private constructors, and must have exactly"
                    + " one. The container does not pick between them");
        }
        return true;
    }

    /**
     * A destination ({@code docs/components.md}, section 12): shaped as a job is, with a connection the framework keeps
     * per key and hands to each method. Exactly one {@code open}, taking nothing or the request; at least one
     * {@code void write(connection, message)}; exactly one {@code void close(connection)}; and, when it is also a
     * {@code @Source}, a {@code run(request, connection)} returning why it ended. The messages' channels are
     * {@link Channels}', its senders {@link Graph}'s.
     */
    void destination(TypeElement type) {
        String kind = "@Destination";
        String name = Mirrors.simple(type);
        if (!shape(type, kind)) {
            return;
        }
        if (mirrors.has(type, dev.vexelray.framework.api.Job.class)) {
            error(type, kind + " " + name + " is also a @Job. A job answers one request on the job lane; a destination"
                    + " writes on a thread of its own for as long as its connection is open");
        }
        boolean source = mirrors.has(type, dev.vexelray.framework.api.Source.class);
        Shape s = destinationShape(type);
        if (s.opens.size() != 1) {
            error(type, kind + " " + name + " has " + s.opens.size() + " open methods, and must have exactly one: an"
                    + " instance method returning the connection, taking the request" + (source ? "" : ", or nothing for"
                    + " one connection opened when the application starts"));
        }
        if (s.closes.size() != 1) {
            error(type, kind + " " + name + " has " + s.closes.size() + " close methods, and must have exactly one: void"
                    + " close(connection), which the framework calls once, after the connection's halves are done");
        }
        if (s.writes.isEmpty()) {
            error(type, kind + " " + name + " has no write method. A destination writes: void write(connection,"
                    + " message), one for each message it hears");
        }
        if (s.opens.size() != 1) {
            return;
        }
        ExecutableElement open = s.opens.get(0);
        TypeMirror connection = open.getReturnType();
        int requests = open.getParameters().size();
        if (connection.getKind() != TypeKind.DECLARED) {
            error(open, Mirrors.where(open) + " returns " + connection + ". The connection is an object the framework"
                    + " keeps for its key: a pseudo-terminal, a socket, a stream");
            return;
        }
        if (requests > 1) {
            error(open, Mirrors.where(open) + " takes " + requests + " parameters, and must take the request, whose type"
                    + " names the channel it arrives on, or nothing");
        } else if (requests == 1) {
            message(open, "request", open.getParameters().get(0).asType());
        } else if (source) {
            error(open, Mirrors.where(open) + " takes nothing, and " + name + " is also a @Source, whose run starts"
                    + " on a request: take it here, as run does");
        }
        if (source && mirrors.find(type, dev.vexelray.framework.api.Destination.class) != null
                && mirrors.value(mirrors.find(type, dev.vexelray.framework.api.Destination.class), "stop")
                instanceof TypeMirror stop && stop.getKind() != TypeKind.VOID) {
            error(type, kind + " " + name + " names a stop on @Destination and is a @Source: name it on @Source(stop ="
                    + " ...), once");
        }
        for (ExecutableElement w : s.writes) {
            String where = Mirrors.where(w);
            if (w.getReturnType().getKind() != TypeKind.VOID) {
                error(w, where + " returns " + w.getReturnType() + ". A write returns nothing: what comes back from"
                        + " outside is read by a source");
            }
            if (w.getParameters().size() != 2 || !same(w.getParameters().get(0).asType(), connection)) {
                error(w, where + " must take the connection, " + connection + ", and then one message");
                continue;
            }
            message(w, "message", w.getParameters().get(1).asType());
        }
        for (ExecutableElement c : s.closes) {
            if (c.getReturnType().getKind() != TypeKind.VOID || c.getParameters().size() != 1
                    || !same(c.getParameters().get(0).asType(), connection)) {
                error(c, Mirrors.where(c) + " must be void close(" + connection + ")");
            }
        }
        if (source) {
            if (s.runs.size() != 1) {
                error(type, kind + " " + name + " is also a @Source, and has " + s.runs.size() + " run methods: it must"
                        + " have exactly one, taking the request and the connection and returning why it ended");
            } else {
                ExecutableElement run = s.runs.get(0);
                List<? extends VariableElement> p = run.getParameters();
                if (p.size() != 2 || requests != 1
                        || !same(p.get(0).asType(), open.getParameters().get(0).asType())
                        || !same(p.get(1).asType(), connection)) {
                    error(run, Mirrors.where(run) + " must take the request open takes and then the connection, "
                            + connection + ". A part with a connection takes no Closer: closing the connection is what"
                            + " ends a read that ignores an interrupt");
                }
                if (run.getReturnType().getKind() == TypeKind.VOID) {
                    error(run, Mirrors.where(run) + " returns nothing. A source's run returns why it ended, stopped,"
                            + " went away or failed: a run's end is always news");
                } else {
                    message(run, "reply", run.getReturnType());
                }
            }
        } else if (!s.runs.isEmpty()) {
            error(s.runs.get(0), Mirrors.where(s.runs.get(0)) + " is a run method on a destination that is not a"
                    + " @Source. A destination only writes; one that also reads is annotated @Source as well");
        }
        for (List<ExecutableElement> methods : List.of(s.opens, s.writes, s.closes, s.runs)) {
            for (ExecutableElement m : methods) {
                if (!m.getThrownTypes().isEmpty()) {
                    error(m, Mirrors.where(m) + " declares " + m.getThrownTypes() + ". A failure a destination expects"
                            + " is part of what its run reads, or of its reply; anything else that escapes ends the"
                            + " application. Catch it here");
                }
            }
        }
    }

    /** Whether two types are the same type, by their names: a destination's parameters against its connection. */
    private static boolean same(TypeMirror a, TypeMirror b) {
        return a.toString().equals(b.toString());
    }

    /** A destination's methods, by name: the instance methods that are not private. */
    record Shape(List<ExecutableElement> opens, List<ExecutableElement> writes, List<ExecutableElement> closes,
                 List<ExecutableElement> runs) {
    }

    static Shape destinationShape(TypeElement type) {
        Shape s = new Shape(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() != ElementKind.METHOD || e.getModifiers().contains(Modifier.PRIVATE)
                    || e.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            ExecutableElement m = (ExecutableElement) e;
            switch (Mirrors.simple(m)) {
                case "open" -> s.opens.add(m);
                case "write" -> s.writes.add(m);
                case "close" -> s.closes.add(m);
                case "run" -> s.runs.add(m);
                default -> {
                }
            }
        }
        return s;
    }

    private void part(TypeElement type, boolean source) {
        String kind = source ? "@Source" : "@Job";
        if (!shape(type, kind)) {
            return;
        }
        String name = Mirrors.simple(type);
        ExecutableElement run = null;
        int runs = 0;
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() == ElementKind.METHOD && Mirrors.simple(e).equals("run")
                    && !e.getModifiers().contains(Modifier.PRIVATE) && !e.getModifiers().contains(Modifier.STATIC)) {
                run = (ExecutableElement) e;
                runs++;
            }
        }
        if (runs != 1) {
            error(type, kind + " " + name + " has " + runs + " run methods, and must have exactly one: an instance"
                    + " method taking the request and returning the reply" + (source
                    ? ", which says why the run ended" : ", or void for a job run only for its effect"));
            return;
        }
        String where = Mirrors.where(run);
        int parameters = run.getParameters().size();
        if (source && parameters == 2 && !closer(run.getParameters().get(1))) {
            error(run, where + " takes " + Mirrors.simple(run.getParameters().get(1)) + " after its request, and the"
                    + " only thing a source's run takes there is a Closer: what it hands the framework to close when"
                    + " the run is stopped");
        }
        if (parameters < 1 || parameters > (source ? 2 : 1)) {
            error(run, where + " takes " + parameters + " parameters, and must take " + (source
                    ? "the request, whose type names the channel it arrives on, and may take a Closer after it"
                    : "exactly one: the request, whose type names the channel it arrives on"));
        } else {
            message(run, "request", run.getParameters().get(0).asType());
        }
        if (source && run.getReturnType().getKind() == TypeKind.VOID) {
            error(run, where + " returns nothing. A source's run returns why it ended, stopped, went away or failed,"
                    + " carrying what the caller needs to tell which run it was: a run's end is always news");
        }
        if (!run.getThrownTypes().isEmpty()) {
            error(run, where + " declares " + run.getThrownTypes() + ". A failure a job expects is part of its reply,"
                    + " a field the subscriber has to read; anything else that escapes ends the application. Catch it"
                    + " here and answer with it");
        }
        if (run.getReturnType().getKind() != TypeKind.VOID) {
            message(run, "reply", run.getReturnType());
        }
    }

    /** A job's request or reply: a class with no type arguments that can cross a lane as itself (T2.4). */
    private void message(ExecutableElement run, String what, TypeMirror type) {
        String where = Mirrors.where(run);
        if (type.getKind() != TypeKind.DECLARED || !((DeclaredType) type).getTypeArguments().isEmpty()) {
            error(run, where + "'s " + what + " is " + type + ", which cannot name a channel. A message is a"
                    + " class or interface with no type arguments: wrap it in a record");
            return;
        }
        String why = Shareable.crossing(type);
        if (why != null) {
            error(run, "T2.4: " + where + "'s " + what + " is " + type + ", and it cannot cross"
                    + " a lane as itself: " + why + ". Requests and replies cross between the sender's lane and the"
                    + " job lane, so they are deeply immutable: primitives, strings, enums, records and final"
                    + " classes of those, and lists, sets and maps of those");
        }
    }

    /** The type's one {@code run} method, or {@code null} when it has not exactly one (already reported). */
    static ExecutableElement run(TypeElement job) {
        ExecutableElement found = null;
        for (Element e : job.getEnclosedElements()) {
            if (e.getKind() == ElementKind.METHOD && Mirrors.simple(e).equals("run")
                    && !e.getModifiers().contains(Modifier.PRIVATE) && !e.getModifiers().contains(Modifier.STATIC)) {
                if (found != null) {
                    return null;
                }
                found = (ExecutableElement) e;
            }
        }
        if (found == null) {
            return null;
        }
        int parameters = found.getParameters().size();
        return parameters == 1 || (parameters == 2 && closer(found.getParameters().get(1))) ? found : null;
    }

    /** Whether {@code parameter} is the framework's {@code Closer}, which a source's run may take after its request. */
    static boolean closer(VariableElement parameter) {
        TypeElement type = Mirrors.element(parameter.asType());
        return type != null && type.getQualifiedName().contentEquals(dev.vexelray.framework.api.Closer.class.getName());
    }

    // --- @Configuration's lists ------------------------------------------------------------------------------

    /** What a configuration lists is what it says it is: every class literal resolves, to a component or a job. */
    void listed(TypeElement configuration, AnnotationMirror mirror) {
        String name = Mirrors.simple(configuration);
        for (String member : List.of("components", "jobs", "sources", "destinations")) {
            if (mirrors.unresolved(mirror, member) > 0) {
                error(configuration, "@Configuration " + name + " lists " + member + " that do not resolve. A listed"
                        + " class comes from the module that declares it, so that module has to be on the class path");
            }
        }
        for (TypeMirror type : mirrors.types(mirror, "components")) {
            TypeElement c = Mirrors.element(type);
            if (c == null || !mirrors.has(c, Component.class)) {
                error(configuration, "@Configuration " + name + " lists " + type + " among its components, and it is"
                        + " not a @Component." + (c != null && mirrors.has(c, dev.vexelray.framework.api.Job.class)
                        ? " It is a @Job: list it under jobs" : ""));
            }
        }
        for (TypeMirror type : mirrors.types(mirror, "jobs")) {
            TypeElement j = Mirrors.element(type);
            if (j == null || !mirrors.has(j, dev.vexelray.framework.api.Job.class)) {
                error(configuration, "@Configuration " + name + " lists " + type + " among its jobs, and it is not a"
                        + " @Job." + (j != null && mirrors.has(j, Component.class)
                        ? " It is a @Component: list it under components" : "")
                        + (j != null && mirrors.has(j, dev.vexelray.framework.api.Source.class)
                        ? " It is a @Source: list it under sources" : ""));
            }
        }
        for (TypeMirror type : mirrors.types(mirror, "sources")) {
            TypeElement s = Mirrors.element(type);
            if (s == null || !mirrors.has(s, dev.vexelray.framework.api.Source.class)) {
                error(configuration, "@Configuration " + name + " lists " + type + " among its sources, and it is not"
                        + " a @Source." + (s != null && mirrors.has(s, dev.vexelray.framework.api.Job.class)
                        ? " It is a @Job: list it under jobs" : ""));
            }
        }
        for (TypeMirror type : mirrors.types(mirror, "destinations")) {
            TypeElement d = Mirrors.element(type);
            if (d == null || !mirrors.has(d, dev.vexelray.framework.api.Destination.class)) {
                error(configuration, "@Configuration " + name + " lists " + type + " among its destinations, and it is"
                        + " not a @Destination." + (d != null && mirrors.has(d, dev.vexelray.framework.api.Source.class)
                        ? " It is a @Source: list it under sources" : ""));
            }
        }
    }

    // --- @VexelApp -------------------------------------------------------------------------------------------

    /** Each starter is named by class literal, and has to be something the wiring can read providers off. */
    void starters(TypeElement app, AnnotationMirror vexelApp) {
        if (mirrors.unresolved(vexelApp, "starters") > 0) {
            // javac names the class; what it most often means is a dependency that was dropped or never added.
            error(app, "@VexelApp " + Mirrors.simple(app) + " names a starter that does not resolve. A starter"
                    + " comes from the module that declares it, so that module has to be on the class path; if it"
                    + " was removed, remove the starter too");
        }
        for (TypeMirror starter : mirrors.types(vexelApp, "starters")) {
            TypeElement type = Mirrors.element(starter);
            if (type == null || !mirrors.has(type, Configuration.class)) {
                error(app, "@VexelApp " + Mirrors.simple(app) + " names " + starter + " as a starter, and it is"
                        + " not a @Configuration class. A starter is a configuration, named rather than found");
            }
        }
    }

    /**
     * The mark is an {@code .ico} or a {@code .png}, and it is there. Found where javac can look: the class output,
     * which Maven has copied {@code src/main/resources} into before compiling, and the class path, for a mark that
     * ships in a library. What the bytes hold is checked at startup, by the decoder that will read them.
     */
    void icon(TypeElement app, AnnotationMirror vexelApp, Filer filer) {
        String icon = mirrors.string(vexelApp, "icon");
        if (icon.isEmpty()) {
            return;
        }
        String lower = icon.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".ico") && !lower.endsWith(".png")) {
            error(app, "@VexelApp " + Mirrors.simple(app) + " names \"" + icon + "\" as its icon, and an icon is an"
                    + " .ico or a .png: the two forms the framework decodes without AWT. Prefer the .ico the"
                    + " executable links, so the window and the .exe wear the same file");
            return;
        }
        String path = iconPath(app, icon);
        if (!found(filer, StandardLocation.CLASS_OUTPUT, path) && !found(filer, StandardLocation.CLASS_PATH, path)) {
            error(app, "@VexelApp " + Mirrors.simple(app) + " names \"" + icon + "\" as its icon, and there is no"
                    + " resource " + path + " on the class output or the class path. The name is resolved as"
                    + " Class.getResource resolves one: relative to " + Mirrors.simple(app) + "'s package, or"
                    + " absolute with a leading /. A file in src/main/resources/" + path + " is one it finds");
        }
    }

    /**
     * The name and the names it had before agree with the installer. The installer records the install under its
     * {@code id}, and the application finds its own record (and its settings directory) by its {@code @VexelApp} name,
     * so the two are one value; a rename changes both, and lists the old one in {@code formerNames} and in the
     * installer's {@code replaces} alike. Not the other way round: a former name may never have been an install id,
     * when only the settings directory was renamed. vexelray-build reads {@code installer.json} into the bill, where this finds
     * it; without one, only the former names themselves are checked.
     */
    void names(TypeElement app, AnnotationMirror vexelApp, Filer filer) {
        String name = mirrors.string(vexelApp, "name");
        List<String> former = mirrors.strings(vexelApp, "formerNames");
        for (String f : former) {
            if (f.isBlank() || f.equals(name) || f.matches(".*[/\\\\].*") || f.equals(".") || f.equals("..")) {
                error(app, "@VexelApp " + Mirrors.simple(app) + " lists \"" + f + "\" among its formerNames, and a former"
                        + " name is a settings directory name other than its own: one path segment, not blank");
                return;
            }
        }
        java.util.Properties bill = BuildBill.read(filer);
        String installerId = bill == null ? null : bill.getProperty("installer.id");
        if (installerId == null) {
            return;
        }
        if (!installerId.equals(name)) {
            error(app, "@VexelApp " + Mirrors.simple(app) + " is named \"" + name + "\", but its installer.json has the id \""
                    + installerId + "\". They are one value: the application finds its install record, for updates, by its"
                    + " name. Make them the same, and when it is a rename, list the old one in formerNames and in"
                    + " installer.json's replaces");
            return;
        }
        String replaces = bill.getProperty("installer.replaces", "");
        List<String> listed = replaces.isBlank() ? List.of() : List.of(replaces.split(","));
        java.util.Set<String> installer = new java.util.TreeSet<>(listed);
        installer.removeAll(former);
        if (!installer.isEmpty()) {
            error(app, "@VexelApp " + Mirrors.simple(app) + " does not list " + installer + " among its formerNames, but"
                    + " its installer.json replaces " + (installer.size() == 1 ? "it" : "them") + ". A rename needs both:"
                    + " the installer moves the install, and the framework moves the settings directory");
        }
    }

    /** The classpath path of a resource named as {@code Class.getResource} names one, from {@code app}'s class. */
    static String iconPath(TypeElement app, String name) {
        if (name.startsWith("/")) {
            return name.substring(1);
        }
        Element pkg = app.getEnclosingElement();
        while (pkg.getKind() != ElementKind.PACKAGE) {
            pkg = pkg.getEnclosingElement();
        }
        String qualified = ((PackageElement) pkg).getQualifiedName().toString();
        return qualified.isEmpty() ? name : qualified.replace('.', '/') + "/" + name;
    }

    private static boolean found(Filer filer, StandardLocation where, String path) {
        try (InputStream in = filer.getResource(where, "", path).openInputStream()) {
            return in != null;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    void secondApp(TypeElement app, TypeElement first) {
        error(app, "A second @VexelApp, " + Mirrors.simple(app) + ", beside " + Mirrors.simple(first) + ". One"
                + " per application: its name is the settings directory, and two would each claim one");
    }

    private void error(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
