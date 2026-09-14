package dev.vexelray.framework.shell;

import sibarum.atchung.Atchung;
import sibarum.atchung.Fatal;

/**
 * What a VexelRay application does when the bus detects a fault it cannot honestly continue past.
 *
 * <p><b>Why this is the framework's to say.</b> {@code Atchung.onFatal}'s own contract is <i>"call it once, at
 * the application edge"</i> — and {@link VexelApplication} is that edge, for every application on this stack.
 * Nothing called it, so every application ran on a policy nobody chose. That is the same class of thing as the
 * two {@code Settings} instances: correct today, inherited rather than decided, and a decision by default is
 * the one nobody can find when they go looking for it.
 *
 * <p><b>It still halts, and the reason is upstream's rather than ours.</b> The obvious framework addition —
 * save the window placement first, since the {@code Disposer} and {@code WindowMemory.save} are right there —
 * is exactly what {@link Fatal#HALT} rules out, with a reason worth reading twice: <i>"running application
 * code on a thread that is mid-publish, holding a mailbox lock, with a full queue behind it, is how a crash
 * becomes a hang."</i> A lost window placement is the cheaper loss, and a hang during a crash is the failure
 * this stack keeps proving is the expensive one. So what the framework adds is the sentence naming the
 * application, and then upstream's report and upstream's exit code, called rather than copied.
 *
 * <p><b>What this is not.</b> It is not the answer to "a component entered an infinite loop", and reading it
 * as one would put survivability in the wrong place. A wedged component is a mailbox filling up, and the
 * answer to that is the {@code Backpressure} chosen for that channel, per channel, by the loss class of what
 * it carries — a sample coalesces because the next one supersedes it, an edge must not be lost. {@code Fatal}
 * is the backstop for a bus that can no longer produce trustworthy output at all, which is a different event
 * and a rarer one.
 *
 * <p><b>Decided now because nothing reaches it yet.</b> {@code Gui}'s mutation and navigation mailboxes are
 * {@code Backpressure.BLOCK}, so no channel in a VexelRay application routes through this today. There is no
 * behaviour to preserve, which will stop being true the first time a component mailbox is given
 * {@code Backpressure.FAIL} — and a default chosen after something depends on it is not a choice any more.
 */
final class Faults {

    private Faults() {
    }

    /**
     * Install this application's fault policy, before anything can publish.
     *
     * <p>Process-wide, so the last call wins — which is why this is called from the application edge and not
     * from {@link Shell}, whose constructor runs once per {@code Shell} and not once per process.
     */
    static void install(AppInfo info) {
        Atchung.onFatal(cause -> {
            System.err.println(report(info, cause));
            System.err.flush();
            // Upstream's report and upstream's exit code, called rather than restated: 70 is a literal that
            // would otherwise have to agree across two repos, and this is the one place it could drift.
            Fatal.HALT.fault(cause);
        });
    }

    /**
     * The line the framework adds above upstream's report.
     *
     * <p>Separate from {@link #install} because the policy itself does not return, so the only part of this
     * that a test can run is the part that builds the sentence.
     */
    static String report(AppInfo info, Throwable cause) {
        return "vexelray: " + info.name() + " is stopping — the bus cannot continue honestly past this: "
                + said(cause)
                + "; a mailbox overflowed, so what follows is the last output that can be trusted";
    }

    /**
     * What the cause says for itself.
     *
     * <p>The type when there is no message, rather than the word {@code null} — a fault report that says
     * {@code null} is a report that sends the reader to this line instead of to theirs.
     */
    private static String said(Throwable cause) {
        if (cause == null) {
            return "no cause given";
        }
        return cause.getMessage() == null ? cause.getClass().getName() : cause.getMessage();
    }
}
