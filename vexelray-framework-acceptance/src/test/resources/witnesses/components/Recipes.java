package ${packageName};

import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.api.Setting;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.framework.shell.LivenessPolicy;
import dev.vexelray.framework.shell.NamedWindow;
import dev.vexelray.framework.shell.Windows;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.widget.TitleBar;

import java.time.Duration;

/**
 * The witness's parts: the template's look, a liveness policy chosen by a setting, the tree, and the tool window.
 *
 * <p>The components and the view are not here. They are declared on their own classes and the wiring is generated
 * from them, which is what this witness is checking: that a component-heavy application needs no wiring written.
 */
@Configuration(components = {Counter.class, Worker.class, Echo.class, Wedger.class, Isolated.class})
final class Recipes {

    @Provides
    Appearance look() {
        return Appearance.of(Look.THEME, Length.rem(${className}.MIN_W_EM), Length.rem(${className}.MIN_H_EM));
    }

    /**
     * Two policies, so both halves of the guarantee can be driven. {@code recover} interrupts a stalled lane, which
     * frees a wedge that is interruptible (the view shows the stall from the watchdog's own message); {@code exit}
     * keeps the framework's default action, report then exit, with the threshold and the grace shortened so a test
     * can wait for it.
     */
    @Provides
    LivenessPolicy liveness(@Setting(value = "witness.policy", def = "recover") String mode) {
        if (mode.equals("exit")) {
            return new LivenessPolicy() {
                @Override
                public Duration stallThreshold() {
                    return Duration.ofSeconds(1);
                }

                @Override
                public Duration exitGrace() {
                    return Duration.ofSeconds(5);
                }
            };
        }
        return new LivenessPolicy() {
            @Override
            public Duration stallThreshold() {
                return Duration.ofSeconds(3);
            }

            @Override
            public void onStall(Stall stall, Context context) {
                context.interrupt(stall.lane());
            }
        };
    }

    /** The tool window, declared with the trees and claimed by the framework; the view opens and closes it by message. */
    @Provides
    ToolWindow toolWindow(Windows windows) {
        return new ToolWindow(windows.named(NamedWindow.of(ToolWindow.KEY, "Tool", ToolWindow.W, ToolWindow.H)
                .satellite()));
    }

    @Provides
    Ui ui(Gui gui, TitleBar titleBar) {
        return new Ui(gui, titleBar);
    }
}
