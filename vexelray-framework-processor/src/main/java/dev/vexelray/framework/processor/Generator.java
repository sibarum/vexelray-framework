package dev.vexelray.framework.processor;

import dev.vexelray.framework.api.BeforeFrame;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.OnMode;
import dev.vexelray.framework.api.RunMode;
import dev.vexelray.framework.api.Setting;
import dev.vexelray.framework.api.VexelApp;
import dev.vexelray.framework.core.Phase;

import javax.annotation.processing.Filer;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes the application's wiring: one {@code Wiring} subclass, named after the {@code @VexelApp} type and beside
 * it, that builds every live provider and component in the phase its parameters put it in.
 *
 * <h2>What it decides, and where each decision was already written</h2>
 *
 * <ul>
 *   <li><b>Phase is inferred, never declared</b> ({@code Component}, {@code Phase}): the latest phase of anything a
 *       part takes. The framework's own values carry the phase {@code Shell} hands them out from
 *       ({@link Framework}); a part that takes nothing is built in {@link Phase#CONFIG}.</li>
 *   <li><b>One provider wins per type per mode</b> ({@code Default}), which {@link Graph} has already checked, so
 *       here it is only a question of which one to call under which {@code if}.</li>
 *   <li><b>A part whose dependency is absent in a mode is absent in that mode</b> ({@code OnMode}) — worked out
 *       here and said at the build, as a note, rather than left as a null a phase later.</li>
 *   <li><b>Nothing is discovered</b>: a parameter names a type, and that type is a framework value, a
 *       {@code @Setting}, the placement of the component's own lane, or exactly one part's. Anything else is a
 *       compile error naming the parameter — the first place this can be said, since only an application has a
 *       complete answer.</li>
 *   <li><b>What the shell takes back</b>: a provided {@code Appearance} is applied, and so has to exist in
 *       {@code CONFIG}, before the first widget reads a role; anything {@code AutoCloseable} is closed in reverse
 *       at shutdown; a {@code @BeforeFrame} method on a built value's type is put in the frame array.</li>
 * </ul>
 *
 * <p>The output is plain Java — constructor calls and method calls in dependency order, a few {@code if}s on the
 * run mode, and nothing else — so it costs a native binary what the hand-written wiring it replaces cost. It is
 * written with fully-qualified names throughout, because two parts from two packages can share a simple name.
 */
final class Generator {

    private static final Set<RunMode> EVERY_MODE = EnumSet.allOf(RunMode.class);

    private final Mirrors mirrors;
    private final Declarations declarations;
    private final Graph graph;
    private final Elements elements;
    private final Types types;
    private final Report report;
    private final Filer filer;

    Generator(Mirrors mirrors, Declarations declarations, Graph graph, Elements elements, Types types,
              Report report, Filer filer) {
        this.mirrors = mirrors;
        this.declarations = declarations;
        this.graph = graph;
        this.elements = elements;
        this.types = types;
        this.report = report;
        this.filer = filer;
    }

    // --- the plan --------------------------------------------------------------------------------------------

    /** One thing the wiring builds: a provider's call, or a component's construction. */
    private final class Binding {
        final Graph.Provider provider;
        final TypeElement component;
        final TypeMirror type;
        final Set<RunMode> declared;
        final List<Arg> args = new ArrayList<>();
        final List<Hook> hooks = new ArrayList<>();
        Set<RunMode> modes;
        Slot slot;
        Phase phase = Phase.CONFIG;
        int visit;

        Binding(Graph.Provider provider, TypeElement component, TypeMirror type, Set<RunMode> modes) {
            this.provider = provider;
            this.component = component;
            this.type = type;
            this.declared = EnumSet.copyOf(modes);
            this.modes = EnumSet.copyOf(modes);
        }

        Element element() {
            return provider != null ? provider.method() : component;
        }

        String where() {
            return provider != null ? Mirrors.where(provider.method()) : Mirrors.simple(component);
        }

        List<? extends VariableElement> parameters() {
            return provider != null ? provider.method().getParameters() : constructor(component).getParameters();
        }
    }

    /** One field of the wiring: a type, and every binding that may write it — one per mode, at most. */
    private static final class Slot {
        final TypeMirror type;
        final List<Binding> writers = new ArrayList<>();
        String field;

        Slot(TypeMirror type) {
            this.type = type;
        }

        Set<RunMode> modes() {
            Set<RunMode> out = EnumSet.noneOf(RunMode.class);
            for (Binding b : writers) {
                out.addAll(b.modes);
            }
            return out;
        }
    }

    /** A {@code @BeforeFrame} method, and the stage it runs in. */
    private record Hook(ExecutableElement method, String stage) {
    }

    /**
     * One parameter, resolved. Open, and asked rather than switched on: each kind knows its own expression,
     * phase, and the modes it exists in.
     */
    private interface Arg {
        String expression();

        Phase phase();

        Set<RunMode> modes();

        /** The bindings this one needs built first. */
        default List<Binding> needs() {
            return List.of();
        }

        /** The {@code @Setting} key this reads, or {@code null}. */
        default String key() {
            return null;
        }
    }

    private record RootArg(Framework.Root root) implements Arg {
        public String expression() {
            return root.expression();
        }

        public Phase phase() {
            return root.phase();
        }

        public Set<RunMode> modes() {
            return EVERY_MODE;
        }
    }

    private record SettingArg(String key, String call) implements Arg {
        public String expression() {
            return call;
        }

        public Phase phase() {
            return Phase.CONFIG;
        }

        public Set<RunMode> modes() {
            return EVERY_MODE;
        }
    }

    private record LaneArg(String field) implements Arg {
        public String expression() {
            return field;
        }

        public Phase phase() {
            return Phase.CONFIG;
        }

        public Set<RunMode> modes() {
            return EVERY_MODE;
        }
    }

    private static final class SlotArg implements Arg {
        final Slot slot;

        SlotArg(Slot slot) {
            this.slot = slot;
        }

        public String expression() {
            return slot.field;
        }

        public Phase phase() {
            Phase latest = Phase.CONFIG;
            for (Binding b : slot.writers) {
                if (b.phase.compareTo(latest) > 0) {
                    latest = b.phase;
                }
            }
            return latest;
        }

        public Set<RunMode> modes() {
            return slot.modes();
        }

        public List<Binding> needs() {
            return slot.writers;
        }
    }

    private final List<Binding> bindings = new ArrayList<>();
    private final List<Slot> slots = new ArrayList<>();
    private final Map<String, String> lanes = new LinkedHashMap<>();
    private final Map<TypeElement, String> configurations = new LinkedHashMap<>();
    private final Set<String> fieldNames = new HashSet<>();

    /**
     * Plan and write the wiring for {@code app}, or report why not.
     *
     * @param hooks every {@code @BeforeFrame} method the compilation holds, so one that nothing will call is an
     *              error rather than a method that silently never runs
     */
    void generate(TypeElement app, Set<ExecutableElement> hooks) {
        // Taken by the generated code itself: a field named like a phase method's parameter or its local would
        // be shadowed by it, and the call would read the wrong thing without a word from javac.
        fieldNames.addAll(List.of("INFO", "shell", "mode"));
        List<Graph.Provider> providers = graph.providersSeen();
        for (Graph.Provider p : providers) {
            Set<RunMode> wins = EnumSet.noneOf(RunMode.class);
            for (RunMode m : RunMode.values()) {
                if (p.modes().contains(m.name()) && graph.winsIn(p, m, providers)) {
                    wins.add(m);
                }
            }
            if (!wins.isEmpty()) {
                bindings.add(new Binding(p, null, p.type(), wins));
            }
        }
        for (TypeElement c : graph.componentsSeen()) {
            if (constructor(c) != null) {
                bindings.add(new Binding(null, c, c.asType(), declaredModes(c)));
            }
        }
        for (Binding b : bindings) {
            slotFor(b.type).writers.add(b);
            b.slot = slotFor(b.type);
        }
        for (Slot s : slots) {
            s.field = field(s.writers.size() == 1 && s.writers.get(0).provider != null
                    ? Mirrors.simple(s.writers.get(0).provider.method())
                    : decapitalize(Mirrors.element(s.type) != null
                        ? Mirrors.simple(Mirrors.element(s.type)) : "value"));
        }
        for (Binding b : bindings) {
            resolve(b);
        }
        if (report.failed()) {
            return;
        }
        narrowModes();
        List<Binding> order = new ArrayList<>();
        for (Binding b : bindings) {
            if (!order(b, order, new ArrayList<>())) {
                return;
            }
        }
        for (Binding b : bindings) {
            appearanceInConfig(b);
            accessible(app, b);
        }
        claimHooks(app, hooks);
        if (report.failed()) {
            return;
        }
        write(app, order);
    }

    private Slot slotFor(TypeMirror type) {
        for (Slot s : slots) {
            if (types.isSameType(s.type, type)) {
                return s;
            }
        }
        Slot s = new Slot(type);
        slots.add(s);
        return s;
    }

    // --- resolving a parameter -------------------------------------------------------------------------------

    private void resolve(Binding b) {
        for (VariableElement p : b.parameters()) {
            Arg arg = argument(b, p);
            if (arg != null) {
                b.args.add(arg);
            }
        }
    }

    private Arg argument(Binding b, VariableElement parameter) {
        AnnotationMirror setting = mirrors.find(parameter, Setting.class);
        if (setting != null) {
            return setting(mirrors.string(setting, "value"), mirrors.string(setting, "def"), parameter.asType());
        }
        TypeMirror type = parameter.asType();
        TypeElement element = Mirrors.element(type);
        if (element != null && b.component != null
                && element.getQualifiedName().contentEquals(Framework.PLACEMENT)) {
            String lane = declarations.lane(b.component);
            return new LaneArg(lanes.computeIfAbsent(lane, l -> field("lane" + capitalize(identifier(l)))));
        }
        if (element != null) {
            Framework.Root root = Framework.root(element.getQualifiedName().toString());
            if (root != null) {
                return new RootArg(root);
            }
        }
        for (Slot s : slots) {
            if (types.isSameType(s.type, type)) {
                return new SlotArg(s);
            }
        }
        if (type.getKind().isPrimitive()) {
            error(parameter, b.where() + " takes " + Mirrors.simple(parameter) + ", a " + type + ", and nothing"
                    + " supplies a bare value. Bind it with @Setting");
        } else {
            error(parameter, b.where() + " takes " + Mirrors.simple(parameter) + ", a " + type + ", and nothing"
                    + " provides one. Write a @Provides method returning " + type + ", or take something the"
                    + " framework supplies — the Shell, the Gui, the GuiApp and the rest of its accessors");
        }
        return null;
    }

    /** {@code shell.setting(...)} with a default literal of the parameter's own type — the overload picks the type. */
    private Arg setting(String key, String def, TypeMirror type) {
        String k = literal(key);
        TypeKind kind = type.getKind();
        if (kind == TypeKind.INT) {
            return new SettingArg(key, "shell.setting(" + k + ", " + Integer.parseInt(def) + ")");
        }
        if (kind == TypeKind.LONG) {
            return new SettingArg(key, "shell.setting(" + k + ", " + Long.parseLong(def) + "L)");
        }
        if (kind == TypeKind.FLOAT) {
            return new SettingArg(key, "shell.setting(" + k + ", " + floatLiteral(Float.parseFloat(def)) + ")");
        }
        if (kind == TypeKind.BOOLEAN) {
            return new SettingArg(key, "shell.setting(" + k + ", " + def + ")");
        }
        TypeElement element = Mirrors.element(type);
        if (element != null && element.getQualifiedName().contentEquals("java.util.List")) {
            return new SettingArg(key, "shell.settingList(" + k + ")");
        }
        return new SettingArg(key, "shell.setting(" + k + ", " + literal(def) + ")");
    }

    // --- modes -----------------------------------------------------------------------------------------------

    /**
     * Narrow each binding to the modes all of its dependencies exist in, until nothing moves. Monotone — a set
     * only ever loses members — so it stops.
     */
    private void narrowModes() {
        boolean moved = true;
        while (moved) {
            moved = false;
            for (Binding b : bindings) {
                Set<RunMode> modes = EnumSet.copyOf(b.declared);
                for (Arg a : b.args) {
                    modes.retainAll(a.modes());
                }
                if (!modes.equals(b.modes)) {
                    b.modes = modes.isEmpty() ? EnumSet.noneOf(RunMode.class) : modes;
                    moved = true;
                }
            }
        }
        for (Binding b : bindings) {
            if (b.modes.isEmpty()) {
                error(b.element(), b.where() + " is never built: in every mode it would exist in (" + b.declared
                        + "), something it takes does not. Guard its dependency to the same modes, or"
                        + " this one to fewer");
            } else if (!b.modes.equals(b.declared)) {
                report.printMessage(Diagnostic.Kind.NOTE, b.where() + " is built only in " + b.modes
                        + ", because something it takes exists only there", b.element());
            }
        }
    }

    /** A component's own {@code @OnMode}; empty or absent means every mode. */
    private Set<RunMode> declaredModes(Element element) {
        AnnotationMirror onMode = mirrors.find(element, OnMode.class);
        Set<String> listed = onMode == null ? Set.of() : mirrors.constants(onMode, "value");
        if (listed.isEmpty()) {
            return EVERY_MODE;
        }
        Set<RunMode> out = EnumSet.noneOf(RunMode.class);
        for (String name : listed) {
            out.add(RunMode.valueOf(name));
        }
        return out;
    }

    // --- phase and order -------------------------------------------------------------------------------------

    /**
     * Depth first: a binding's phase is the latest of its arguments', and it is placed after everything it needs.
     * A binding met again while it is still being visited is a cycle, reported with the path that closed it.
     */
    private boolean order(Binding b, List<Binding> order, List<Binding> path) {
        if (b.visit == 2) {
            return true;
        }
        if (b.visit == 1) {
            StringBuilder cycle = new StringBuilder();
            for (int i = path.indexOf(b); i < path.size(); i++) {
                cycle.append(path.get(i).where()).append(" -> ");
            }
            error(b.element(), "A cycle: " + cycle + b.where() + ". Something has to be built first, and here"
                    + " everything needs something built after it. Break it with a publish rather than a"
                    + " reference");
            return false;
        }
        b.visit = 1;
        path.add(b);
        Phase phase = Phase.CONFIG;
        for (Arg a : b.args) {
            for (Binding need : a.needs()) {
                if (!order(need, order, path)) {
                    return false;
                }
            }
            if (a.phase().compareTo(phase) > 0) {
                phase = a.phase();
            }
        }
        b.phase = phase;
        path.remove(path.size() - 1);
        b.visit = 2;
        order.add(b);
        return true;
    }

    /** The look is applied before the first widget reads a role, so what provides it can take only CONFIG values. */
    private void appearanceInConfig(Binding b) {
        TypeElement element = Mirrors.element(b.type);
        if (element != null && element.getQualifiedName().contentEquals(Framework.APPEARANCE)
                && b.phase != Phase.CONFIG) {
            error(b.element(), b.where() + " provides the Appearance, and takes something that exists only from "
                    + b.phase + ". The look is applied before the first widget is constructed — \"a role resolves"
                    + " at the moment a widget writes a prop\" — so it can be built only out of CONFIG values");
        }
    }

    // --- frame hooks -----------------------------------------------------------------------------------------

    /**
     * Each {@code @BeforeFrame} method is claimed by the binding whose type declares or inherits it; one that no
     * binding claims would never run, which is an error rather than a method that silently does nothing.
     */
    private void claimHooks(TypeElement app, Set<ExecutableElement> all) {
        Set<ExecutableElement> claimed = new HashSet<>();
        for (Binding b : bindings) {
            TypeElement element = Mirrors.element(b.type);
            if (element == null) {
                continue;
            }
            for (Element member : elements.getAllMembers(element)) {
                AnnotationMirror hook = mirrors.find(member, BeforeFrame.class);
                if (member.getKind() == ElementKind.METHOD && hook != null) {
                    ExecutableElement method = (ExecutableElement) member;
                    b.hooks.add(new Hook(method, ((VariableElement) mirrors.value(hook, "value"))
                            .getSimpleName().toString()));
                    claimed.add(method);
                    if (!visibleFrom(app, method)) {
                        error(method, "@BeforeFrame " + Mirrors.where(method) + " is not visible from "
                                + Mirrors.simple(app) + "'s package, where the generated wiring calls it. Make it"
                                + " public");
                    }
                }
            }
        }
        for (ExecutableElement hook : all) {
            if (!claimed.contains(hook) && !mirrors.has(hook.getEnclosingElement(), Component.class)) {
                error(hook, "@BeforeFrame " + Mirrors.where(hook) + " would never run: nothing the wiring builds is"
                        + " of that type. Put the hook on the type a @Provides method returns, or provide the"
                        + " type it is on");
            }
        }
    }

    // --- visibility ------------------------------------------------------------------------------------------

    private void accessible(TypeElement app, Binding b) {
        if (b.provider != null) {
            ExecutableElement method = b.provider.method();
            if (!visibleFrom(app, method) || !visibleFrom(app, b.provider.configuration())) {
                error(method, "@Provides " + b.where() + " is not visible from " + Mirrors.simple(app)
                        + "'s package, where the generated wiring calls it. A starter's configuration and its"
                        + " providers are public");
            }
        } else if (!visibleFrom(app, b.component) || !visibleFrom(app, constructor(b.component))) {
            error(b.component, "@Component " + b.where() + " is not visible from " + Mirrors.simple(app)
                    + "'s package, where the generated wiring constructs it");
        }
    }

    private boolean visibleFrom(TypeElement app, Element element) {
        for (Element e = element; e != null && e.getKind() != ElementKind.PACKAGE; e = e.getEnclosingElement()) {
            if (e.getModifiers().contains(Modifier.PRIVATE)) {
                return false;
            }
            if (!e.getModifiers().contains(Modifier.PUBLIC)
                    && !elements.getPackageOf(e).equals(elements.getPackageOf(app))) {
                return false;
            }
        }
        return true;
    }

    // --- writing ---------------------------------------------------------------------------------------------

    private void write(TypeElement app, List<Binding> order) {
        PackageElement pkg = elements.getPackageOf(app);
        String name = Mirrors.simple(app) + "Wiring";
        String qualified = pkg.isUnnamed() ? name : pkg.getQualifiedName() + "." + name;
        AnnotationMirror vexelApp = mirrors.find(app, VexelApp.class);

        Set<String> keys = new LinkedHashSet<>();
        for (Binding b : order) {
            for (Arg a : b.args) {
                if (a.key() != null) {
                    keys.add(a.key());
                }
            }
        }
        StringBuilder src = new StringBuilder();
        if (!pkg.isUnnamed()) {
            src.append("package ").append(pkg.getQualifiedName()).append(";\n\n");
        }
        src.append("/**\n")
                .append(" * What {@link ").append(Mirrors.simple(app)).append("} builds, and in which phase —")
                .append(" generated from its annotations by\n")
                .append(" * {@code vexelray-framework-processor}. Not to be edited: change the {@code @Provides}")
                .append(" methods and components\n")
                .append(" * it was generated from, and it follows.\n")
                .append(" */\n")
                .append("@javax.annotation.processing.Generated(\"dev.vexelray.framework.processor.VexelProcessor\")\n")
                .append("final class ").append(name).append(" extends ").append(Framework.WIRING).append(" {\n\n");

        src.append("    private static final ").append(Framework.APP_INFO).append(" INFO = new ")
                .append(Framework.APP_INFO).append("(\n            ")
                .append(literal(mirrors.string(vexelApp, "name"))).append(", ")
                .append(literal(mirrors.string(vexelApp, "title"))).append(", ")
                .append(mirrors.value(vexelApp, "width")).append(", ")
                .append(mirrors.value(vexelApp, "height")).append(",\n            java.util.Set.of(");
        String sep = "";
        for (String k : keys) {
            src.append(sep).append(literal(k));
            sep = ", ";
        }
        src.append("));\n\n");

        for (Binding b : order) {
            if (b.provider != null && !b.provider.method().getModifiers().contains(Modifier.STATIC)) {
                TypeElement config = b.provider.configuration();
                if (!configurations.containsKey(config)) {
                    String field = field(decapitalize(Mirrors.simple(config)));
                    configurations.put(config, field);
                    src.append("    private final ").append(config.getQualifiedName()).append(' ').append(field)
                            .append(" = new ").append(config.getQualifiedName()).append("();\n");
                }
            }
        }
        for (Slot s : slots) {
            src.append("    private ").append(s.type).append(' ').append(s.field).append(";\n");
        }
        for (String lane : lanes.values()) {
            src.append("    private ").append(Framework.PLACEMENT).append(' ').append(lane).append(";\n");
        }
        src.append("\n    @Override\n    public ").append(Framework.APP_INFO).append(" info() {\n")
                .append("        return INFO;\n    }\n");

        for (Phase phase : Phase.values()) {
            List<Binding> here = new ArrayList<>();
            for (Binding b : order) {
                if (b.phase == phase) {
                    here.add(b);
                }
            }
            if (here.isEmpty()) {
                continue;
            }
            src.append("\n    @Override\n    public void ").append(Framework.method(phase)).append('(')
                    .append(Framework.SHELL).append(" shell) {\n");
            boolean guarded = false;
            for (Binding b : here) {
                guarded |= !b.modes.equals(EVERY_MODE);
            }
            if (guarded) {
                src.append("        dev.vexelray.framework.api.RunMode mode = shell.launch().mode();\n");
            }
            for (Binding b : here) {
                statement(b, src);
            }
            src.append("    }\n");
        }
        src.append("}\n");

        try (Writer out = filer.createSourceFile(qualified, app).openWriter()) {
            out.write(src.toString());
        } catch (IOException e) {
            error(app, "could not write " + qualified + ": " + e.getMessage());
        }
    }

    private void statement(Binding b, StringBuilder src) {
        String indent = "        ";
        boolean guard = !b.modes.equals(EVERY_MODE);
        if (guard) {
            src.append(indent).append("if (");
            String or = "";
            for (RunMode m : b.modes) {
                src.append(or).append("mode == dev.vexelray.framework.api.RunMode.").append(m.name());
                or = " || ";
            }
            src.append(") {\n");
            indent += "    ";
        }
        for (Arg a : b.args) {
            if (a instanceof LaneArg lane) {
                String laneName = laneOf(lane.field());
                src.append(indent).append("if (").append(lane.field()).append(" == null) {\n")
                        .append(indent).append("    ").append(lane.field()).append(" = shell.place(")
                        .append(literal(laneName)).append(");\n")
                        .append(indent).append("}\n");
            }
        }
        StringBuilder args = new StringBuilder();
        String sep = "";
        for (Arg a : b.args) {
            args.append(sep).append(a.expression());
            sep = ", ";
        }
        String field = b.slot.field;
        src.append(indent).append(field).append(" = ");
        if (b.provider != null) {
            ExecutableElement method = b.provider.method();
            String target = method.getModifiers().contains(Modifier.STATIC)
                    ? b.provider.configuration().getQualifiedName().toString()
                    : configurations.get(b.provider.configuration());
            src.append(target).append('.').append(Mirrors.simple(method)).append('(').append(args).append(");\n");
        } else {
            src.append("new ").append(b.component.getQualifiedName()).append('(').append(args).append(");\n");
        }

        List<String> after = new ArrayList<>();
        TypeElement element = Mirrors.element(b.type);
        if (element != null && element.getQualifiedName().contentEquals(Framework.APPEARANCE)) {
            after.add("shell.appearance(" + field + ");");
        }
        TypeElement closeable = elements.getTypeElement("java.lang.AutoCloseable");
        if (closeable != null && types.isAssignable(b.type, closeable.asType())) {
            after.add("shell.disposer().register(" + field + ");");
        }
        for (Hook h : b.hooks) {
            after.add("shell.hooks().add(dev.vexelray.framework.api.FrameStage." + h.stage() + ", " + field + "::"
                    + Mirrors.simple(h.method()) + ");");
        }
        if (!after.isEmpty()) {
            src.append(indent).append("if (").append(field).append(" != null) {\n");
            for (String line : after) {
                src.append(indent).append("    ").append(line).append('\n');
            }
            src.append(indent).append("}\n");
        }
        if (guard) {
            src.append("        }\n");
        }
    }

    private String laneOf(String field) {
        for (Map.Entry<String, String> e : lanes.entrySet()) {
            if (e.getValue().equals(field)) {
                return e.getKey();
            }
        }
        return field;
    }

    // --- names and literals ----------------------------------------------------------------------------------

    /** A field name not yet taken, and not a keyword. */
    private String field(String wanted) {
        String base = SourceVersion.isName(wanted) ? wanted : wanted + "Value";
        String name = base;
        for (int i = 2; !fieldNames.add(name); i++) {
            name = base + i;
        }
        return name;
    }

    private static String identifier(String text) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : text.toCharArray()) {
            if (Character.isJavaIdentifierPart(c)) {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            } else {
                upper = true;
            }
        }
        return out.isEmpty() ? "lane" : out.toString();
    }

    private static String decapitalize(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String floatLiteral(float f) {
        if (Float.isNaN(f)) {
            return "Float.NaN";
        }
        if (Float.isInfinite(f)) {
            return f > 0 ? "Float.POSITIVE_INFINITY" : "Float.NEGATIVE_INFINITY";
        }
        return Float.toString(f) + "f";
    }

    /** A Java string literal, escaped so any text survives the trip into source. */
    static String literal(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c < 0x20 || c > 0x7e) {
                out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /** The one non-private constructor, or {@code null} — already reported by {@link Declarations}. */
    private static ExecutableElement constructor(TypeElement type) {
        ExecutableElement found = null;
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() == ElementKind.CONSTRUCTOR && !e.getModifiers().contains(Modifier.PRIVATE)) {
                if (found != null) {
                    return null;
                }
                found = (ExecutableElement) e;
            }
        }
        return found;
    }

    private void error(Element element, String message) {
        report.printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
