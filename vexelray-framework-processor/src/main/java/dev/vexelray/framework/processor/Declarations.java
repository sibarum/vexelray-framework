package dev.vexelray.framework.processor;

import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.MainThread;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.api.Setting;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
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
import javax.tools.Diagnostic;

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
            error(type, "@Component " + name + " is a record, and a value is not the container's business: a"
                    + " component has a thread and a mailbox. Reach a value through a Shell accessor, or make"
                    + " it configuration with @Setting");
            return;
        }
        if (type.getKind() != ElementKind.CLASS) {
            error(type, "@Component " + name + " is not a class. A component is constructed by the wiring,"
                    + " so it has to be something the wiring can call new on");
            return;
        }
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            error(type, "@Component " + name + " is abstract, and the wiring cannot construct it");
        }
        if (type.getNestingKind() == NestingKind.MEMBER && !type.getModifiers().contains(Modifier.STATIC)) {
            error(type, "@Component " + name + " is an inner class, so constructing it needs an instance of"
                    + " the class around it. Make it static, or top-level");
        }
        int constructors = 0;
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() == ElementKind.CONSTRUCTOR && !e.getModifiers().contains(Modifier.PRIVATE)) {
                constructors++;
            }
        }
        if (constructors != 1) {
            error(type, "@Component " + name + " has " + constructors + " non-private constructors, and must"
                    + " have exactly one. The container does not pick between them, and there is no @Inject"
                    + " to break a tie that is better not created");
        }
        if (mirrors.has(type, MainThread.class)) {
            error(type, "T2.1: " + name + " is both a @Component and @MainThread. A component owns a thread"
                    + " of its own, and a value has exactly one colour; the main thread is not a lane a"
                    + " component can be placed on");
        }
        String lane = lane(type);
        if (lane.isBlank()) {
            error(type, "@Component " + name + " has a blank lane. The lane is the component's thread, named"
                    + " vexel-component-<lane> in a thread dump, and components sharing a lane are the only"
                    + " ones that may hold each other");
        }
    }

    /** The lane a component was declared on. */
    String lane(TypeElement component) {
        return mirrors.string(mirrors.find(component, Component.class), "lane");
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
            error(method, "@Provides " + where + " returns void, so it provides nothing");
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
        String kind = element.getKind() == ElementKind.RECORD
                ? "a record, and a record behind @Provides is the smell rather than the exception — a value is"
                  + " not the container's business"
                : "a concrete " + element.getKind().toString().toLowerCase() + " of this application's own";
        error(method, "@Provides " + where + " returns " + Mirrors.simple(element) + ", " + kind + ". Return"
                + " an interface, so the wiring can construct something else without touching a call site");
    }

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
     * A frame hook is a no-argument call on the main thread, in the frame budget, that must not throw.
     */
    void beforeFrame(ExecutableElement method) {
        String where = Mirrors.where(method);
        if (!method.getParameters().isEmpty()) {
            error(method, "@BeforeFrame " + where + " takes parameters, and a frame hook is called with none");
        }
        if (!method.getThrownTypes().isEmpty()) {
            error(method, "@BeforeFrame " + where + " declares " + method.getThrownTypes() + ". A hook that"
                    + " throws takes the frame loop down with it, and the loop is the application: drop this"
                    + " frame's work rather than tear the loop down");
        }
        if (method.getModifiers().contains(Modifier.PRIVATE)) {
            error(method, "@BeforeFrame " + where + " is private, so the generated frame array cannot call it");
        }
        if (mirrors.has(method.getEnclosingElement(), Component.class)) {
            error(method, "@BeforeFrame " + where + " is on a @Component. The frame is the main thread's, and"
                    + " no component enters it: a hook here would run on the main thread against state that"
                    + " belongs to the component's own. Publish to the component instead");
        }
    }

    // --- @VexelApp -------------------------------------------------------------------------------------------

    /** Each starter is named by class literal, and has to be something the wiring can read providers off. */
    void starters(TypeElement app, AnnotationMirror vexelApp) {
        for (TypeMirror starter : mirrors.types(vexelApp, "starters")) {
            TypeElement type = Mirrors.element(starter);
            if (type == null || !mirrors.has(type, Configuration.class)) {
                error(app, "@VexelApp " + Mirrors.simple(app) + " names " + starter + " as a starter, and it is"
                        + " not a @Configuration class. A starter is a configuration, named rather than found");
            }
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
