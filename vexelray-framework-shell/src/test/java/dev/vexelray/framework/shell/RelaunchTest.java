package dev.vexelray.framework.shell;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starting this process again with settings changed: into a debug run, and out of one. */
class RelaunchTest {

    private static final List<String> JAVA = List.of("C:/jdk/bin/java.exe", "--enable-native-access=ALL-UNNAMED",
            "-Dautomation=0", "-Dautomation.control=7000", "-Dautomationish=keep", "-cp", "app.jar", "dev.example.App");

    @Test
    void dropTakesASettingOutOfTheArgumentsAndTheLaunchersProperties() {
        Relaunch r = Relaunch.of(JAVA, List.of("photo.jpg", "--automation=0", "--automation.control", "--zoom=2"))
                .drop("automation", "automation.control");
        assertEquals(List.of("C:/jdk/bin/java.exe", "--enable-native-access=ALL-UNNAMED", "-Dautomationish=keep",
                "-cp", "app.jar", "dev.example.App", "photo.jpg", "--zoom=2"), r.command());
    }

    @Test
    void setReplacesTheSettingAndPutsItAfterTheClassWhereItWins() {
        Relaunch r = Relaunch.of(List.of("pix.exe"), List.of("--automation=off", "photo.jpg")).set("automation", "0");
        assertEquals(List.of("pix.exe", "photo.jpg", "--automation=0"), r.command());
    }

    @Test
    void theProgramItselfIsNeverTakenForAProperty() {
        Relaunch r = Relaunch.of(List.of("-Dautomation=0"), List.of()).drop("automation");
        assertEquals(List.of("-Dautomation=0"), r.command());
    }

    @Test
    void theEnvironmentChangesAreCarriedAndAVariableCanBeTakenOut() {
        Relaunch r = Relaunch.of(List.of("pix.exe"), List.of())
                .environment("PROBE", "frame,bus")
                .environment("VEXELRAY_AUTOMATION", null);
        assertEquals("frame,bus", r.environment().get("PROBE"));
        assertTrue(r.environment().containsKey("VEXELRAY_AUTOMATION"));
        assertNull(r.environment().get("VEXELRAY_AUTOMATION"));
    }

    @Test
    void aLaunchWithNoProgramCannotBeStartedAndSaysSo() {
        assertTrue(Relaunch.of(List.of(), List.of("x")).unavailable().isPresent());
        assertFalse(Relaunch.of(List.of("pix.exe"), List.of()).unavailable().isPresent());
    }

    @Test
    void thisProcessCanSayHowItWasStarted() {
        // A test run is a JVM: the launcher, its options, the class path and the class. Enough to start it again.
        Relaunch r = Relaunch.current(List.of("--zoom=2"));
        assertFalse(r.unavailable().isPresent(), () -> r.toString());
        assertEquals("--zoom=2", r.command().getLast());
        assertTrue(r.command().size() > 2, r::toString);
    }
}
