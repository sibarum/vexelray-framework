package ${packageName};

import dev.vexelray.framework.api.VexelApp;

/**
 * The release edition's application declaration: {@link ${className}}'s facts and no starters. In particular no
 * {@code AutomationStarter}, and the pom drops {@code vexelray-framework-automation} from the class path under
 * {@code -Pnative-release}, so a shipped binary cannot open a driving socket. The debug edition is
 * {@code src/edition-debug}; keep the two annotations identical apart from {@code starters}.
 */
@VexelApp(name = ${className}.APP, title = ${className}.TITLE, width = ${className}.W, height = ${className}.H,
        icon = ${className}.ICON)
final class ${className}App {

    private ${className}App() {
    }
}
