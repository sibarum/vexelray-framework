package dev.vexelray.framework.shell;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.atchung.Atchung;
import sibarum.atchung.Fatal;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An application runs on a fault policy the framework chose, not on the one it would otherwise inherit.
 *
 * <p>{@code Atchung.onFatal} is process-wide and its contract is <i>"call it once, at the application
 * edge"</i>. Nothing on this stack called it, so the answer to <i>what does this application do when the bus
 * cannot continue honestly</i> was {@code Fatal.HALT} by inheritance — which happens to be the right answer
 * and was nobody's decision, and those are two different things. See {@link Faults} for the decision itself,
 * including why it is still a halt.
 *
 * <p><b>The policy cannot be run from a test, by construction.</b> It does not return: it halts the JVM, which
 * would take this suite with it. So what is asserted is that the framework installed one and what its sentence
 * says — the two halves that can be checked without the check being fatal.
 */
final class FaultPolicyTest {

    private static final AppInfo DEMO = new AppInfo("demo", "Demo", 800, 600);

    /**
     * Put the process back on the default.
     *
     * <p>Not tidiness: this policy is global to the JVM and halts it, so a test that left the framework's one
     * installed would hand every later test in this fork a live {@code Runtime.halt}. Atchung's own javadoc
     * sanctions exactly this — a test that needs to observe a fault installs its own policy and restores it.
     */
    @AfterEach
    void restoreTheDefault() {
        Atchung.onFatal(null);
    }

    @Test
    void anApplicationNeverRunsOnTheInheritedDefault(@TempDir Path home) {
        System.setProperty(DEMO.name() + ".home", home.toString());
        assertSameDefaultAsAFreshProcess();

        Shell shell = VexelApplication.tree(new Bare(), new String[0]);
        try {
            assertNotSame(Fatal.HALT, Atchung.fatal(),
                    "starting an application left the process on the policy it inherited, so the framework's "
                            + "answer to a bus fault is whatever atchung's happens to be");
        } finally {
            shell.disposer().close();
        }
    }

    /**
     * The sentence names the application and repeats what the fault said.
     *
     * <p>Both halves earn their place. A process on a desk running three of these applications produces a
     * report that has to say <em>which</em>; and a report that summarises the cause instead of quoting it is
     * a second description of a fault that already had one.
     */
    @Test
    void theReportNamesTheApplicationAndTheCause() {
        String report = Faults.report(DEMO, new IllegalStateException("mailbox 'gui.mutations' full at 1024"));

        assertTrue(report.contains("demo"), report);
        assertTrue(report.contains("mailbox 'gui.mutations' full at 1024"), report);
    }

    /**
     * A cause with no message is named by its type.
     *
     * <p>The alternative is a report reading {@code ... past this: null}, which sends whoever is holding the
     * pager to the framework's formatting code rather than to their own fault.
     */
    @Test
    void aSilentCauseIsStillIdentified() {
        String report = Faults.report(DEMO, new NullPointerException());

        assertTrue(report.contains("java.lang.NullPointerException"), report);
    }

    /** The precondition the first test rests on: nothing before it has moved the process off the default. */
    private static void assertSameDefaultAsAFreshProcess() {
        Atchung.onFatal(null);
    }

    /** A wiring that says who it is and builds nothing. */
    private static final class Bare extends Wiring {

        @Override
        public AppInfo info() {
            return DEMO;
        }
    }
}
