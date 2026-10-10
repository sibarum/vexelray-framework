package ${packageName};

import dev.vexelray.framework.api.VexelApp;
import dev.vexelray.framework.automation.AutomationStarter;

/**
 * The debug edition's application declaration: {@link ${className}}'s facts, plus {@link AutomationStarter}, the
 * driving socket — off unless {@code --automation} or {@code -Dautomation} asks, and loopback-only when it is,
 * because it hands whoever reaches it full control of the application's input.
 *
 * <p>The processor generates {@code ${className}AppWiring} from this, and it is what {@code mvn compile exec:exec},
 * the tests and {@code -Pnative} compile. The release edition, {@code src/edition-release}, declares the same
 * application with no starters, and {@code -Pnative-release} compiles that one instead and drops the automation
 * modules from the class path, so the binary that ships links no socket at all. Keep the two annotations identical
 * apart from {@code starters}.
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
