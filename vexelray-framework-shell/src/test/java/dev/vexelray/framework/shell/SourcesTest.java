package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Launch;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Sender;
import sibarum.atchung.Topic;
import sibarum.probe.Cause;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code @Source} as the generated wiring puts it on its topics, asserted without a GPU: a run per key on a thread
 * of its own, sending as it goes; a newer request for a key replacing its run; a stop; a read that ignores an
 * interrupt ended through the {@code Closer}; and every run stopped, its reply published, when the application closes.
 */
final class SourcesTest {

    private static final AppInfo DEMO = new AppInfo("demo", "Demo", 800, 600);

    /** Start reading for {@code peer}; one run per peer. */
    record Attach(String peer) {
        static final Topic<Attach> TOPIC = Topic.edge(Attach.class, Attach::peer);
    }

    record Detach(String peer) {
        static final Topic<Detach> TOPIC = Topic.edge(Detach.class, Detach::peer);
    }

    record Detached(String peer, String why) {
        static final Topic<Detached> TOPIC = Topic.edge(Detached.class);
    }

    record Line(String peer, String text) {
        static final Topic<Line> TOPIC = Topic.edge(Line.class);
    }

    private static Shell shell() {
        return new Shell(Launch.parse(new String[0], "demo", Set.of()), DEMO);
    }

    /** A run that sends one line, then waits until it is stopped. */
    private static Detached waitForStop(Attach request, Sender<Line> lines) {
        lines.send(new Line(request.peer(), "hello from " + Thread.currentThread().getName()));
        try {
            new CountDownLatch(1).await();
            return new Detached(request.peer(), "never");
        } catch (InterruptedException e) {
            return new Detached(request.peer(), "stopped");
        }
    }

    @Test
    void eachKeyRunsOnAThreadOfItsOwnAndSendsAsItGoes() throws Exception {
        Shell shell = shell();
        BlockingQueue<Line> lines = new ArrayBlockingQueue<>(8);
        BlockingQueue<Long> causes = new ArrayBlockingQueue<>(8);
        BlockingQueue<Detached> ends = new ArrayBlockingQueue<>(8);
        try {
            Sender<Line> send = Sources.sender(shell, "test.Reader", Line.TOPIC);
            Sources.run(shell, "test.Reader", "reader", Attach.TOPIC, (q, closer) -> waitForStop(q, send),
                    Detached.TOPIC, Detach.TOPIC);
            shell.bus().subscribe(Line.TOPIC, l -> {
                lines.add(l);
                causes.add(Cause.current());
            });
            shell.bus().subscribe(Detached.TOPIC, ends::add);

            shell.bus().publish(Attach.TOPIC, new Attach("a"));
            shell.bus().publish(Attach.TOPIC, new Attach("b"));

            List<String> peers = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Line l = lines.poll(5, TimeUnit.SECONDS);
                assertTrue(l.text().equals("hello from vexel-source-reader"), l.text());
                peers.add(l.peer());
                assertEquals(Cause.source("source test.Reader", Cause.Kind.IO).index(),
                        Cause.sourceOf(causes.poll()), "a source's message carries the source's cause");
            }
            peers.sort(null);
            assertEquals(List.of("a", "b"), peers, "two keys, two runs, side by side");

            shell.bus().publish(Detach.TOPIC, new Detach("a"));
            assertEquals(new Detached("a", "stopped"), ends.poll(5, TimeUnit.SECONDS), "a stop ends only its key");
            assertEquals(null, ends.poll(100, TimeUnit.MILLISECONDS));
        } finally {
            shell.disposer().close();
        }
        assertEquals(new Detached("b", "stopped"), ends.poll(1, TimeUnit.SECONDS),
                "shutdown stops every run, and its reply is still published");
    }

    @Test
    void aNewerRequestForAKeyStopsItsRunAndStartsOnceItHasReturned() throws Exception {
        Shell shell = shell();
        BlockingQueue<String> order = new ArrayBlockingQueue<>(8);
        try {
            Sources.run(shell, "test.Reader", "reader", Attach.TOPIC, (q, closer) -> {
                order.add("start " + q.peer());
                Detached d = waitForStop(q, Sender.outside(shell.bus(), Line.TOPIC,
                        Cause.source("test", Cause.Kind.IO)));
                order.add("end " + q.peer());
                return d;
            }, Detached.TOPIC, null);

            shell.bus().publish(Attach.TOPIC, new Attach("a"));
            assertEquals("start a", order.poll(5, TimeUnit.SECONDS));
            shell.bus().publish(Attach.TOPIC, new Attach("a"));
            assertEquals("end a", order.poll(5, TimeUnit.SECONDS), "the old run returns first");
            assertEquals("start a", order.poll(5, TimeUnit.SECONDS), "then the new one starts");
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void aReadThatIgnoresAnInterruptIsEndedByClosingWhatTheRunHandedOver() throws Exception {
        Shell shell = shell();
        BlockingQueue<Detached> ends = new ArrayBlockingQueue<>(4);
        PipedOutputStream writer = new PipedOutputStream();
        try (InputStream stream = new PipedInputStream(writer)) {
            CountDownLatch reading = new CountDownLatch(1);
            Sources.run(shell, "test.Child", "child", Attach.TOPIC, (q, closer) -> {
                closer.closeOnStop(stream);
                reading.countDown();
                try {
                    return new Detached(q.peer(), "read " + stream.read());
                } catch (IOException e) {
                    return new Detached(q.peer(), "closed");
                }
            }, Detached.TOPIC, Detach.TOPIC);
            shell.bus().subscribe(Detached.TOPIC, ends::add);

            shell.bus().publish(Attach.TOPIC, new Attach("p"));
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            shell.bus().publish(Detach.TOPIC, new Detach("p"));

            assertEquals(new Detached("p", "closed"), ends.poll(5, TimeUnit.SECONDS));
        } finally {
            shell.disposer().close();
            writer.close();
        }
    }
}
