package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Launch;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Sender;
import sibarum.atchung.Topic;
import sibarum.probe.Cause;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code @Destination} as the generated wiring puts it on its topics, asserted without a GPU: a connection per key,
 * written in order on a thread of its own and read on another when the part is also a source; a message for a key
 * with no connection dropped; one heard while its connection opens waiting for it; a newer request closing the old
 * connection before opening its own; a read that ignores an interrupt ended by the close; and a destination opened at
 * start writing what was queued before it closes at shutdown.
 */
final class DestinationsTest {

    private static final AppInfo DEMO = new AppInfo("demo", "Demo", 800, 600);

    record Open(String peer) {
        static final Topic<Open> TOPIC = Topic.edge(Open.class, Open::peer);
    }

    record Typed(String peer, String text) {
        static final Topic<Typed> TOPIC = Topic.edge(Typed.class, Typed::peer);
    }

    record Hangup(String peer) {
        static final Topic<Hangup> TOPIC = Topic.edge(Hangup.class, Hangup::peer);
    }

    record Ended(String peer, String why) {
        static final Topic<Ended> TOPIC = Topic.edge(Ended.class);
    }

    record Echo(String peer, String text) {
        static final Topic<Echo> TOPIC = Topic.edge(Echo.class);
    }

    /** A line for the one log opened at start: unkeyed. */
    record Logged(String text) {
        static final Topic<Logged> TOPIC = Topic.edge(Logged.class);
    }

    /**
     * A connection read and written, as a pipe pair is: what is written is echoed back to be read, and a read ignores an
     * interrupt, as a process's output does, so only closing ends it.
     */
    static final class Conn {
        final String peer;
        final List<String> written = Collections.synchronizedList(new ArrayList<>());
        final BlockingQueue<String> incoming = new LinkedBlockingQueue<>();
        volatile boolean closed;

        Conn(String peer) {
            this.peer = peer;
        }

        void write(String text) {
            written.add(text);
            incoming.add(text);
        }

        /** The next line, or null once closed: deaf to interrupts. */
        String read() {
            while (true) {
                if (closed) {
                    return null;
                }
                try {
                    String s = incoming.poll(10, TimeUnit.MILLISECONDS);
                    if (s != null) {
                        return s;
                    }
                } catch (InterruptedException ignored) {
                    // as a process's output stream: an interrupt does not end the read
                }
            }
        }
    }

    private static Shell shell() {
        return new Shell(Launch.parse(new String[0], "demo", Set.of()), DEMO);
    }

    /** The source half: echo each line back as it is read, until the connection closes. */
    private static Ended echo(Open request, Conn conn, Sender<Echo> echoes) {
        for (String line; (line = conn.read()) != null; ) {
            echoes.send(new Echo(request.peer(), line + " on " + Thread.currentThread().getName()));
        }
        return new Ended(request.peer(), "closed");
    }

    @Test
    void eachKeysMessagesReachItsOwnConnectionInOrderAndItsRunReadsThem() throws Exception {
        Shell shell = shell();
        BlockingQueue<Echo> echoes = new ArrayBlockingQueue<>(16);
        BlockingQueue<Ended> ends = new ArrayBlockingQueue<>(4);
        List<Conn> opened = Collections.synchronizedList(new ArrayList<>());
        try {
            Sender<Echo> send = Sources.sender(shell, "test.Line", Echo.TOPIC);
            Destinations.keyed(shell, "test.Line", "line", Open.TOPIC, q -> {
                        Conn c = new Conn(q.peer());
                        opened.add(c);
                        return c;
                    }, (q, c) -> echo(q, c, send), Ended.TOPIC, Hangup.TOPIC, c -> c.closed = true,
                    List.of(Destinations.write(Typed.TOPIC, (Conn c, Typed t) -> c.write(t.text()))));
            shell.bus().subscribe(Echo.TOPIC, echoes::add);
            shell.bus().subscribe(Ended.TOPIC, ends::add);

            shell.bus().publish(Open.TOPIC, new Open("a"));
            shell.bus().publish(Open.TOPIC, new Open("b"));
            shell.bus().publish(Typed.TOPIC, new Typed("a", "1"));
            shell.bus().publish(Typed.TOPIC, new Typed("b", "x"));
            shell.bus().publish(Typed.TOPIC, new Typed("a", "2"));
            shell.bus().publish(Typed.TOPIC, new Typed("a", "3"));

            List<Echo> heard = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                heard.add(echoes.poll(5, TimeUnit.SECONDS));
            }
            List<String> a = new ArrayList<>();
            for (Echo e : heard) {
                if (e.peer().equals("a")) {
                    a.add(e.text());
                }
            }
            assertEquals(List.of("1 on vexel-source-line", "2 on vexel-source-line", "3 on vexel-source-line"), a,
                    "a key's messages, in order, read back by its own run");
            assertTrue(heard.contains(new Echo("b", "x on vexel-source-line")), "and b's by b's");

            shell.bus().publish(Hangup.TOPIC, new Hangup("a"));
            assertEquals(new Ended("a", "closed"), ends.poll(5, TimeUnit.SECONDS),
                    "a stop closes the connection, which ends a read that ignores the interrupt");
            Conn first = opened.stream().filter(c -> c.peer.equals("a")).findFirst().orElseThrow();
            assertTrue(first.closed);
        } finally {
            shell.disposer().close();
        }
        assertEquals(new Ended("b", "closed"), ends.poll(2, TimeUnit.SECONDS),
                "shutdown stops every connection, and its reply is still published");
    }

    @Test
    void aMessageForAKeyWithNoConnectionIsDropped() throws Exception {
        Shell shell = shell();
        List<Conn> opened = Collections.synchronizedList(new ArrayList<>());
        try {
            Destinations.keyed(shell, "test.Line", "line", Open.TOPIC, q -> {
                        Conn c = new Conn(q.peer());
                        opened.add(c);
                        return c;
                    }, null, null, null, c -> c.closed = true,
                    List.of(Destinations.write(Typed.TOPIC, (Conn c, Typed t) -> c.write(t.text()))));

            shell.bus().publish(Open.TOPIC, new Open("a"));
            shell.bus().publish(Typed.TOPIC, new Typed("nobody", "lost"));
            shell.bus().publish(Typed.TOPIC, new Typed("a", "kept"));

            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (opened.isEmpty() || opened.get(0).written.isEmpty()) {
                assertTrue(System.nanoTime() < until, "a's message never arrived");
                Thread.sleep(5);
            }
            assertEquals(List.of("kept"), opened.get(0).written);
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void aMessageHeardWhileItsConnectionOpensWaitsForIt() throws Exception {
        Shell shell = shell();
        CountDownLatch mayOpen = new CountDownLatch(1);
        BlockingQueue<Conn> opened = new ArrayBlockingQueue<>(2);
        try {
            Destinations.keyed(shell, "test.Slow", "slow", Open.TOPIC, q -> {
                        try {
                            mayOpen.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        Conn c = new Conn(q.peer());
                        opened.add(c);
                        return c;
                    }, null, null, null, c -> c.closed = true,
                    List.of(Destinations.write(Typed.TOPIC, (Conn c, Typed t) -> c.write(t.text()))));

            shell.bus().publish(Open.TOPIC, new Open("a"));
            shell.bus().publish(Typed.TOPIC, new Typed("a", "early"));
            mayOpen.countDown();

            Conn c = opened.poll(5, TimeUnit.SECONDS);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (c.written.isEmpty()) {
                assertTrue(System.nanoTime() < until, "the early message never arrived");
                Thread.sleep(5);
            }
            assertEquals(List.of("early"), c.written);
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void aNewerRequestClosesTheOldConnectionBeforeItOpensItsOwn() throws Exception {
        Shell shell = shell();
        BlockingQueue<String> order = new ArrayBlockingQueue<>(8);
        BlockingQueue<Ended> ends = new ArrayBlockingQueue<>(4);
        try {
            Sender<Echo> send = Sources.sender(shell, "test.Line", Echo.TOPIC);
            Destinations.keyed(shell, "test.Line", "line", Open.TOPIC, q -> {
                        order.add("open " + q.peer());
                        return new Conn(q.peer());
                    }, (q, c) -> echo(q, c, send), Ended.TOPIC, null, c -> {
                        order.add("close " + c.peer);
                        c.closed = true;
                    },
                    List.of(Destinations.write(Typed.TOPIC, (Conn c, Typed t) -> c.write(t.text()))));
            shell.bus().subscribe(Ended.TOPIC, ends::add);

            shell.bus().publish(Open.TOPIC, new Open("a"));
            assertEquals("open a", order.poll(5, TimeUnit.SECONDS));
            shell.bus().publish(Open.TOPIC, new Open("a"));
            assertEquals("close a", order.poll(5, TimeUnit.SECONDS), "the old connection closes first");
            assertEquals("open a", order.poll(5, TimeUnit.SECONDS), "then the new one opens");
            assertEquals(new Ended("a", "closed"), ends.poll(5, TimeUnit.SECONDS), "the old run's reply");
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void oneOpenedAtStartWritesWhatWasQueuedBeforeItClosesAtShutdown() throws Exception {
        Shell shell = shell();
        Conn log = new Conn("log");
        CountDownLatch slow = new CountDownLatch(1);
        Destinations.atStart(shell, "test.Log", "log", () -> log, c -> c.closed = true,
                List.of(Destinations.write(Logged.TOPIC, (Conn c, Logged l) -> {
                    try {
                        slow.await(50, TimeUnit.MILLISECONDS);   // a write that takes a while
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    c.write(l.text());
                })));

        for (int i = 0; i < 5; i++) {
            shell.bus().publish(Logged.TOPIC, new Logged("line " + i));
        }
        shell.disposer().close();

        assertEquals(List.of("line 0", "line 1", "line 2", "line 3", "line 4"), log.written,
                "every line queued before shutdown is written");
        assertTrue(log.closed, "and then it is closed");
        assertFalse(Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals("vexel-destination-log") && t.isAlive()), "its writer has returned");
    }

    @Test
    void aRepliesCauseIsTheSourcesOwn() throws Exception {
        Shell shell = shell();
        BlockingQueue<Long> causes = new ArrayBlockingQueue<>(4);
        try {
            Destinations.keyed(shell, "test.Line", "line", Open.TOPIC, q -> new Conn(q.peer()),
                    (q, c) -> new Ended(q.peer(), "at once"), Ended.TOPIC, null, c -> c.closed = true,
                    List.of(Destinations.write(Typed.TOPIC, (Conn c, Typed t) -> c.write(t.text()))));
            shell.bus().subscribe(Ended.TOPIC, e -> causes.add(Cause.current()));

            shell.bus().publish(Open.TOPIC, new Open("a"));

            assertEquals(Cause.source("source test.Line", Cause.Kind.IO).index(),
                    Cause.sourceOf(causes.poll(5, TimeUnit.SECONDS)));
        } finally {
            shell.disposer().close();
        }
    }
}
