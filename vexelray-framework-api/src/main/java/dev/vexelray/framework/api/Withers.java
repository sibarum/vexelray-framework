package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gives a record one {@code with<Component>} method per component, each returning a copy with that one component
 * replaced: what a ledger's state needs to grow a field without a change to every constructor call.
 *
 * <p>The ledger pattern keeps a component's state as one immutable record and changes it only by its steps, so every
 * step that changes one field writes a constructor call naming all of them, and a new field is an edit to each. Java
 * gives a record no copy-with. So the processor writes an interface beside the record, holding a default wither for
 * each component, and the record implements it:
 *
 * <pre>{@code
 * @Withers
 * record State(Doc doc, long ids, Map<Long, Closing> closings) implements EditorStateWithers {
 *
 *     State withNextId() {
 *         return withIds(ids + 1);
 *     }
 * }
 * }</pre>
 *
 * <p>The interface is named for the record and the types that enclose it, outermost first, then {@code Withers}:
 * {@code EditorStateWithers} for {@code Editor.State}, {@code StateWithers} for a top-level {@code State}. It is in
 * the record's package and no more visible than the record. A wither goes through the canonical constructor, so a
 * compact constructor's copies and checks run as for any other. A method the record declares with a wither's name and
 * parameter overrides it, so a hand-written one can stay until it is deleted.
 *
 * <p>Refused at compile time: a type that is not a record, a private one or one inside a private type (the interface
 * could not name it), a generic one, and a record that does not implement the interface written for it.
 *
 * <p>Found by VexelRay IDE's v0.2 FN-10: its {@code Editor.State} had eleven fields and twelve hand-written
 * withers, and its run chain borrowed an unrelated field rather than add one more.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Stability(Stability.Level.EXPERIMENTAL)
public @interface Withers {
}
