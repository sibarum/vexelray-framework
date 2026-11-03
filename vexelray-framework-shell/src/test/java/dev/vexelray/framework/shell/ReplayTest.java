package dev.vexelray.framework.shell;

import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.core.Launch;
import dev.vexelray.framework.core.Phase;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Inbox;
import sibarum.atchung.Sender;
import sibarum.atchung.Topic;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run replayed (ROADMAP <i>Done means</i> 5): what the logic took in, run again on a fresh set of the components,
 * sends the same, in either mode; and where a component keeps something outside itself, the replay says which sends
 * differ.
 */
class ReplayTest {

    private static final AppInfo DEMO = new AppInfo("demo", "Demo", 800, 600);
    private static final Topic<String> ASKED = Topic.edge("test.replay.asked", String.class);
    private static final Topic<String> ANSWERED = Topic.edge("test.replay.answered", String.class);
    private static final Topic<String> REPORTED = Topic.edge("test.replay.reported", String.class);

    /** A count kept outside every component: what a fresh set of them does not start from. */
    private static final AtomicInteger LEAKED = new AtomicInteger();

    /** A counter that answers each question with its number, and a reporter that shouts the answer. */
    private static final class Counting extends Wiring {
        private final boolean leaks;

        Counting(boolean leaks) {
            this.leaks = leaks;
        }

        @Override
        public AppInfo info() {
            return DEMO;
        }

        @Override
        public void model(Shell shell) {
            Inbox counter = shell.inbox("app.Counter", Component.DEFAULT_LANE);
            Sender<String> answer = counter.sender(ANSWERED);
            int[] mine = {0};
            counter.subscribe(ASKED, q -> answer.send(q + " #" + (leaks ? LEAKED.incrementAndGet() : ++mine[0])), 8);
            Inbox reporter = shell.inbox("app.Reporter", Component.DEFAULT_LANE);
            Sender<String> report = reporter.sender(REPORTED);
            reporter.subscribe(ANSWERED, a -> report.send(a.toUpperCase()), 8);
        }

        @Override
        public Wiring again() {
            return new Counting(leaks);
        }
    }

    /** Ask three questions of a run launched with {@code args}, then replay it. */
    private static String askedThenReplayed(boolean leaks, String... args) throws Exception {
        Shell shell = new Shell(Launch.parse(args, "demo", Set.of()), DEMO);
        LEAKED.set(0);
        Counting wiring = new Counting(leaks);
        try {
            shell.phase(Phase.CONFIG);
            wiring.config(shell);
            shell.phase(Phase.MODEL);
            wiring.model(shell);
            shell.again(wiring.again());
            shell.startComponents();
            for (String q : new String[]{"a", "bb", "ccc"}) {
                shell.bus().publish(ASKED, q);
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                do {
                    shell.runLogicOnMain();
                    Thread.sleep(1);
                } while (!shell.owesNothing() && System.nanoTime() < until);
            }
            return shell.replay();
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void theSameInputsRunAgainSendTheSameInEitherMode() throws Exception {
        for (String mode : new String[]{"--logic=thread", "--logic=main"}) {
            String answer = askedThenReplayed(false, "--replayable=true", mode);
            assertEquals("ok replay ticks=3 sends=6 differ=0", answer, mode);
        }
    }

    @Test
    void aComponentThatKeepsItsCountOutsideItselfDiffersAndTheReplaySaysWhere() throws Exception {
        String answer = askedThenReplayed(true, "--replayable=true", "--logic=main");
        String[] lines = answer.split("\n");
        assertEquals("ok replay ticks=3 sends=6 differ=6", lines[0], answer);
        assertTrue(lines[1].startsWith("#1 0 test.replay.answered recorded="), lines[1]);
        assertEquals("  now a #4", lines[2], "what the replay sent there");
        assertEquals(1 + 6 * 2, lines.length, "each difference listed, with what the replay sent; the run's not kept");
    }

    @Test
    void aRunThatKeepsItsSendsSaysWhatItSentBesideWhatTheReplayDid() throws Exception {
        for (String mode : new String[]{"--logic=thread", "--logic=main"}) {
            String[] lines = askedThenReplayed(true, "--replayable=values", mode).split("\n");
            assertEquals("ok replay ticks=3 sends=6 differ=6", lines[0], mode);
            assertTrue(lines[2].startsWith("  now a #"), lines[2]);
            assertTrue(lines[3].startsWith("  was a #"), lines[3]);
            assertNotEquals(lines[2].substring(6), lines[3].substring(6), "the count kept outside moved on");
            assertEquals(1 + 6 * 3, lines.length, mode);
        }
    }

    @Test
    void aRunLaunchedWithoutReplayableKeepsNothingAndSaysSo() throws Exception {
        assertTrue(askedThenReplayed(false, "--logic=main").startsWith("err replay: this run keeps no recording"));
    }
}
