package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Launch;
import dev.vexelray.framework.core.Open;
import dev.vexelray.framework.core.Opened;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Open} as a part sends it: answered by the framework, on the job lane, with an {@link Opened} for the same
 * target saying what went wrong (Vexplore FN-4). Only targets that fail before the desktop's opener are asked for, so
 * the test starts no program.
 */
final class LinksTest {

    private static final AppInfo DEMO = new AppInfo("demo", "Demo", 800, 600);

    @Test
    void anOpenIsAnsweredOnItsTopicWithTheSameTarget() throws Exception {
        Shell shell = new Shell(Launch.parse(new String[0], "demo", Set.of()), DEMO);
        try {
            Links.serve(shell);
            BlockingQueue<Opened> answers = new ArrayBlockingQueue<>(4);
            BlockingQueue<String> on = new ArrayBlockingQueue<>(4);
            shell.bus().subscribe(Opened.TOPIC, o -> {
                on.add(Thread.currentThread().getName());
                answers.add(o);
            });

            shell.bus().publish(Open.TOPIC, new Open("  "));

            Opened answer = answers.poll(5, TimeUnit.SECONDS);
            assertEquals("  ", answer.target());
            assertFalse(answer.opened());
            assertEquals("there is nothing to open", answer.problem());
            String thread = on.poll(1, TimeUnit.SECONDS);
            assertTrue(thread.startsWith("vexel-job-"), "answered from " + thread);
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void anAnswerWithNoProblemIsOpened() {
        assertTrue(Opened.done("C:/a.txt").opened());
        assertTrue(new Opened("C:/a.txt", null).opened(), "no problem is none, not null, across a lane");
        assertFalse(new Opened("C:/a.txt", "no opener").opened());
    }
}
