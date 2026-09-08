package dev.vexelray.framework.core;

import dev.vexelray.framework.api.FrameStage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The one ordering in this framework that costs a frame of latency when it is wrong. */
final class FrameHooksTest {

    @Test
    void hooksRunInStageOrderRegardlessOfRegistrationOrder() {
        List<String> log = new ArrayList<>();
        FrameHooks hooks = new FrameHooks()
                .add(FrameStage.SETTLE, () -> log.add("settle"))
                .add(FrameStage.APP, () -> log.add("app"))
                .add(FrameStage.INPUT, () -> log.add("input"))
                .add(FrameStage.CLOCK, () -> log.add("clock"))
                .seal();

        hooks.run();

        // Input before clock is the ordering the stack documents a reason for: the frame that presents a value
        // is the frame that computed it.
        assertEquals(List.of("input", "clock", "app", "settle"), log);
    }

    @Test
    void registrationOrderIsKeptWithinOneStage() {
        List<String> log = new ArrayList<>();
        new FrameHooks()
                .add(FrameStage.APP, () -> log.add("first"))
                .add(FrameStage.APP, () -> log.add("second"))
                .add(FrameStage.APP, () -> log.add("third"))
                .seal()
                .run();

        assertEquals(List.of("first", "second", "third"), log);
    }

    @Test
    void theWalkIsRepeatableAcrossFrames() {
        List<String> log = new ArrayList<>();
        FrameHooks hooks = new FrameHooks().add(FrameStage.APP, () -> log.add("tick")).seal();

        hooks.run();
        hooks.run();
        hooks.run();

        assertEquals(3, log.size());
    }

    @Test
    void sealingIsIdempotentAndDoesNotDuplicateHooks() {
        List<String> log = new ArrayList<>();
        FrameHooks hooks = new FrameHooks().add(FrameStage.APP, () -> log.add("tick")).seal().seal();

        hooks.run();

        assertEquals(1, hooks.size());
        assertEquals(1, log.size());
    }

    @Test
    void addingAfterSealIsRefusedRatherThanSilentlyIgnored() {
        FrameHooks hooks = new FrameHooks().seal();
        assertThrows(IllegalStateException.class, () -> hooks.add(FrameStage.APP, () -> {
        }));
    }

    @Test
    void stagesReportTheWalkOrder() {
        FrameHooks hooks = new FrameHooks()
                .add(FrameStage.SETTLE, () -> {
                })
                .add(FrameStage.INPUT, () -> {
                })
                .seal();

        assertEquals(List.of(FrameStage.INPUT, FrameStage.SETTLE), List.of(hooks.stages()));
    }

    @Test
    void noHooksIsNotAnError() {
        new FrameHooks().seal().run();
    }
}
