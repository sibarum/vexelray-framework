package dev.vexelray.framework.processor;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import java.util.HashSet;
import java.util.Set;

/**
 * Whether a value can cross a lane as itself: whether it is <b>deeply immutable</b>, so that sharing it is
 * indistinguishable from copying it (T2.4).
 *
 * <p><b>This is the check half of T2.4 and nothing more.</b> The ruled design has the processor emit a copier for
 * anything else, so that a mutable payload crosses as a copy. That is not built. Until it is, a payload that is not
 * provably immutable is refused, which is the same rule with the copy missing: it accepts less, and building the
 * copier later only accepts more, so it is additive. Nothing here is trusted on an annotation's word, since an
 * annotation the processor believes is a promise that can be wrong and still compile.
 *
 * <p><b>What is provable from a declaration, and so what is accepted:</b>
 * <ul>
 *   <li>primitives, their boxes, {@code String}, an enum, and a short list of JDK value types;</li>
 *   <li>a record whose every component is itself shareable;</li>
 *   <li>a {@code final} class whose fields, and every superclass's, are all {@code final} and shareable;</li>
 *   <li>a {@code sealed} interface or class whose every permitted subtype is shareable (and, for a class, whose
 *       own fields are);</li>
 *   <li>{@code Optional} of a shareable type;</li>
 *   <li>in a payload only ({@link #crossing}), a {@code List}, {@code Set} or {@code Map} of shareable types.</li>
 * </ul>
 * <b>What is refused, and why it is not a limitation of the check:</b> an array (mutable), an interface that is not
 * sealed (its implementations are unknown), any other generic type, a class with a mutable field, and an inner
 * class (it holds the instance that made it, through a field the declaration does not show). A collection is refused
 * outside a payload: as a job's field it is state the job made and can change.
 *
 * <p><b>Why a collection in a payload is accepted on trust.</b> {@code List<String>} might be an {@code ArrayList} its
 * sender still holds, and the type cannot say. Refusing it made the template send "close these five tabs" as five
 * messages with a count, a protocol written around the checker rather than the problem (ROADMAP fork 6, ruled
 * 2026-10-26 and built 2026-10-28). Unmodifiable is the sender's part, kept by building it with {@code List.copyOf};
 * a record that copies in its compact constructor keeps it for every sender.
 */
final class Shareable {

    private static final Set<String> VALUES = Set.of(
            "java.lang.String", "java.lang.Boolean", "java.lang.Byte", "java.lang.Short", "java.lang.Character",
            "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double",
            "java.math.BigInteger", "java.math.BigDecimal", "java.util.UUID", "java.net.URI", "java.nio.file.Path",
            "java.time.Instant", "java.time.Duration", "java.time.LocalDate", "java.time.LocalTime",
            "java.time.LocalDateTime", "java.time.ZonedDateTime", "java.time.OffsetDateTime");

    /** What a payload may hold when what each holds is shareable: {@link #crossing}. */
    private static final Set<String> COLLECTIONS = Set.of("java.util.List", "java.util.Set", "java.util.Map");

    private Shareable() {
    }

    /**
     * Why a value of this type cannot cross a lane as itself, as a path from the payload to the first thing that
     * stops it, or {@code null} when it can. For state that stays, a job's field: a collection there is refused,
     * because it is one the holder made and can change.
     */
    static String why(TypeMirror type) {
        return why(type, "", new HashSet<>(), Rules.STATE);
    }

    /**
     * Why a payload of this type cannot cross, as {@link #why(TypeMirror)}, except that a {@code List}, {@code Set} or
     * {@code Map} inside it is accepted when what it holds is shareable (ruling 6, ROADMAP fork 6). The type cannot
     * show that the collection itself is unmodifiable, so that part is the sender's: build it with {@code List.of} or
     * {@code List.copyOf}, as a record does in its compact constructor. That is rule 4 of {@code docs/plans/chains.md}:
     * the check refuses what is certainly wrong, and a collection of shareable values is not.
     */
    static String crossing(TypeMirror type) {
        return why(type, "", new HashSet<>(), Rules.PAYLOAD);
    }

    /**
     * Why a view's state, the one its {@code @Draw} method is asked with, is not deeply immutable; as {@link #crossing},
     * except that a vexelray-gui {@code Node} in it is a <b>handle</b>, accepted: the nodes the told half made and the
     * asked half addresses through its {@code Drawn} (frame-contract.md B). It never crosses a lane, and the step check
     * refuses calling anything on it in the asked half, so all the asked half can do with one is name it.
     */
    static String drawnState(TypeMirror type) {
        return why(type, "", new HashSet<>(), Rules.DRAWN);
    }

    /** What the walk accepts beyond the deeply immutable: collections on trust (a payload's), and nodes as handles. */
    private record Rules(boolean collections, boolean handles) {
        static final Rules STATE = new Rules(false, false);
        static final Rules PAYLOAD = new Rules(true, false);
        static final Rules DRAWN = new Rules(true, true);
    }

    /** vexelray-gui's node, a final class: what a view's state may hold as a handle. */
    static final String NODE = "dev.vexelray.gui.core.Node";

    private static String why(TypeMirror type, String path, Set<String> visiting, Rules rules) {
        if (type.getKind().isPrimitive()) {
            return null;
        }
        if (type.getKind() == TypeKind.ARRAY) {
            return at(path, type + " is an array, which anyone holding it can change");
        }
        if (type.getKind() != TypeKind.DECLARED) {
            return at(path, type + " is not a type that can be checked");
        }
        DeclaredType declared = (DeclaredType) type;
        TypeElement element = (TypeElement) declared.asElement();
        String name = element.getQualifiedName().toString();
        if (VALUES.contains(name) || element.getKind() == ElementKind.ENUM || rules.handles() && name.equals(NODE)) {
            return null;
        }
        if (!declared.getTypeArguments().isEmpty()) {
            if (name.equals("java.util.Optional")) {
                return why(declared.getTypeArguments().get(0), path, visiting, rules);
            }
            if (rules.collections() && COLLECTIONS.contains(name)) {
                for (TypeMirror held : declared.getTypeArguments()) {
                    String why = why(held, join(path, Mirrors.simple(element) + " of " + held), visiting, rules);
                    if (why != null) {
                        return why;
                    }
                }
                return null;
            }
            return at(path, type + " has type arguments, and what they hold cannot be proven immutable from the"
                    + " type: a " + Mirrors.simple(element) + " may be a mutable one its sender still holds");
        }
        if (!visiting.add(name)) {
            return null; // a recursive type: the first visit decides it
        }
        try {
            return structure(element, path, visiting, rules);
        } finally {
            visiting.remove(name);
        }
    }

    private static String structure(TypeElement element, String path, Set<String> visiting, Rules rules) {
        String simple = Mirrors.simple(element);
        switch (element.getKind()) {
            case RECORD -> {
                for (RecordComponentElement c : element.getRecordComponents()) {
                    String why = why(c.asType(), join(path, simple + "." + c.getSimpleName()), visiting, rules);
                    if (why != null) {
                        return why;
                    }
                }
                return null;
            }
            case INTERFACE -> {
                if (!element.getModifiers().contains(Modifier.SEALED)) {
                    return at(path, simple + " is an interface that is not sealed, so what implements it is unknown");
                }
                for (TypeMirror permitted : element.getPermittedSubclasses()) {
                    String why = why(permitted, join(path, simple), visiting, rules);
                    if (why != null) {
                        return why;
                    }
                }
                return null;
            }
            case CLASS -> {
                if (element.getModifiers().contains(Modifier.SEALED)) {
                    String why = fields(element, path, visiting, rules);
                    if (why != null) {
                        return why;
                    }
                    for (TypeMirror permitted : element.getPermittedSubclasses()) {
                        why = why(permitted, join(path, simple), visiting, rules);
                        if (why != null) {
                            return why;
                        }
                    }
                    return null;
                }
                if (!element.getModifiers().contains(Modifier.FINAL)) {
                    return at(path, simple + " is neither final nor sealed, so a subclass could add mutable state");
                }
                return fields(element, path, visiting, rules);
            }
            default -> {
                return at(path, simple + " is " + element.getKind().toString().toLowerCase().replace('_', ' ')
                        + ", which cannot be checked");
            }
        }
    }

    /**
     * The instance state of a class: its own fields and every superclass's, up to {@code Object}. Only the class
     * whose finality was checked is asked to be final; a superclass is not, by definition, and what matters about
     * it is what it holds. A class that holds its enclosing instance is refused at any level, because that
     * reference is a field javac never shows.
     */
    private static String fields(TypeElement element, String path, Set<String> visiting, Rules rules) {
        for (TypeElement at = element; at != null; at = superclass(at)) {
            String simple = Mirrors.simple(at);
            if (holdsItsMaker(at)) {
                return at(join(path, simple), "is an inner class, so it holds a reference to the instance that"
                        + " made it, which the sender still has. Declare it static");
            }
            for (VariableElement f : ElementFilter.fieldsIn(at.getEnclosedElements())) {
                if (f.getModifiers().contains(Modifier.STATIC)) {
                    continue;
                }
                String where = join(path, simple + "." + f.getSimpleName());
                if (!f.getModifiers().contains(Modifier.FINAL)) {
                    return at(where, "is not final, so it can change after the value has been sent");
                }
                String why = why(f.asType(), where, visiting, rules);
                if (why != null) {
                    return why;
                }
            }
        }
        return null;
    }

    /** The superclass, or {@code null} at {@code Object}. */
    private static TypeElement superclass(TypeElement element) {
        TypeMirror parent = element.getSuperclass();
        if (parent.getKind() != TypeKind.DECLARED) {
            return null;
        }
        TypeElement up = (TypeElement) ((DeclaredType) parent).asElement();
        return up.getQualifiedName().contentEquals("java.lang.Object") ? null : up;
    }

    /** A non-static member class, or a local or anonymous one: it carries its enclosing instance or captures. */
    private static boolean holdsItsMaker(TypeElement element) {
        NestingKind nesting = element.getNestingKind();
        return nesting == NestingKind.LOCAL || nesting == NestingKind.ANONYMOUS
                || nesting == NestingKind.MEMBER && !element.getModifiers().contains(Modifier.STATIC);
    }

    private static String join(String path, String step) {
        return path.isEmpty() ? step : path + " -> " + step;
    }

    private static String at(String path, String reason) {
        return path.isEmpty() ? reason : path + ": " + reason;
    }
}
