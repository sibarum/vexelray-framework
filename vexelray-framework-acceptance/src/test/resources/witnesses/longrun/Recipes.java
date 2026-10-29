package ${packageName};

import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.widget.TitleBar;

/** The long-running witness's parts: the template's look and the tree. The logic is declared on its own classes. */
@Configuration(components = {Counter.class, Metronome.class}, jobs = Measure.class)
final class Recipes {

    @Provides
    Appearance look() {
        return Appearance.of(Look.THEME, Length.rem(${className}.MIN_W_EM), Length.rem(${className}.MIN_H_EM));
    }

    @Provides
    Ui ui(Gui gui, TitleBar titleBar) {
        return new Ui(gui, titleBar);
    }
}
