package dev.vexelray.framework.processor;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T2.5 and T7.2, the framework's half (ROADMAP phase 4, *Done means* 2): no public framework type an application can
 * reach takes or returns a function or an executor, so no code of an application's runs anywhere the framework did not
 * put it. Read with reflection, which is a test's to use and never the framework's.
 *
 * <p><b>What an application can reach</b> is what the wiring hands a part ({@link Framework#ROOTS}), the annotations it
 * writes, and every public type the public members of those mention, transitively. Only {@code dev.vexelray.framework}
 * members are judged here: the widget library's and atchung's are their own repos' walks (vexelray-gui's T7.2 walk),
 * and the {@code Sender} a part is handed is atchung's, which runs nothing of the caller's.
 *
 * <p><b>A ratchet.</b> What is still reachable is listed in {@link #OWED}, each with the phase that removes it, and the
 * walk must find exactly that list: a new door fails this test, and so does a door closed without crossing it off,
 * which keeps the list the truth.
 */
class ApiWalkTest {

    /** Every door still open, by the phase that closes it. */
    private static final Set<String> OWED = Set.of(
            // Phase 5, the shell: the close gate, the dialogs and the windows become topics, and the Shell itself
            // leaves an application's reach (a recipe takes it today only to be last, and to arm the close gate).
            "dev.vexelray.framework.shell.Dialogs#confirm takes java.lang.Runnable",
            "dev.vexelray.framework.shell.Dialogs#openFile takes java.lang.Runnable",
            "dev.vexelray.framework.shell.Dialogs#openFile takes java.util.function.Consumer<java.nio.file.Path>",
            "dev.vexelray.framework.shell.Dialogs#openFolder takes java.lang.Runnable",
            "dev.vexelray.framework.shell.Dialogs#openFolder takes java.util.function.Consumer<java.nio.file.Path>",
            "dev.vexelray.framework.shell.Dialogs#saveFile takes java.lang.Runnable",
            "dev.vexelray.framework.shell.Dialogs#saveFile takes java.util.function.Consumer<java.nio.file.Path>",
            "dev.vexelray.framework.shell.Window#onClosed takes java.lang.Runnable",
            "dev.vexelray.framework.shell.Window#onOpened takes java.lang.Runnable",
            // Phase 5 too: reached only through the Shell, which the generated wiring needs and a recipe still takes.
            // The processor hands none of them to a part (Framework.REFUSED), so this is the Shell's reach.
            "dev.vexelray.framework.core.Disposer#beforeClose takes java.lang.Runnable",
            "dev.vexelray.framework.core.FrameHooks#add takes java.lang.Runnable",
            "dev.vexelray.framework.core.FrameHooks#onFailure takes dev.vexelray.framework.core.FrameHooks$HookFailure",
            "dev.vexelray.framework.core.FrameHooks#onOverrun takes dev.vexelray.framework.core.FrameHooks$StageOverrun",
            "dev.vexelray.framework.core.WakeSource#onWake takes java.lang.Runnable",
            "dev.vexelray.framework.shell.InputBackend#perWindow returns dev.vexelray.gui.core.app.WindowInput$Factory",
            "dev.vexelray.framework.shell.NamedWindow#<init> takes dev.vexelray.gui.core.app.Standing",
            "dev.vexelray.framework.shell.NamedWindow#standing returns dev.vexelray.gui.core.app.Standing",
            "dev.vexelray.framework.shell.NamedWindow#standing takes dev.vexelray.gui.core.app.Standing");

    @Test
    void noFrameworkTypeAnApplicationReachesTakesOrReturnsAFunction() throws Exception {
        Set<Class<?>> seeds = new LinkedHashSet<>();
        for (Framework.Root r : Framework.ROOTS) {
            seeds.add(Class.forName(r.type()));
        }
        Set<Class<?>> seen = new LinkedHashSet<>();
        Deque<Class<?>> todo = new ArrayDeque<>(seeds);
        Set<String> found = new TreeSet<>();
        while (!todo.isEmpty()) {
            Class<?> c = todo.poll();
            if (!seen.add(c) || !Modifier.isPublic(c.getModifiers())) {
                continue;
            }
            boolean judged = c.getName().startsWith("dev.vexelray.framework.");
            for (Method m : c.getMethods()) {
                if (m.getDeclaringClass() == Object.class || m.isSynthetic() || m.isBridge()) {
                    continue;
                }
                member(m, m.getGenericReturnType(), "returns", judged, found, todo);
                for (Type p : m.getGenericParameterTypes()) {
                    member(m, p, "takes", judged, found, todo);
                }
            }
            for (Constructor<?> k : c.getConstructors()) {
                for (Type p : k.getGenericParameterTypes()) {
                    member(k, p, "takes", judged, found, todo);
                }
            }
            for (Field f : c.getFields()) {
                reach(f.getGenericType(), todo);
                if (judged && functional(f.getGenericType())) {
                    found.add(c.getName() + "." + f.getName() + " field");
                }
            }
            for (Class<?> inner : c.getClasses()) {
                todo.add(inner);
            }
        }
        assertEquals(new TreeSet<>(OWED), found, "the doors an application can reach that run its code: a new one"
                + " is refused, and one closed comes off OWED");
    }

    private static void member(Executable e, Type t, String how, boolean judged, Set<String> found,
                               Deque<Class<?>> todo) {
        reach(t, todo);
        if (judged && functional(t) && e.getDeclaringClass().getName().startsWith("dev.vexelray.framework.")) {
            String name = e instanceof Constructor ? "<init>" : e.getName();
            found.add(e.getDeclaringClass().getName() + "#" + name + " " + how + " " + t.getTypeName());
        }
    }

    private static boolean functional(Type t) {
        if (t instanceof Class<?> k) {
            return isFunction(k);
        }
        if (t instanceof ParameterizedType p) {
            if (functional(p.getRawType())) {
                return true;
            }
            for (Type a : p.getActualTypeArguments()) {
                if (functional(a)) {
                    return true;
                }
            }
        }
        if (t instanceof GenericArrayType g) {
            return functional(g.getGenericComponentType());
        }
        if (t instanceof WildcardType w) {
            for (Type b : w.getUpperBounds()) {
                if (functional(b)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A function by declaration, the JDK's own, or what runs one: an executor, a future, a subscriber. */
    private static boolean isFunction(Class<?> type) {
        return type.isAnnotationPresent(FunctionalInterface.class)
                || type == Runnable.class
                || java.util.concurrent.Callable.class == type
                || java.util.concurrent.Executor.class.isAssignableFrom(type)
                || java.util.concurrent.Future.class.isAssignableFrom(type)
                || java.util.concurrent.CompletionStage.class.isAssignableFrom(type)
                || type == sibarum.atchung.Subscriber.class;
    }

    /** Queue every public framework type {@code t} mentions: what the walk goes on to. */
    private static void reach(Type t, Deque<Class<?>> todo) {
        if (t instanceof Class<?> k) {
            while (k.isArray()) {
                k = k.getComponentType();
            }
            if (k.getName().startsWith("dev.vexelray.framework.") && !k.getPackageName().endsWith(".spi")) {
                todo.add(k);
            }
        } else if (t instanceof ParameterizedType p) {
            reach(p.getRawType(), todo);
            for (Type a : p.getActualTypeArguments()) {
                reach(a, todo);
            }
        } else if (t instanceof GenericArrayType g) {
            reach(g.getGenericComponentType(), todo);
        } else if (t instanceof WildcardType w) {
            for (Type b : w.getUpperBounds()) {
                reach(b, todo);
            }
        }
    }
}
