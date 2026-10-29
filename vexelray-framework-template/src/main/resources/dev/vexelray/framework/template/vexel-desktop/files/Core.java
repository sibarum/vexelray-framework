package ${packageName}.core;

import dev.vexelray.framework.api.Configuration;

/**
 * What this module brings the wiring: the jobs that touch the disk.
 *
 * <p>The annotation processor sees only the sources of the module it is compiling, so when {@code -gui} compiles,
 * everything here is a class file it would never construct. This list is how it hears of them: {@code -gui}'s
 * {@code ${className}App} names this class among its {@code starters}, and the generated wiring builds what is listed
 * here as if it were its own, and checks every message between them. A new job in this module goes on this list, or
 * nothing builds it.
 *
 * <p>The {@link Editor} is not listed. It is a ledger given the inbox it owns, and the wiring learns to build one of
 * those, and to list it here, when the framework gives a component its inbox (phase 4 of the 0.2 roadmap).
 */
@Configuration(jobs = {ReadFile.class, WriteFile.class, FindPath.class, CreateFile.class, ReadSession.class,
        WriteSession.class})
public final class Core {
}
