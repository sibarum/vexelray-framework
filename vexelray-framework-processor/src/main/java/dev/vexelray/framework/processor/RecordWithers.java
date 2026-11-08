package dev.vexelray.framework.processor;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Writes the interface of withers a {@code @Withers} record implements: one default {@code with<Component>} per
 * component, each a call to the canonical constructor with that one argument replaced (the annotation's javadoc, in
 * {@code vexelray-framework-api}, is the contract).
 *
 * <p>Written in the round the record is seen, so javac compiles it with the record. The record names the interface
 * before it exists, which javac allows while annotations are processed and resolves once this has written it; a
 * record that names something else, or nothing, is told what to write rather than left to an unresolved symbol.
 */
final class RecordWithers {

    private final Messager report;
    private final Filer filer;

    RecordWithers(Messager report, Filer filer) {
        this.report = report;
        this.filer = filer;
    }

    /** Check {@code type} and write its interface, or say why not. */
    void write(TypeElement type) {
        if (type.getKind() != ElementKind.RECORD) {
            error(type, "@Withers is on " + type.getSimpleName() + ", which is not a record. Withers copy a record"
                    + " with one component replaced, and only a record has components to copy");
            stub(type);
            return;
        }
        for (Element e = type; e instanceof TypeElement t; e = t.getEnclosingElement()) {
            if (t.getModifiers().contains(Modifier.PRIVATE)) {
                error(type, "@Withers on " + type.getSimpleName() + ": " + (t == type ? "it is" : t.getSimpleName()
                        + ", which encloses it, is") + " private, so the interface written beside it cannot name it."
                        + " Make it package-private");
                stub(type);
                return;
            }
        }
        if (!type.getTypeParameters().isEmpty()) {
            error(type, "@Withers on " + type.getSimpleName() + ": withers for a generic record are not written yet."
                    + " A ledger's state is usually not generic; if this one has to be, write its withers by hand");
            stub(type);
            return;
        }
        String name = interfaceName(type);
        if (!implementsByName(type, name)) {
            error(type, "@Withers on " + type.getSimpleName() + ": it does not implement " + name + ", the"
                    + " interface of withers written beside it. Add `implements " + name + "` to the record");
            // Written anyway, so the one message is this and not an unresolved symbol as well.
        }
        String pkg = packageOf(type).getQualifiedName().toString();
        String record = type.getQualifiedName().toString();
        List<? extends RecordComponentElement> components = type.getRecordComponents();

        StringBuilder src = new StringBuilder();
        if (!pkg.isEmpty()) {
            src.append("package ").append(pkg).append(";\n\n");
        }
        src.append("/**\n")
                .append(" * The withers of {@link ").append(record).append("}: one per component, each a copy with that\n")
                .append(" * component replaced. Written by the processor for its {@code @Withers}; never edit it.\n")
                .append(" */\n")
                .append("@javax.annotation.processing.Generated(\"dev.vexelray.framework.processor.VexelProcessor\")\n")
                .append(type.getModifiers().contains(Modifier.PUBLIC) && publicAllTheWay(type) ? "public " : "")
                .append("interface ").append(name).append(" {\n");
        for (RecordComponentElement c : components) {
            src.append("\n    ").append(typeName(c.asType())).append(' ').append(c.getSimpleName()).append("();\n");
        }
        for (RecordComponentElement c : components) {
            String field = c.getSimpleName().toString();
            src.append("\n    /** This, with {@code ").append(field).append("} replaced by {@code value}. */\n")
                    .append("    default ").append(record).append(" with").append(capitalised(field)).append('(')
                    .append(typeName(c.asType())).append(" value) {\n")
                    .append("        return new ").append(record).append('(');
            for (int i = 0; i < components.size(); i++) {
                RecordComponentElement other = components.get(i);
                src.append(i == 0 ? "" : ", ")
                        .append(other == c ? "value" : other.getSimpleName() + "()");
            }
            src.append(");\n    }\n");
        }
        src.append("}\n");

        emit(type, pkg, name, src.toString());
    }

    /**
     * An empty interface by the name a refused record would have implemented, so the refusal is the one error and not
     * followed by an unresolved symbol for the {@code implements} the record already wrote.
     */
    private void stub(TypeElement type) {
        String pkg = packageOf(type).getQualifiedName().toString();
        String name = interfaceName(type);
        emit(type, pkg, name, (pkg.isEmpty() ? "" : "package " + pkg + ";\n\n")
                + "/** Empty: the processor refused this record's {@code @Withers}, and said why. */\n"
                + "@javax.annotation.processing.Generated(\"dev.vexelray.framework.processor.VexelProcessor\")\n"
                + "interface " + name + " {\n}\n");
    }

    private void emit(TypeElement type, String pkg, String name, String source) {
        String qualified = pkg.isEmpty() ? name : pkg + "." + name;
        try (Writer out = filer.createSourceFile(qualified, type).openWriter()) {
            out.write(source);
        } catch (IOException e) {
            error(type, "could not write " + qualified + ": " + e.getMessage());
        }
    }

    /** {@code Editor.State} → {@code EditorStateWithers}: the enclosing types outermost first, the record, Withers. */
    static String interfaceName(TypeElement type) {
        StringBuilder name = new StringBuilder("Withers");
        for (Element e = type; e instanceof TypeElement t; e = t.getEnclosingElement()) {
            name.insert(0, t.getSimpleName());
        }
        return name.toString();
    }

    /**
     * Whether the record lists {@code name} among what it implements. Read by name, because in the round that writes
     * the interface it does not exist yet, and javac shows it as an error type spelled as the source spelled it.
     */
    private static boolean implementsByName(TypeElement type, String name) {
        for (TypeMirror i : type.getInterfaces()) {
            String written = i.toString();
            if (written.equals(name) || written.endsWith("." + name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean publicAllTheWay(TypeElement type) {
        for (Element e = type; e instanceof TypeElement t; e = t.getEnclosingElement()) {
            if (!t.getModifiers().contains(Modifier.PUBLIC)) {
                return false;
            }
        }
        return true;
    }

    private static PackageElement packageOf(Element e) {
        while (!(e instanceof PackageElement)) {
            e = e.getEnclosingElement();
        }
        return (PackageElement) e;
    }

    /** As source spells it, fully qualified, so the interface needs no imports. */
    private static String typeName(TypeMirror type) {
        return type.toString();
    }

    private static String capitalised(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void error(Element e, String message) {
        report.printMessage(Diagnostic.Kind.ERROR, message, e);
    }
}
