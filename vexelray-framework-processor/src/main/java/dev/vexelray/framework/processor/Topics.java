package dev.vexelray.framework.processor;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.Trees;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a topic is declared: the {@code static final Topic} of a payload, which is how both ends of a channel name it
 * without a string the processor would have to trust (ROADMAP fork 5, {@code docs/threading.md} T4.1 and T4.5).
 *
 * <p><b>The constant is found by its payload.</b> A topic carries one type, and a payload's topic is declared beside
 * it: in the payload's own type ({@code Intent.INTENTS}) or in the type enclosing it ({@code Messages.OPEN_TOPIC} for
 * {@code Messages.Open}, {@code TextField.CHANGED} for {@code TextField.Changed}). So a {@code Sender<Open>} and a
 * {@code @Subscribe} taking an {@code Open} both name {@code Messages.OPEN_TOPIC}, and its kind, state or edge, is the
 * one its declaration chose, for every subscriber alike.
 *
 * <p><b>Every topic declares its kind.</b> A constant this compilation has the source of must be made with
 * {@code Topic.edge} or {@code Topic.state}: {@code Topic.of} is atchung's older spelling of an edge, and reads as if
 * the question had not been asked. Read syntactically, from the declaration's tree, which asks javac for nothing it has
 * not already parsed (see {@link StepCheck} for why attributing a tree during processing is not done). A constant from a
 * class file is the library's, which has no tree here and was its own build's to check.
 */
final class Topics {

    private static final String TOPIC = "sibarum.atchung.Topic";

    private final Types types;
    private final Trees trees;

    Topics(ProcessingEnvironment env) {
        this.types = env.getTypeUtils();
        Trees t;
        try {
            t = Trees.instance(env);
        } catch (IllegalArgumentException | NoClassDefFoundError e) {
            t = null;   // not javac: nothing to read a declaration's tree with, and nothing is refused for it
        }
        this.trees = t;
    }

    /**
     * Every {@code static final Topic<payload>} declared where a payload's topic is declared: in the payload's type,
     * then in the type enclosing it. Empty when there is none; more than one is two channels of one type, which a
     * declaration by payload cannot tell apart.
     */
    List<VariableElement> declared(TypeMirror payload) {
        List<VariableElement> found = new ArrayList<>();
        TypeElement type = Mirrors.element(payload);
        if (type == null) {
            return found;
        }
        collect(type, payload, found);
        if (found.isEmpty() && type.getEnclosingElement() instanceof TypeElement owner) {
            collect(owner, payload, found);
        }
        return found;
    }

    private void collect(TypeElement in, TypeMirror payload, List<VariableElement> into) {
        for (Element e : in.getEnclosedElements()) {
            if (e.getKind() == ElementKind.FIELD && e.getModifiers().contains(Modifier.STATIC)
                    && e.getModifiers().contains(Modifier.FINAL) && e.asType() instanceof DeclaredType t
                    && Mirrors.element(t).getQualifiedName().contentEquals(TOPIC)
                    && t.getTypeArguments().size() == 1 && types.isSameType(t.getTypeArguments().get(0), payload)) {
                into.add((VariableElement) e);
            }
        }
    }

    /**
     * Whether {@code type} is this compilation's own source, which its topics' declarations are held to. A library's
     * type is a class file here, and its topics are its own build's to declare.
     */
    boolean inSource(TypeElement type) {
        return trees != null && trees.getTree(type) != null;
    }

    /** The constant as the generated wiring writes it: {@code pkg.Owner.NAME}. */
    static String expression(VariableElement constant) {
        return ((TypeElement) constant.getEnclosingElement()).getQualifiedName() + "." + constant.getSimpleName();
    }

    /**
     * The topic's name, when it can be read off the declaration: its first argument, a string literal or a
     * {@code static final String} of the type that declares the constant ({@code Topic.edge(OPEN, Open.class)}). Read
     * from the tree without attributing it, so a name built any other way, or one from a class file, is {@code null},
     * and the channel is then matched by its payload, which T4.1 makes equivalent.
     */
    String name(VariableElement constant) {
        if (trees == null || !(trees.getTree(constant) instanceof VariableTree variable)
                || !(variable.getInitializer() instanceof MethodInvocationTree call) || call.getArguments().isEmpty()) {
            return null;
        }
        ExpressionTree first = call.getArguments().get(0);
        if (first instanceof com.sun.source.tree.LiteralTree literal && literal.getValue() instanceof String s) {
            return s;
        }
        if (first instanceof IdentifierTree id) {
            for (Element e : constant.getEnclosingElement().getEnclosedElements()) {
                if (e.getKind() == ElementKind.FIELD && e.getSimpleName().contentEquals(id.getName())
                        && ((VariableElement) e).getConstantValue() instanceof String s) {
                    return s;
                }
            }
        }
        return null;
    }

    /**
     * Why {@code constant} does not declare its kind, or {@code null} when it does, or when there is no source to read
     * (a class file, or a compiler without javac's trees).
     */
    String undeclaredKind(VariableElement constant) {
        if (trees == null) {
            return null;
        }
        Tree tree = trees.getTree(constant);
        if (!(tree instanceof VariableTree variable) || variable.getInitializer() == null) {
            return null;
        }
        ExpressionTree init = variable.getInitializer();
        if (!(init instanceof MethodInvocationTree call)) {
            return null;    // made some other way: not a spelling this can judge
        }
        String method;
        ExpressionTree select = call.getMethodSelect();
        if (select instanceof MemberSelectTree member) {
            if (!member.getExpression().toString().endsWith("Topic")) {
                return null;
            }
            method = member.getIdentifier().toString();
        } else if (select instanceof IdentifierTree id) {
            method = id.getName().toString();   // a static import
        } else {
            return null;
        }
        return method.equals("of") ? "it is made with Topic.of, which does not say whether the channel is state or edge"
                : null;
    }

    /** Whether {@code constant} is read, from its source, to be made with {@code Topic.edge} or {@code Topic.of}. */
    boolean readAsEdge(VariableElement constant) {
        if (trees == null || !(trees.getTree(constant) instanceof VariableTree variable)
                || !(variable.getInitializer() instanceof MethodInvocationTree call)) {
            return false;
        }
        ExpressionTree select = call.getMethodSelect();
        String method = select instanceof MemberSelectTree member ? member.getIdentifier().toString()
                : select instanceof IdentifierTree id ? id.getName().toString() : "";
        return method.equals("edge") || method.equals("of");
    }
}
