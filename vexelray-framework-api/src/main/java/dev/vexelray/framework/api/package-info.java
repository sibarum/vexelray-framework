/**
 * The vocabulary an application is written in.
 *
 * <h2>Which half of this is built</h2>
 *
 * <p><b>The ten annotations in this package are inert, and the processor that would read them does not exist
 * yet.</b> Nothing in this repository implements {@code AbstractProcessor}. The only types here that anything
 * executes are the two enums — {@link FrameStage}, walked by {@code FrameHooks}, and {@link RunMode}, read off
 * {@code Launch} — and both are used by hand-written code rather than by generated code.
 *
 * <p>Everything else on these pages is written in the present tense and is a <b>specification</b>: what the
 * processor will do, stated as the brief it will be written against. That is deliberate rather than careless,
 * and it follows from the sequencing recorded in {@code docs/architecture.md} — the wiring is written by hand
 * first, against the real {@code -shell}, so that <i>"the processor's job becomes reproduce these files"</i>.
 * Documentation written after the fact would describe whatever got built. This section exists because a
 * specification and a description read identically, and a reader is owed the difference.
 *
 * <p><b>So a claim on these pages that something "is a compile error" means it will be, and today is not.</b>
 * A hand-written {@code Wiring} that declares two providers for one type, takes a {@code @MainThread} value on
 * a worker component, or depends backwards across a {@code Phase} compiles cleanly and is wrong at run time.
 * The framework's answer in the meantime is one step to the right of where it belongs: {@code Shell}'s
 * accessors throw at startup, naming the phase, which is a backstop and not the mechanism. The house rule is
 * that <i>a compile error beats a startup error beats a runtime error</i>, and moving these leftward is the
 * whole reason the mechanism is a processor.
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
