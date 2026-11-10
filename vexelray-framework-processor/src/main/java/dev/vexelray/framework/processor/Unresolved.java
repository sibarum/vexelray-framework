package dev.vexelray.framework.processor;

import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Destination;
import dev.vexelray.framework.api.Job;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.api.Setting;
import dev.vexelray.framework.api.Source;
import dev.vexelray.framework.api.View;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.tools.Diagnostic;
import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The types the wiring reads that do not resolve, said once each before anything reads them (Rasterware FN-30).
 *
 * <p>The usual cause is a module of the application's own that is older than this one's sources: {@code -gui} compiled
 * alone after {@code -core} gained a message, against the {@code -core} in the local repository. javac reports the
 * missing symbol only after the processors have run, and every check here that met the type before then either crashed
 * on it (an NPE naming nothing) or said something about it that was not so (a cycle, a value, a type that cannot cross a
 * lane). So the round is read for them first: each signature the wiring reads that names one is reported, with what
 * usually fixes it, and nothing else is checked or written for this build. javac names the symbol beside it.
 *
 * <p>Only what the wiring reads: a part's members (its constructor, its {@code @Subscribe} and {@code @Draw} methods, its
 * state), a {@code @Provides} method, a {@code @Setting}. A type that does not resolve anywhere else is javac's alone.
 */
final class Unresolved {

    private static final List<Class<? extends Annotation>> PARTS =
            List.of(Component.class, View.class, Job.class, Source.class, Destination.class);

    private final Report report;

    Unresolved(Report report) {
        this.report = report;
    }

    /** Report every signature in {@code round} the wiring reads that names a type that does not resolve; whether any. */
    boolean report(RoundEnvironment round) {
        Set<Element> read = new LinkedHashSet<>();
        for (Class<? extends Annotation> part : PARTS) {
            for (Element e : round.getElementsAnnotatedWith(part)) {
                if (e instanceof TypeElement type) {
                    for (Element member : type.getEnclosedElements()) {
                        if (member.getKind() == ElementKind.CONSTRUCTOR || member.getKind() == ElementKind.METHOD
                                || member.getKind() == ElementKind.FIELD) {
                            read.add(member);
                        }
                    }
                }
            }
        }
        read.addAll(round.getElementsAnnotatedWith(Provides.class));
        read.addAll(round.getElementsAnnotatedWith(Setting.class));
        boolean any = false;
        for (Element e : read) {
            Set<String> names = new LinkedHashSet<>();
            if (e instanceof ExecutableElement method) {
                missing(method.getReturnType(), names);
                for (VariableElement p : method.getParameters()) {
                    missing(p.asType(), names);
                }
            } else {
                missing(e.asType(), names);
            }
            if (!names.isEmpty()) {
                any = true;
                report.printMessage(Diagnostic.Kind.ERROR, where(e) + " names " + String.join(", ", names) + ", which "
                        + (names.size() == 1 ? "does" : "do") + " not resolve, so the wiring is neither checked nor "
                        + "written in this build. When it is declared in another module of this application, that module "
                        + "is older than these sources: build it first (from the parent, mvn compile, or -pl <this "
                        + "module> -am), rather than this module alone against the one in the local repository", e);
            }
        }
        return any;
    }

    /** The names of the types in {@code type} that did not resolve, as the source spelt them. */
    private static void missing(TypeMirror type, Set<String> into) {
        if (type == null) {
            return;
        }
        if (type.getKind() == TypeKind.ERROR) {
            into.add(type.toString());
        } else if (type.getKind() == TypeKind.DECLARED) {
            for (TypeMirror argument : ((DeclaredType) type).getTypeArguments()) {
                missing(argument, into);
            }
        } else if (type.getKind() == TypeKind.ARRAY) {
            missing(((ArrayType) type).getComponentType(), into);
        } else if (type.getKind() == TypeKind.WILDCARD) {
            missing(((WildcardType) type).getExtendsBound(), into);
            missing(((WildcardType) type).getSuperBound(), into);
        }
    }

    private static String where(Element e) {
        if (e instanceof ExecutableElement method) {
            return method.getKind() == ElementKind.CONSTRUCTOR
                    ? Mirrors.simple(method.getEnclosingElement()) + "'s constructor"
                    : Mirrors.where(method);
        }
        return Mirrors.simple(e.getEnclosingElement()) + "." + Mirrors.simple(e);
    }
}
