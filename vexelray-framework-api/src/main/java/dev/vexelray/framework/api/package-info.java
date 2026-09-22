/**
 * The vocabulary an application is written in.
 *
 * <h2>Which half of this is built</h2>
 *
 * <p><b>The annotations in this package are checked, and none of them is generated from yet.</b>
 * {@code vexelray-framework-processor} reads them while an application compiles and turns every rule that a
 * declaration can decide into a compile error: the colour rule's main-thread and lane halves (T2.1–T2.3), a
 * provider holding a component (T3.7), two providers for one type in one mode, and the shape of each
 * annotation's use. It emits no source. The only types here that anything executes are the two enums —
 * {@link FrameStage}, walked by {@code FrameHooks}, and {@link RunMode}, read off {@code Launch} — and both are
 * used by hand-written code.
 *
 * <p>So each annotation's page says one of two things at its foot. <b>Checked</b>: its rules hold at compile
 * time. <b>Read</b>: the processor consults it for another check, and what it will <em>make</em> the wiring do
 * is still a specification. Either way, anything on these pages about what generated code does — constructor
 * calls, a frame-stage array, a startup branch — is the brief generation will be written against, following the
 * sequencing in {@code docs/architecture.md}: <i>"the processor's job becomes reproduce these files"</i>.
 *
 * <p><b>What a compile error cannot catch yet.</b> A hand-written {@code Wiring} is a method body, and the
 * processor reads declarations: one that depends backwards across a {@code Phase}, or puts a main-thread value
 * into a lambda handed to a lane, still compiles and is wrong at run time. {@code Shell}'s accessors throw at
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
