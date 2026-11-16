package dev.vexelray.framework.template;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** What {@link ConcurrencyScan} finds, and the words it does not mistake for code. */
class ConcurrencyScanTest {

    private static List<String> found(String text) {
        return ConcurrencyScan.scanText("p/A.java", text).stream().map(h -> h.line() + " " + h.what()).toList();
    }

    @Test
    void wordsInCommentsStringsAndJavadocAreNotCode() {
        assertEquals(List.of(), found("""
                package p;
                /** A synchronized, volatile Thread from java.util.concurrent.atomic. */
                class A {
                    // synchronized (this) { volatile Thread t; }
                    /* java.util.concurrent.locks.LockSupport */
                    String s = "synchronized volatile Thread java.util.concurrent.Executor";
                    Object thread = null;
                    String threads() { return "ThreadGroup"; }
                }
                """));
    }

    @Test
    void eachConstructIsFoundWhereItIs() {
        assertEquals(List.of(
                "2 import java.util.concurrent.atomic.AtomicLong",
                "3 import java.util.concurrent.locks.LockSupport",
                "4 import java.util.concurrent.*",
                "5 import java.lang.Thread.State",
                "7 volatile field v",
                "8 java.util.concurrent.ConcurrentHashMap",
                "9 synchronized method m",
                "10 synchronized block",
                "11 Thread",
                "12 ThreadGroup",
                "12 ThreadGroup",
                "13 Thread"), found("""
                package p;
                import java.util.concurrent.atomic.AtomicLong;
                import java.util.concurrent.locks.LockSupport;
                import java.util.concurrent.*;
                import static java.lang.Thread.State;
                class A {
                    volatile int v;
                    Object m = new java.util.concurrent.ConcurrentHashMap<String, String>();
                    synchronized void m() {
                        synchronized (this) { }
                        Thread.currentThread().interrupt();
                        ThreadGroup g = new ThreadGroup("g");
                        new Thread(() -> { }).start();
                    }
                }
                """));
    }

    @Test
    void aTemplateSlotIsAnIdentifierOnItsOwnLine() {
        List<ConcurrencyScan.Hit> hits = ConcurrencyScan.scanText("x/B.java", """
                package ${packageName}.core;
                import ${packageName}.core.Messages;
                class ${className}B {
                    volatile int v;
                }
                """);
        assertEquals(1, hits.size());
        assertEquals("packageName.core.B", hits.get(0).type());
        assertEquals(4, hits.get(0).line());
    }

    @Test
    void aFileThatDoesNotParseIsRefusedRatherThanPassed() {
        assertThrows(AssertionError.class, () -> found("class A { synchronized"));
    }
}
