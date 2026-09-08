/**
 * The vocabulary an application is written in.
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
