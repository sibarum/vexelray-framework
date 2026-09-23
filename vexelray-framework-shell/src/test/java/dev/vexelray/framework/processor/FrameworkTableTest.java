package dev.vexelray.framework.processor;

import dev.vexelray.framework.core.Phase;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.Wiring;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The processor's table of framework values, held against the {@code Shell} and {@code Wiring} it names.
 *
 * <p>The processor names {@code -shell}'s types by string, because depending on {@code -shell} would put the
 * graphics stack on every application's processor path — and a string that must be spelled right compiles when it
 * is spelled wrong. So this is the one place both sides are on one classpath, and a renamed accessor fails here
 * rather than in the first application to be generated against it. In the processor's package, from {@code -shell}'s
 * tests, so the table can stay package-private. Reflection is a test's privilege; nothing reflects at startup.
 */
class FrameworkTableTest {

    @Test
    void everyRootIsAShellAccessorReturningTheTypeTheTableSays() throws Exception {
        for (Framework.Root root : Framework.ROOTS) {
            if (root.accessor().isEmpty()) {
                assertEquals(Shell.class.getName(), root.type(), "the accessor-less root is the shell itself");
                continue;
            }
            Method accessor;
            try {
                accessor = Shell.class.getMethod(root.accessor());
            } catch (NoSuchMethodException e) {
                fail("Framework names Shell." + root.accessor() + "(), and Shell has no such method");
                return;
            }
            assertEquals(root.type(), accessor.getReturnType().getName(),
                    "Shell." + root.accessor() + "() returns a different type than the table says");
        }
    }

    @Test
    void everyConstructionPhaseIsAWiringMethodTakingTheShell() throws Exception {
        for (Phase phase : Phase.values()) {
            if (phase == Phase.RUN) {
                continue;
            }
            Method method = Wiring.class.getMethod(Framework.method(phase), Shell.class);
            assertEquals(void.class, method.getReturnType());
        }
    }

    @Test
    void theNamedTypesAreTheOnesTheShellUses() throws Exception {
        assertEquals(Shell.class.getName(), Framework.SHELL);
        assertEquals(Wiring.class.getName(), Framework.WIRING);
        assertEquals(dev.vexelray.framework.shell.AppInfo.class.getName(), Framework.APP_INFO);
        assertEquals(dev.vexelray.framework.shell.Placement.class.getName(), Framework.PLACEMENT);
        assertEquals(dev.vexelray.framework.shell.Appearance.class.getName(), Framework.APPEARANCE);
        assertTrue(Shell.class.getMethod("place", String.class).getReturnType()
                .getName().equals(Framework.PLACEMENT));
        Shell.class.getMethod("appearance", dev.vexelray.framework.shell.Appearance.class);
        for (Class<?> type : new Class<?>[]{String.class, int.class, long.class, float.class, boolean.class}) {
            Shell.class.getMethod("setting", String.class, type);
        }
        Shell.class.getMethod("settingList", String.class);
    }
}
