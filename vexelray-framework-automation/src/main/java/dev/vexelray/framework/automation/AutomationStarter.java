package dev.vexelray.framework.automation;

import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Default;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.api.Stability;
import dev.vexelray.framework.shell.Shell;

/**
 * The driving socket, as a starter: name it and an application can be driven.
 *
 * {@snippet :
 * @VexelApp(name = "calculator", title = "Calculator", starters = AutomationStarter.class)
 * public final class CalculatorApp { ... }
 * }
 *
 * <p><b>Two decisions, and both are written down where somebody reads them.</b> Depending on this module is what
 * links a listening socket into the binary at all; naming this class is what builds one. The second is not
 * redundant with the first, and it is not a {@code @ConditionalOnType} guard on a framework starter either — that
 * would make the dependency the whole decision, which is the drop-a-jar-and-get-behaviour arrangement
 * {@code VexelApp.starters} gives up on purpose: <i>"the set of things configuring this application is written at
 * the one place somebody reads when they want to know."</i> A starter that is named and not on the path is a
 * compile error, so the two cannot drift apart silently.
 *
 * <p><b>A {@code @Default}, though since phase 5 nothing backs it off.</b> A driver is opened on the {@code Shell},
 * which this starter, the framework's own, may take and an application part may not (the processor refuses it: the
 * shell reaches everything the framework does). So an application's say is naming this starter or not, and where the
 * socket binds is the launch's ({@link Driver#SETTING}). The {@code @Default} stays, for a framework starter that
 * might one day open a driver differently.
 *
 * <p>Built in {@code ATTACH}, which the generated wiring infers from the {@code Shell} parameter rather than being
 * told: the driver needs the window's real controls, and those exist only once the window does. Closed at shutdown,
 * because a {@code Driver} is {@code AutoCloseable}. Off unless the launch asks — see {@link Driver#SETTING}.
 */
@Configuration
@Stability(Stability.Level.EXPERIMENTAL)
public final class AutomationStarter {

    /** The socket, bound if this launch asked for one. Never null; see {@link Driver#bound()}. */
    @Default
    @Provides
    public Driver driver(Shell shell) {
        return Driver.open(shell);
    }
}
