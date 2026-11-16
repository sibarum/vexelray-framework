package dev.vexelray.framework.template;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds, in Java sources, the constructs threading rule T7.3 keeps to an allowlist: a {@code synchronized} method or
 * block, a {@code volatile} field, anything from {@code java.util.concurrent} (its {@code atomic} and {@code locks}
 * too), and {@code Thread} or {@code ThreadGroup}. ROADMAP <i>Done means</i> 1.
 *
 * <p><b>Parsed, not grepped.</b> Each file goes through javac's parser (the tree API the processor's {@code StepCheck}
 * reads bodies with), so the words in a comment, a string or a javadoc are not code and trip nothing. It is not
 * attributed: an import or a fully qualified name says {@code java.util.concurrent}, since no other spelling reaches the
 * package, and {@code Thread} is the name as written, which in this repository is only ever {@code java.lang}'s.
 *
 * <p>A template's {@code ${slot}} is replaced by an identifier before parsing, on the same line, so a line number is the
 * file's own.
 */
final class ConcurrencyScan {

    /** One construct, where it is. {@code type} is the qualified name of the file's top-level class. */
    record Hit(String type, Path file, long line, String what) {
        @Override
        public String toString() {
            return file + ":" + line + ": " + what;
        }
    }

    private static final Pattern SLOT = Pattern.compile("\\$\\{(\\w+)}");
    private static final String CONCURRENT = "java.util.concurrent";

    private ConcurrencyScan() {
    }

    /** Every hit in every {@code .java} file under each of {@code roots}. */
    static List<Hit> scan(List<Path> roots) {
        List<Source> sources = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                throw new AssertionError("no sources at " + root.toAbsolutePath().normalize());
            }
            try (Stream<Path> walk = Files.walk(root.toAbsolutePath().normalize())) {
                walk.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> sources.add(Source.of(p)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return scanSources(sources);
    }

    /** Every hit in {@code text}, as if it were the file {@code name}. For the scanner's own test. */
    static List<Hit> scanText(String name, String text) {
        return scanSources(List.of(new Source(Path.of(name), text)));
    }

    private static List<Hit> scanSources(List<Source> sources) {
        if (sources.isEmpty()) {
            return List.of();
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> problems = new DiagnosticCollector<>();
        List<JavaFileObject> objects = sources.stream().map(Source::object).toList();
        // javac hands back its own wrapper of each file object, so a unit is matched to its file by URI.
        Map<URI, Path> paths = new HashMap<>();
        for (JavaFileObject o : objects) {
            paths.put(o.toUri(), ((Source.Text) o).path);
        }
        JavacTask task = (JavacTask) javac.getTask(null, null, problems, List.of("-proc:none"), null, objects);
        Iterable<? extends CompilationUnitTree> units;
        try {
            units = task.parse();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : problems.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                errors.add(d.getSource().getName() + ":" + d.getLineNumber() + ": " + d.getMessage(null));
            }
        }
        if (!errors.isEmpty()) {
            // A file that does not parse would be a file nothing was checked in.
            throw new AssertionError("does not parse:\n  " + String.join("\n  ", errors));
        }
        SourcePositions positions = Trees.instance(task).getSourcePositions();
        List<Hit> hits = new ArrayList<>();
        for (CompilationUnitTree unit : units) {
            Path file = paths.get(unit.getSourceFile().toUri());
            String simple = file.getFileName().toString().replaceFirst("\\.java$", "");
            String type = unit.getPackageName() == null ? simple : unit.getPackageName() + "." + simple;
            new Finder(unit, positions, type, file, hits).scan(unit, null);
        }
        return hits;
    }

    /** The walk over one file. */
    private static final class Finder extends TreeScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final String type;
        private final Path file;
        private final List<Hit> hits;

        Finder(CompilationUnitTree unit, SourcePositions positions, String type, Path file, List<Hit> hits) {
            this.unit = unit;
            this.positions = positions;
            this.type = type;
            this.file = file;
            this.hits = hits;
        }

        private void hit(Tree at, String what) {
            long line = unit.getLineMap().getLineNumber(positions.getStartPosition(unit, at));
            hits.add(new Hit(type, file, line, what));
        }

        @Override
        public Void visitPackage(com.sun.source.tree.PackageTree node, Void unused) {
            // A package name is not a use, and a slot in one is a placeholder.
            return null;
        }

        @Override
        public Void visitImport(ImportTree node, Void unused) {
            String name = dotted(node.getQualifiedIdentifier());
            if (name != null && concurrent(name)) {
                hit(node, "import " + name);
            } else if (name != null && thread(name)) {
                hit(node, "import " + name);
            }
            return null;
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void unused) {
            String name = dotted(node);
            if (name != null && concurrent(name)) {
                hit(node, name);
                return null;
            }
            if (name != null && (name.equals("java.lang.Thread") || name.equals("java.lang.ThreadGroup"))) {
                hit(node, name);
                return null;
            }
            return super.visitMemberSelect(node, unused);
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Void unused) {
            String name = node.getName().toString();
            if (name.equals("Thread") || name.equals("ThreadGroup")) {
                hit(node, name);
            }
            return null;
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            if (node.getModifiers().getFlags().contains(Modifier.SYNCHRONIZED)) {
                hit(node, "synchronized method " + node.getName());
            }
            return super.visitMethod(node, unused);
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            if (node.getModifiers().getFlags().contains(Modifier.VOLATILE)) {
                hit(node, "volatile field " + node.getName());
            }
            return super.visitVariable(node, unused);
        }

        @Override
        public Void visitSynchronized(SynchronizedTree node, Void unused) {
            hit(node, "synchronized block");
            return super.visitSynchronized(node, unused);
        }
    }

    private static boolean concurrent(String name) {
        return name.equals(CONCURRENT) || name.startsWith(CONCURRENT + ".");
    }

    private static boolean thread(String name) {
        return name.equals("java.lang.Thread") || name.startsWith("java.lang.Thread.")
                || name.equals("java.lang.ThreadGroup");
    }

    /** {@code a.b.c} for a chain of plain names, else null (a call, an array, anything that is not a name). */
    private static String dotted(Tree tree) {
        if (tree instanceof IdentifierTree id) {
            return id.getName().toString();
        }
        if (tree instanceof MemberSelectTree select) {
            ExpressionTree left = select.getExpression();
            String head = dotted(left);
            return head == null ? null : head + "." + select.getIdentifier();
        }
        return null;
    }

    /** A file's text, with each template slot made an identifier. */
    private record Source(Path path, String text) {
        static Source of(Path path) {
            try {
                return new Source(path, Files.readString(path, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        JavaFileObject object() {
            return new Text(path, SLOT.matcher(text).replaceAll("$1"));
        }

        static final class Text extends SimpleJavaFileObject {
            final Path path;
            private final String code;

            Text(Path path, String code) {
                super(URI.create("string:///" + path.toString().replace('\\', '/').replace(' ', '_')),
                        Kind.SOURCE);
                this.path = path;
                this.code = code;
            }

            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        }
    }
}
