package ${packageName};

import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.MainThread;
import dev.vexelray.framework.core.Lanes;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.api.Setting;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.framework.shell.LivenessPolicy;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.widget.TitleBar;
import sibarum.atchung.Atchung;

import java.time.Duration;

/**
 * The witness's parts: the template's look and model, the bench the components report to, a liveness policy
 * chosen by a setting, and a tree that publishes to the components.
 *
 * <p>The components themselves are not here. They are declared on their own classes and the wiring is generated
 * from them, which is what this witness is checking: that a component-heavy application needs no wiring written.
 */
@Configuration
final class Recipes {

    @Provides
    Appearance look() {
        return Appearance.of(Look.THEME, Length.em(${className}.MIN_W_EM), Length.em(${className}.MIN_H_EM));
    }

    @Provides
    Model model() {
        return new Model();
    }

    @Provides
    Bench bench() {
        return new Bench();
    }

    /**
     * Two policies, so both halves of the guarantee can be driven. {@code recover} reports a stalled lane on the
     * screen and interrupts it, which frees a wedge that is interruptible; {@code exit} keeps the framework's own
     * default action — report, then exit — with the threshold and the grace shortened so a test can wait for it.
     */
    @Provides
    LivenessPolicy liveness(@Setting(value = "witness.policy", def = "recover") String mode, Bench bench) {
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
                bench.stalled(stall.lane());
                context.interrupt(stall.lane());
            }
        };
    }

    /** The tool window's tree: a second window that can close before the application does. */
    @Provides
    ToolWindow toolWindow(Appearance look, Lanes lanes) {
        return new ToolWindow(look, lanes);
    }

    /** Its window, claimed once the GuiApp exists, and the button that opens it. */
    @Provides
    @MainThread
    AppWindow toolHost(GuiApp app, WindowMemory memory, ToolWindow tool, Ui ui) {
        AppWindow window = tool.claim(app, memory);
        ui.onTool(window::show);
        return window;
    }

    @Provides
    Ui ui(Gui gui, Model model, Bench bench, Atchung bus, TitleBar titleBar) {
        Ui ui = new Ui(gui, model, bench, bus, titleBar);
        model.onChange(ui::show);
        bench.onChange(ui::showBench);
        ui.show(model.doc());
        return ui;
    }
}
