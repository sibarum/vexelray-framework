package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Says whether a public type is part of what v1 promises, so a reader can tell without asking.
 *
 * <p>{@link Level#FROZEN} means the abstraction met the five conditions in {@code docs/v1.md} (more than one
 * witness, semantics decided in writing, extension points named, nothing legacy, nothing under <i>Blocks v1</i>) and a change
 * to it that an application would notice is a breaking change. {@link Level#EXPERIMENTAL} means it works and
 * the shape may still move; that is the mark on everything until the freeze pass has read the type. An
 * unmarked public type is one nobody has looked at, which is not the same as either.
 *
 * <p>This is a marker and nothing reads it. The processor does not check it, and a build is not rejected for
 * using an experimental type; a check here would be a new compile error, which is exactly what v1 says a
 * release may not add. It is {@code CLASS} retention for the reason {@link dev.vexelray.framework.api} gives:
 * nothing reflects, and a tool that wants the list can read it off the class files.
 */
@Stability(Stability.Level.EXPERIMENTAL)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface Stability {

    /** Which side of the freeze the type is on. */
    Level value();

    /** The two sides. There is deliberately no third: a type is either promised or it is not. */
    enum Level {
        /** Covered by the v1 promise. */
        FROZEN,
        /** Works, and may be renamed or restructured before the freeze. */
        EXPERIMENTAL
    }
}
