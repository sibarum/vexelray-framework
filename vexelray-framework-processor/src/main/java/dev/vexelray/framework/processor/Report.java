package dev.vexelray.framework.processor;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

/**
 * The compiler's {@code Messager}, counting its errors.
 *
 * <p>So that generation can refuse to run over a program the checks have already rejected. A wiring generated
 * from a graph with an error in it would add a second wave of messages about code nobody wrote, and bury the one
 * that says what is actually wrong.
 */
final class Report implements Messager {

    private final Messager compiler;
    private int errors;

    Report(Messager compiler) {
        this.compiler = compiler;
    }

    /** Whether anything has been reported as an error so far. */
    boolean failed() {
        return errors > 0;
    }

    @Override
    public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
        count(kind);
        compiler.printMessage(kind, msg);
    }

    @Override
    public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) {
        count(kind);
        compiler.printMessage(kind, msg, e);
    }

    @Override
    public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) {
        count(kind);
        compiler.printMessage(kind, msg, e, a);
    }

    @Override
    public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a,
                             AnnotationValue v) {
        count(kind);
        compiler.printMessage(kind, msg, e, a, v);
    }

    private void count(Diagnostic.Kind kind) {
        if (kind == Diagnostic.Kind.ERROR) {
            errors++;
        }
    }
}
