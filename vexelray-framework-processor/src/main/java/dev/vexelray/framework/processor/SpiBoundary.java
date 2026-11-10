package dev.vexelray.framework.processor;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * A {@code @View} names nothing in vexelray-gui's {@code spi} (Rasterware FN-34). {@code Seams} is the one type a widget
 * library reaches and an application is not handed (vexelray-gui's {@code docs/reference/seams.md}); what an application
 * uses instead is a {@code @Draw}, a {@code @Subscribe} and the widgets' own API, each of which this processor checks.
 * {@code Seams.of(gui)} is public and static, so until now only review kept a view from calling it, and what a view
 * registered there would be code the GUI thread runs with none of T7.6's checks.
 *
 * <p>Read from the source as written: an import of the package or of anything in it, and a name spelt out in full in
 * the class. A view compiled elsewhere arrives as a class file, which has no imports to read, and is the library's to
 * keep to.
 */
final class SpiBoundary {

    /** vexelray-gui's seams: the host's and the widget libraries', never an application's. */
    static final String SPI = "dev.vexelray.gui.core.spi";

    private final Trees trees;
    private final Report report;

    private SpiBoundary(Trees trees, Report report) {
        this.trees = trees;
        this.report = report;
    }

    /** The check, or null under a compiler whose trees cannot be read: then nothing is refused. */
    static SpiBoundary of(ProcessingEnvironment env, Report report) {
        try {
            return new SpiBoundary(Trees.instance(env), report);
        } catch (IllegalArgumentException notJavac) {
            return null;
        }
    }

    /** Refuse each place {@code view}'s source names the {@code spi} package. */
    void check(TypeElement view) {
        TreePath path = trees.getPath(view);
        if (path == null) {
            return;
        }
        CompilationUnitTree unit = path.getCompilationUnit();
        for (ImportTree i : unit.getImports()) {
            if (inSpi(i.getQualifiedIdentifier().toString())) {
                refuse(view, i, unit);
            }
        }
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMemberSelect(MemberSelectTree select, Void unused) {
                if (inSpi(select.toString())) {
                    refuse(view, select, unit);
                    return null;   // its own prefix is the package again: once is enough
                }
                return super.visitMemberSelect(select, unused);
            }
        }.scan(path, null);
    }

    private static boolean inSpi(String name) {
        return name.equals(SPI) || name.startsWith(SPI + ".");
    }

    private void refuse(TypeElement view, com.sun.source.tree.Tree where, CompilationUnitTree unit) {
        report.at(trees, Diagnostic.Kind.ERROR, "@View " + Mirrors.simple(view) + " names " + SPI + ", the widget "
                + "libraries' seam, which an application does not reach: what it registers there runs on the GUI thread "
                + "with none of the checks a view's code has. A view moves its nodes in its @Draw, hears its widgets with "
                + "@Subscribe, and sets them through their own API (vexelray-gui's docs/reference/seams.md, If you want "
                + "to...)", where, unit);
    }
}
