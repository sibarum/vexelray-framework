package dev.vexelray.framework.processor;

import dev.vexelray.framework.api.BeforeFrame;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.ConditionalOnType;
import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Default;
import dev.vexelray.framework.api.MainThread;
import dev.vexelray.framework.api.OnMode;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.api.Setting;
import dev.vexelray.framework.api.VexelApp;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads {@code vexelray-framework-api}'s vocabulary while an application compiles, and turns what its Javadoc
 * calls a compile error into one.
 *
 * <h2>Checks, then the wiring</h2>
 *
 * <p>Two halves, in that order, and the second runs only if the first reported nothing — a wiring generated from a
 * graph with an error in it would bury the one message that matters under ones about code nobody wrote. The
 * second is {@link Generator}: where the compilation has a {@code @VexelApp}, it writes that application's
 * {@code Wiring}. The first is every check that can be decided from declarations alone — which is most of what the
 * vocabulary promises, and all of the colour rule that does not depend on a copier:
 *
 * <ul>
 *   <li><b>T2.2</b> — a main-thread value, by its type's {@code @MainThread} or by a {@code @MainThread}
 *       provider's, injected into a component or into a provider whose value is not main-thread.</li>
 *   <li><b>T2.3</b> — one component holding another on a different lane.</li>
 *   <li><b>T3.7</b> — a provider holding a component, which would carry it to anyone who asks.</li>
 *   <li><b>T2.1</b> — a component that is also {@code @MainThread}.</li>
 *   <li>Two providers for one type in one mode, with {@code @Default}'s back-off and {@code @OnMode}'s and
 *       {@code @ConditionalOnType}'s guards taken into account.</li>
 *   <li>The shapes: one non-private constructor, a {@code @Provides} inside a configuration returning an
 *       interface when the type is this build's own, a {@code @Setting} whose type has an accessor and whose
 *       default parses, a {@code @BeforeFrame} that takes nothing, throws nothing, and is not a component's,
 *       starters that are configurations, and one {@code @VexelApp}.</li>
 * </ul>
 *
 * <p><b>Why checks came first.</b> {@code docs/threading.md} counted fourteen of its thirty-two rules as the
 * processor's, and four of them — T2.1, T2.2, T2.3, T3.7 — needed no generated code, only something reading the
 * declarations. Generation freezes what the annotations mean into every application; checking does not, so it
 * is the half that can go first without committing the other.
 *
 * <p><b>Explicit, not discovered.</b> Since JDK 23 javac runs no processor it merely finds on the class path,
 * so an application names this one on its {@code annotationProcessorPaths}. That is the house position on
 * implicit behaviour anyway: <i>"a default is not a choice anyone can read."</i>
 */
public final class VexelProcessor extends AbstractProcessor {

    /** Everything read, by class literal, so a renamed annotation fails to compile here rather than to match. */
    private static final List<Class<? extends Annotation>> VOCABULARY = List.of(
            VexelApp.class, Component.class, Configuration.class, Provides.class, Default.class,
            MainThread.class, Setting.class, BeforeFrame.class, OnMode.class, ConditionalOnType.class);

    private Mirrors mirrors;
    private Declarations declarations;
    private Graph graph;
    private Generator generator;
    private TypeElement app;
    private Report report;
    private boolean checked;
    private final Set<ExecutableElement> hooks = new LinkedHashSet<>();

    @Override
    public void init(ProcessingEnvironment env) {
        super.init(env);
        Report report = new Report(env.getMessager());
        mirrors = new Mirrors(env.getElementUtils());
        declarations = new Declarations(mirrors, report);
        graph = new Graph(mirrors, declarations, env.getElementUtils(), env.getTypeUtils(), report);
        generator = new Generator(mirrors, declarations, graph, env.getElementUtils(), env.getTypeUtils(), report,
                env.getFiler());
        this.report = report;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> names = new LinkedHashSet<>();
        for (Class<? extends Annotation> a : VOCABULARY) {
            names.add(a.getCanonicalName());
        }
        return names;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (Element e : round.getElementsAnnotatedWith(VexelApp.class)) {
            app((TypeElement) e);
        }
        for (Element e : round.getElementsAnnotatedWith(Component.class)) {
            declarations.component((TypeElement) e);
            graph.component((TypeElement) e);
        }
        for (Element e : round.getElementsAnnotatedWith(Configuration.class)) {
            declarations.configuration((TypeElement) e);
            graph.configuration((TypeElement) e);
        }
        for (Element e : round.getElementsAnnotatedWith(Provides.class)) {
            declarations.provides((ExecutableElement) e);
        }
        for (Element e : round.getElementsAnnotatedWith(Default.class)) {
            declarations.orphanDefault((ExecutableElement) e);
        }
        for (Element e : round.getElementsAnnotatedWith(Setting.class)) {
            declarations.setting((VariableElement) e);
        }
        for (Element e : round.getElementsAnnotatedWith(BeforeFrame.class)) {
            declarations.beforeFrame((ExecutableElement) e);
            hooks.add((ExecutableElement) e);
        }
        // Generated in the round the application is seen, not the last one: javac compiles nothing created in
        // the final round, and the application's main names the wiring. Every source of a clean build is in the
        // first round, so the whole program is already here; a starter is a class file, resolved by name.
        if (app != null && !checked) {
            checked = true;
            graph.check();
            if (!report.failed()) {
                generator.generate(app, hooks);
            }
        } else if (round.processingOver() && !checked) {
            // A library -- a starter compiled on its own. Checked, and there is nothing to generate for.
            checked = true;
            graph.check();
        }
        return false;
    }

    /** The one application, and the starters it names — resolved by class literal, never searched for. */
    private void app(TypeElement type) {
        if (app != null) {
            declarations.secondApp(type, app);
            return;
        }
        app = type;
        AnnotationMirror vexelApp = mirrors.find(type, VexelApp.class);
        declarations.starters(type, vexelApp);
        for (TypeMirror starter : mirrors.types(vexelApp, "starters")) {
            TypeElement config = Mirrors.element(starter);
            if (config != null && config.getKind() == ElementKind.CLASS && mirrors.has(config, Configuration.class)) {
                graph.configuration(config);
            }
        }
    }
}
