package dev.vexelray.framework.processor;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reading the vocabulary off an element, by mirror.
 *
 * <p><b>Mirrors rather than {@code Element.getAnnotation}</b>, uniformly, and the reason is one member:
 * {@code VexelApp.starters()} is a {@code Class<?>[]}, and asking a proxy for a class that exists only as source
 * throws {@code MirroredTypesException} — the documented way to get the type is to catch the exception. One way
 * of reading annotations for all of them is cheaper to trust than two, one of them exceptional.
 *
 * <p>An annotation is matched by the canonical name of its class literal, so a renamed annotation in {@code -api}
 * is a compile error here rather than a string that silently stops matching.
 */
final class Mirrors {

    private final Elements elements;

    Mirrors(Elements elements) {
        this.elements = elements;
    }

    /** The mirror of {@code annotation} on {@code element}, or {@code null} when it is not there. */
    AnnotationMirror find(Element element, Class<? extends Annotation> annotation) {
        String name = annotation.getCanonicalName();
        for (AnnotationMirror m : element.getAnnotationMirrors()) {
            TypeElement type = (TypeElement) m.getAnnotationType().asElement();
            if (type.getQualifiedName().contentEquals(name)) {
                return m;
            }
        }
        return null;
    }

    boolean has(Element element, Class<? extends Annotation> annotation) {
        return find(element, annotation) != null;
    }

    /** A member's value, with the annotation's default filled in when the use site left it out. */
    Object value(AnnotationMirror mirror, String member) {
        Map<? extends ExecutableElement, ? extends AnnotationValue> values =
                elements.getElementValuesWithDefaults(mirror);
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : values.entrySet()) {
            if (e.getKey().getSimpleName().contentEquals(member)) {
                return e.getValue().getValue();
            }
        }
        return null;
    }

    String string(AnnotationMirror mirror, String member) {
        return (String) value(mirror, member);
    }

    /** The {@code String[]} member as a list. */
    List<String> strings(AnnotationMirror mirror, String member) {
        List<String> out = new ArrayList<>();
        for (AnnotationValue v : list(mirror, member)) {
            out.add((String) v.getValue());
        }
        return out;
    }

    /** The {@code Class<?>[]} member, as the types it names. */
    List<TypeMirror> types(AnnotationMirror mirror, String member) {
        List<TypeMirror> out = new ArrayList<>();
        for (AnnotationValue v : list(mirror, member)) {
            out.add((TypeMirror) v.getValue());
        }
        return out;
    }

    /** The enum-array member, as the constants' names. */
    Set<String> constants(AnnotationMirror mirror, String member) {
        Set<String> out = new LinkedHashSet<>();
        for (AnnotationValue v : list(mirror, member)) {
            out.add(((VariableElement) v.getValue()).getSimpleName().toString());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<? extends AnnotationValue> list(AnnotationMirror mirror, String member) {
        Object v = value(mirror, member);
        return v == null ? List.of() : (List<? extends AnnotationValue>) v;
    }

    /**
     * Whether the element was compiled from source in this build, rather than read from a class file.
     *
     * <p>Sound because the build is always a clean one: a module's own types are all source in the compilation
     * that checks them, so a class file is always somebody else's.
     */
    boolean owned(Element element) {
        FileObject file = elements.getFileObjectOf(element);
        return file instanceof JavaFileObject java && java.getKind() == JavaFileObject.Kind.SOURCE;
    }

    /** The type element a type names, or {@code null} for a primitive, an array, a type variable. */
    static TypeElement element(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return null;
        }
        return (TypeElement) ((DeclaredType) type).asElement();
    }

    /** The simple name, for a message a reader can scan. */
    static String simple(Element element) {
        return element.getSimpleName().toString();
    }

    /** {@code Type.method}, the way a reader would look for a provider. */
    static String where(ExecutableElement method) {
        return simple(method.getEnclosingElement()) + "." + simple(method);
    }
}
