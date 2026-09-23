/**
 * The vocabulary an application is written in.
 *
 * <h2>Which half of this is built</h2>
 *
 * <p><b>The annotations in this package are checked, and an application's wiring is generated from them.</b>
 * {@code vexelray-framework-processor} reads them while an application compiles. First it turns every rule a
 * declaration can decide into a compile error: the colour rule's main-thread and lane halves (T2.1–T2.3), a
 * provider holding a component (T3.7), two providers for one type in one mode, and the shape of each
 * annotation's use. Then, where the compilation has a {@link VexelApp}, it writes that application's
 * {@code Wiring}: constructor and provider calls in the phase each part's parameters put it in, a frame-stage
 * array, a startup branch per run mode, and a reverse-order close. The project builder's {@code vexel-desktop}
 * template is written this way, and {@code -Pacceptance} builds and drives one.
 *
 * <p>Each annotation's page says so at its foot — <b>checked and generated</b>, or only <b>checked</b> — and where
 * a promise on a page is not yet kept, the page says that too.
 *
 * <p><b>What a compile error cannot catch yet.</b> A method body: the processor reads declarations. A
 * hand-written {@code Wiring} that depends backwards across a {@code Phase}, or a lambda handed to a lane that
 * captures a main-thread value, still compiles and is wrong at run time. A generated wiring cannot depend
 * backwards, because it has no phase that was not inferred. {@code Shell}'s accessors throw at
 * startup, naming the phase, which is a backstop and not the mechanism. The house rule is that <i>a compile error
 * beats a startup error beats a runtime error</i>, and moving these leftward is the whole reason the mechanism is
 * a processor.
 *
 * <h2>The design</h2>
 *
 * <p>Every annotation here is read at <b>compile time</b> by the framework's annotation processor, which emits
 * plain Java: constructor calls in dependency order, a frame-stage array, and a reverse-order close. None of it
 * is read at runtime, so none of it needs reachability metadata, and an application's wiring costs a native
 * binary what the equivalent hand-written {@code main} would have cost.
 *
 * <p><b>Nothing here is discovered.</b> The processor reads the elements of the compilation it was given —
 * {@code RoundEnvironment.getElementsAnnotatedWith} — plus types it resolves <em>by name</em>:
 * {@code Elements.getTypeElement} for a {@link dev.vexelray.framework.api.ConditionalOnType} guard, and the
 * class literals in {@link dev.vexelray.framework.api.VexelApp#starters()}. It never walks the classpath
 * looking for annotated types. A build-time scan would be Spring's runtime scan moved one step to the left:
 * still proportional to the size of the classpath rather than to what the application uses, still paid on every
 * compile, and still leaving no auditable record of which jar contributed what. This is also the house
 * precedent — {@code atchung/elektroq}'s processor reads its round and nothing else.
 *
 * <p><b>Retention is {@link java.lang.annotation.RetentionPolicy#CLASS}</b>, and the choice is load-bearing in
 * both directions. Not {@code RUNTIME}: nothing reflects, so runtime visibility would buy nothing and would put
 * the annotations in the image's reflective metadata. Not {@code SOURCE} either, which is the tempting answer
 * for a compile-time-only vocabulary — the processor reads annotations off elements it resolved by name, and
 * those elements are usually <em>compiled</em>: {@link dev.vexelray.framework.api.MainThread} on a type from
 * another repo, {@link dev.vexelray.framework.api.Default} on a starter's provider, a
 * {@link dev.vexelray.framework.api.Setting} on a constructor parameter in a jar. Resolving the element is not
 * the problem; {@code SOURCE} would erase the annotation once the element was in hand.
 */
package dev.vexelray.framework.api;
