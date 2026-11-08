package dev.vexelray.framework.processor;

import dev.vexelray.framework.api.BeforeFrame;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.ConditionalOnType;
import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Default;
import dev.vexelray.framework.api.Draw;
import dev.vexelray.framework.api.Job;
import dev.vexelray.framework.api.MainThread;
import dev.vexelray.framework.api.OnMode;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.api.Publishes;
import dev.vexelray.framework.api.Setting;
import dev.vexelray.framework.api.Destination;
import dev.vexelray.framework.api.Source;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.api.VexelApp;
import dev.vexelray.framework.api.View;
import dev.vexelray.framework.api.Hears;
import dev.vexelray.framework.api.Withers;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
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
 *   <li><b>T7.1</b> — no application code on the main thread: {@code @BeforeFrame} and {@code @MainThread} are
 *       refused, and so is a part taking a main-thread value (T2.2), save a view.</li>
 *   <li><b>T2.3</b> — one component holding another, on any lane.</li>
 *   <li><b>T3.7</b> — a provider holding a component, which would carry it to anyone who asks.</li>
 *   <li><b>T2.5, T4.5</b> — the raw bus, a lane or an executor handed to an application part: a part sends by
 *       taking a {@code Sender<T>}, and runs where the framework puts it.</li>
 *   <li><b>T4.1</b> — every topic declared in source says its kind ({@code Topic.edge} or {@code Topic.state}), and
 *       every topic a part hears or sends is such a constant, found by its payload ({@link Channels}).</li>
 *   <li><b>T2.6, T2.7</b> — a provided value a component takes is shareable or a framework service, and a
 *       component's fields ask for no synchronisation.</li>
 *   <li>Two providers for one type in one mode, with {@code @Default}'s back-off and {@code @OnMode}'s and
 *       {@code @ConditionalOnType}'s guards taken into account.</li>
 *   <li>A {@code @Job}'s shape and its two messages, and the components and jobs a configuration lists, which is
 *       how one compiled in another module reaches the wiring at all.</li>
 *   <li>The shapes: one non-private constructor, a {@code @Provides} inside a configuration returning an
 *       interface when the type is this build's own, a {@code @Setting} whose type has an accessor and whose
 *       default parses, starters that are configurations, and one {@code @VexelApp}.</li>
 *   <li><b>A ledger's steps</b> ({@link StepCheck}): what is certainly wrong in a step's body, a capture, a static
 *       written, a lock, a known effect or wait. Read from bodies rather than declarations, in every class of the
 *       compilation once javac has analyzed it, so the processor asks for {@code *} to be started at all.</li>
 *   <li><b>A view's {@code @Draw}</b>: its shape and its one state field, and its body read as a step's is, with a
 *       frame's rules besides (T7.6).</li>
 * </ul>
 *
 * <p>Beside the wiring it writes one thing more, wherever a record asks for it: a {@code @Withers} record's interface
 * of withers ({@link RecordWithers}), in any module, with or without an application.
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
            MainThread.class, Setting.class, BeforeFrame.class, OnMode.class, ConditionalOnType.class,
            Subscribe.class, Publishes.class, Job.class, Source.class, Destination.class, View.class, Hears.class,
            Draw.class, Withers.class);

    private Mirrors mirrors;
    private Declarations declarations;
    private Graph graph;
    private Channels channels;
    private Generator generator;
    private RecordWithers withers;
    private TypeElement app;
    private Report report;
    private boolean checked;
    /** Every component and job read so far, so one a round offers and a configuration also lists is checked once. */
    private final Set<TypeElement> parts = new LinkedHashSet<>();

    @Override
    public void init(ProcessingEnvironment env) {
        super.init(env);
        Report report = new Report(env.getMessager());
        mirrors = new Mirrors(env.getElementUtils());
        declarations = new Declarations(mirrors, report);
        graph = new Graph(mirrors, declarations, env.getElementUtils(), env.getTypeUtils(), report);
        channels = new Channels(mirrors, env.getElementUtils(), report, new Topics(env));
        generator = new Generator(mirrors, declarations, graph, channels, env.getElementUtils(), env.getTypeUtils(),
                report,
                env.getFiler());
        this.report = report;
        withers = new RecordWithers(report, env.getFiler());
        StepCheck.install(env, report);
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> names = new LinkedHashSet<>();
        for (Class<? extends Annotation> a : VOCABULARY) {
            names.add(a.getCanonicalName());
        }
        // And every other source, for the step check: a step is a builder call that no annotation marks, a library
        // of ledgers may have none of the vocabulary, and javac starts only a processor that something asks for.
        // Claiming nothing (process returns false) leaves every annotation to whoever else reads it.
        names.add("*");
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
            component((TypeElement) e);
        }
        for (Element e : round.getElementsAnnotatedWith(View.class)) {
            component((TypeElement) e);
        }
        for (Element e : round.getElementsAnnotatedWith(Hears.class)) {
            if (!mirrors.has(e, Component.class)) {
                report.printMessage(javax.tools.Diagnostic.Kind.ERROR, "@Hears is on " + e.getSimpleName()
                        + ", which is not a @Component. It lists what a component hears through its inbox on the logic"
                        + " lane", e);
            }
        }
        for (Element e : round.getElementsAnnotatedWith(Job.class)) {
            job((TypeElement) e);
        }
        for (Element e : round.getElementsAnnotatedWith(Source.class)) {
            source((TypeElement) e);
        }
        for (Element e : round.getElementsAnnotatedWith(Destination.class)) {
            destination((TypeElement) e);
        }
        for (Element e : round.getElementsAnnotatedWith(Configuration.class)) {
            declarations.configuration((TypeElement) e);
            graph.configuration((TypeElement) e);
            listed((TypeElement) e);
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
        }
        for (Element e : round.getElementsAnnotatedWith(MainThread.class)) {
            declarations.mainThread(e);
        }
        for (Element e : round.getElementsAnnotatedWith(Subscribe.class)) {
            declarations.subscribe((ExecutableElement) e);
        }
        for (Element e : round.getElementsAnnotatedWith(Draw.class)) {
            declarations.draw((ExecutableElement) e);
        }
        for (Element e : round.getElementsAnnotatedWith(Publishes.class)) {
            channels.publishes(e);
        }
        // Written in the round the record is seen, so javac compiles the interface with it (RecordWithers).
        for (Element e : round.getElementsAnnotatedWith(Withers.class)) {
            withers.write((TypeElement) e);
        }
        for (Element e : round.getRootElements()) {
            if (e instanceof TypeElement type) {
                topics(type);
            }
        }
        // Generated in the round the application is seen, not the last one: javac compiles nothing created in
        // the final round, and the application's main names the wiring. Every source of a clean build is in the
        // first round, so the whole program is already here; a starter is a class file, resolved by name.
        if (app != null && !checked) {
            checked = true;
            graph.check(true);
            channels.check(graph, true);
            if (!report.failed()) {
                generator.generate(app);
            }
        } else if (round.processingOver() && !checked) {
            // A library -- a starter compiled on its own. Checked, and there is nothing to generate for.
            checked = true;
            graph.check(false);
            channels.check(graph, false);
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
        declarations.icon(type, vexelApp, processingEnv.getFiler());
        declarations.names(type, vexelApp, processingEnv.getFiler());
        for (TypeMirror starter : mirrors.types(vexelApp, "starters")) {
            TypeElement config = Mirrors.element(starter);
            if (config != null && config.getKind() == ElementKind.CLASS && mirrors.has(config, Configuration.class)) {
                graph.configuration(config);
                listed(config);
            }
        }
    }

    /**
     * The components and jobs a configuration lists. One compiled in another module arrives as a class file, which no
     * round offers, so this is the only way the wiring hears of it; its {@code @Publishes} are read off it here for the
     * same reason. One compiled beside the configuration is offered by the round as well, and is read once.
     */
    private void listed(TypeElement configuration) {
        AnnotationMirror mirror = mirrors.find(configuration, Configuration.class);
        declarations.listed(configuration, mirror);
        for (TypeMirror type : mirrors.types(mirror, "components")) {
            TypeElement c = Mirrors.element(type);
            // Not a component is Declarations.listed's to say; it is not read as one.
            if (c != null && mirrors.has(c, Component.class) && component(c) && !mirrors.owned(c)) {
                publishers(c);
            }
        }
        for (TypeMirror type : mirrors.types(mirror, "jobs")) {
            TypeElement j = Mirrors.element(type);
            if (j != null && mirrors.has(j, Job.class)) {
                job(j);
            }
        }
        for (TypeMirror type : mirrors.types(mirror, "sources")) {
            TypeElement s = Mirrors.element(type);
            if (s != null && mirrors.has(s, Source.class)) {
                source(s);
            }
        }
        for (TypeMirror type : mirrors.types(mirror, "destinations")) {
            TypeElement d = Mirrors.element(type);
            if (d != null && mirrors.has(d, Destination.class)) {
                destination(d);
            }
        }
    }

    /** Read a component once; whether this was the first time. */
    private boolean component(TypeElement type) {
        if (!parts.add(type)) {
            return false;
        }
        declarations.component(type);
        graph.component(type);
        return true;
    }

    private void job(TypeElement type) {
        if (parts.add(type)) {
            declarations.job(type);
            graph.job(type);
        }
    }

    /** A source is read as a job is, and marked as one: see {@code Graph.source}. */
    private void source(TypeElement type) {
        if (mirrors.has(type, Destination.class)) {
            destination(type);
            return;
        }
        if (parts.add(type)) {
            declarations.source(type);
            graph.source(type);
        }
    }

    /** A destination is read as a job is, and marked as one, a source too when it is both: see {@code Graph.destination}. */
    private void destination(TypeElement type) {
        if (parts.add(type)) {
            declarations.destination(type);
            graph.destination(type);
        }
    }

    /** Every {@code static final Topic} declared in {@code type} and the types it encloses, judged for its kind (T4.1). */
    private void topics(TypeElement type) {
        for (Element member : type.getEnclosedElements()) {
            if (member instanceof TypeElement nested) {
                topics(nested);
            } else if (member.getKind() == ElementKind.FIELD && member.getModifiers().contains(Modifier.STATIC)
                    && member.getModifiers().contains(Modifier.FINAL)
                    && member.asType() instanceof javax.lang.model.type.DeclaredType d
                    && Mirrors.element(d).getQualifiedName().contentEquals("sibarum.atchung.Topic")) {
                channels.kind((VariableElement) member);
            }
        }
    }

    /** The {@code @Publishes} on a type read from a class file, and on its methods: the sends a round would offer. */
    private void publishers(TypeElement type) {
        if (mirrors.has(type, Publishes.class)) {
            channels.publishes(type);
        }
        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() == ElementKind.METHOD && mirrors.has(member, Publishes.class)) {
                channels.publishes(member);
            }
        }
    }
}
