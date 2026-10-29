package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Launch;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code @Job} as the generated wiring subscribes it, asserted without a GPU: the request runs on the job lane
 * and not on the publisher's thread, two requests do not wait for each other, the answer is published on the reply
 * topic, and what nothing declared halts.
 */
final class JobsTest {

    private static final AppInfo DEMO = new AppInfo("demo", "Demo", 800, 600);

    private static final Topic<String> ASK = Topic.of("test.job.ask", String.class);
    private static final Topic<Integer> ANSWER = Topic.of("test.job.answer", Integer.class);

    private static Shell shell() {
        return new Shell(Launch.parse(new String[0], "demo", Set.of()), DEMO);
    }

    @Test
    void aRequestRunsOnTheJobLaneAndItsAnswerIsPublished() throws Exception {
        Shell shell = shell();
        try {
            AtomicReference<String> ranOn = new AtomicReference<>();
            Jobs.reply(shell, "test.Length", ASK, s -> {
                ranOn.set(Thread.currentThread().getName());
                return s.length();
            }, ANSWER);
            BlockingQueue<Integer> answers = new ArrayBlockingQueue<>(4);
            shell.bus().subscribe(ANSWER, answers::add);

            shell.bus().publish(ASK, "four");

            assertEquals(4, answers.poll(5, TimeUnit.SECONDS));
            assertTrue(ranOn.get().startsWith("vexel-job-"), "ran on " + ranOn.get());
        } finally {
            shell.disposer().close();
        }
    }

    /** No order and no lane: a request stuck on a slow disk does not hold up the next one. */
    @Test
    void aSlowRequestDoesNotHoldUpTheNext() throws Exception {
        Shell shell = shell();
        try {
            CountDownLatch release = new CountDownLatch(1);
            Jobs.reply(shell, "test.Slow", ASK, s -> {
                if (s.equals("slow")) {
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return s.length();
            }, ANSWER);
            BlockingQueue<Integer> answers = new ArrayBlockingQueue<>(4);
            shell.bus().subscribe(ANSWER, answers::add);

            shell.bus().publish(ASK, "slow");
            shell.bus().publish(ASK, "quick");

            assertEquals(5, answers.poll(5, TimeUnit.SECONDS), "the quick one answers while the slow one waits");
            release.countDown();
            assertEquals(4, answers.poll(5, TimeUnit.SECONDS));
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void aJobRunForItsEffectPublishesNothing() throws Exception {
        Shell shell = shell();
        try {
            BlockingQueue<String> done = new ArrayBlockingQueue<>(4);
            Jobs.effect(shell, "test.Effect", ASK, done::add);

            shell.bus().publish(ASK, "written");

            assertEquals("written", done.poll(5, TimeUnit.SECONDS));
        } finally {
            shell.disposer().close();
        }
    }

    /** Ruled 2026-10-18: a failure nothing declared ends the application, and so does a reply owed and not given. */
    @Test
    void aThrowAndANullReplyBothGoToTheFaultPolicy() throws Exception {
        BlockingQueue<Throwable> faults = new ArrayBlockingQueue<>(4);
        Atchung.onFatal(faults::add);
        Shell shell = shell();
        try {
            Jobs.reply(shell, "test.Fragile", ASK, s -> {
                if (s.equals("throw")) {
                    throw new IllegalArgumentException("unexpected");
                }
                return null;
            }, ANSWER);

            shell.bus().publish(ASK, "throw");
            Throwable thrown = faults.poll(5, TimeUnit.SECONDS);
            assertTrue(String.valueOf(thrown).contains("job test.Fragile failed on test.job.ask"), String.valueOf(thrown));

            shell.bus().publish(ASK, "null");
            Throwable nothing = faults.poll(5, TimeUnit.SECONDS);
            assertTrue(String.valueOf(nothing).contains("a reply is owed"), String.valueOf(nothing));
        } finally {
            Atchung.onFatal(null);
            shell.disposer().close();
        }
    }

    @Test
    void aClosedJobTakesNoMoreRequests() throws Exception {
        Shell shell = shell();
        BlockingQueue<String> done = new ArrayBlockingQueue<>(4);
        Jobs.effect(shell, "test.Closed", ASK, done::add);
        shell.disposer().close();

        assertEquals(0, shell.bus().subscriberCount(ASK));
    }
}
