package dev.vexelray.framework.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds one configuration value to a constructor parameter, by key.
 *
 * <p>Resolved by generated code against the typed accessors of {@code Settings} — {@code getInt},
 * {@code getBoolean}, {@code getString} and the rest — chosen by the parameter's declared type at compile time.
 * There is no reflective binder, no type coercion at runtime, and no metadata: a parameter declared {@code int}
 * compiles to a {@code getInt} call, and a parameter declared as a type nothing can supply becomes a build
 * error rather than a startup one. Until the processor exists, a wiring reads {@code Settings} itself and
 * {@code AppInfo.settingKeys} is typed out by hand — which that method calls the sharpest single argument for
 * generating this.
 *
 * <p><b>Four sources, one precedence, stated once.</b> Today each application resolves its own configuration
 * inline and each picks its own order; the scaffold reads {@code System.getProperty("automation", "off")} in
 * the middle of a factory method, and window bounds come from a settings file read somewhere else entirely.
 * The order here is:
 *
 * <ol>
 *   <li>an explicit command-line override, {@code --key=value}</li>
 *   <li>a system property of the same key, {@code -Dkey=value}</li>
 *   <li>the user's settings file, {@code $HOME/.{appName}/settings.properties}</li>
 *   <li>{@link #def()}</li>
 * </ol>
 *
 * <p>Highest wins, and each level is more specific to <em>this launch</em> than the one beneath it: a flag beats
 * a property beats a remembered preference beats a default. A missing or malformed value falls through to the
 * next source rather than failing — which is {@code Settings}' own documented policy, kept rather than
 * reinvented: <i>"a malformed value falls back to the caller's default, same policy as a missing one"</i>.
 * Settings are a convenience, and an application must not refuse to launch over a preferences file.
 *
 * <p><b>Inert.</b> Nothing reads this annotation yet; the above is its specification. See
 * {@link dev.vexelray.framework.api} for which half of this package is built.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.CLASS)
public @interface Setting {

    /** The key, as it appears in the settings file and as {@code --key=} / {@code -Dkey=} accept it. */
    String value();

    /**
     * The value when no source supplies one, as text — parsed at compile time into the parameter's type, so a
     * default that does not parse is a build error rather than a fallback that silently never worked.
     */
    String def() default "";
}
