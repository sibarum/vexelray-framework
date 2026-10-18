package ${packageName};

import dev.vexelray.framework.api.VexelApp;
import dev.vexelray.framework.automation.AutomationStarter;

/**
 * The application declaration: {@link ${className}}'s facts, plus {@link AutomationStarter}, the automation sockets
 * (remote control and diagnostics), in every build, the release one included.
 *
 * <p>The sockets are off unless the launch asks ({@code --automation}, {@code VEXELRAY_AUTOMATION}) or a person
 * switches them on from the title bar's plug, and loopback-only when they are on, because remote control hands
 * whoever reaches it full control of the application's input. The plug's lamps show each one's port open or not and
 * anything connected now or earlier; {@code --automation.available=none} leaves them out of a run altogether. The
 * processor generates {@code ${className}AppWiring} from this.
 *
 * <p>{@code icon} is the mark the window wears: the same file {@code src/main/rc/${artifactId}.rc} links into the
 * executable, which the pom puts on the class path. Draw your own, render it to an {@code .ico}, and replace that
 * one file; see the README, <i>The icon</i>.
 */
@VexelApp(name = ${className}.APP, title = ${className}.TITLE, width = ${className}.W, height = ${className}.H,
        icon = ${className}.ICON, starters = AutomationStarter.class)
final class ${className}App {

    private ${className}App() {
    }
}
