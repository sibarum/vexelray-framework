/**
 * Syntax highlighting for a VexelRay application: the stack's second starter, {@link
 * dev.vexelray.framework.highlight.HighlightStarter}, and the part it provides, {@link
 * dev.vexelray.framework.highlight.Highlighting}.
 *
 * <p><b>What is the stack's, and what is the application's.</b> The stack's: the sixteen bundled TextMate grammars
 * and their licence, which file is in which language and what each is called, TM4E's tokenizer behind one lock, the
 * limits past which a document is plain text, matching a token's most specific scope to a colour, a failure meaning no
 * colours, and the native-image metadata all of that needs. The application's: its colour table ({@link
 * dev.vexelray.framework.highlight.Colours}, a look) and any language of its own ({@link
 * dev.vexelray.framework.highlight.Language}), each provided as a type, so an application overrides only what it
 * needs.
 *
 * <p><b>Six types, and why each exists.</b> {@code Highlighting} is the service. {@code Colour} is one rule of a table
 * and {@code Colours} the ordered table, a type of its own because order decides and a provided type has to be named.
 * {@code Language} is a language to colour, one shape for a TextMate grammar and for a language with a {@link
 * dev.vexelray.framework.highlight.Lexer} of its own (the seam for one that has no grammar), and the bundled grammars
 * are languages of the same shape. {@code HighlightStarter} is what an application names. Everything an application
 * constructs is a final class with factories, not a record, so a field it gains is not a breaking change.
 *
 * <p><b>Where it sits.</b> On {@code -api} and {@code vexelray-gui-core} and not on {@code -shell}, so an application's
 * own {@code -core} module, where the job that colours a document lives and which cannot reach {@code -shell}, can
 * take it. The native-image metadata is in this jar, under {@code META-INF/native-image/dev.vexelray.framework/
 * vexelray-framework-highlight/}: the grammars, joni's {@code tables/*.bin}, and the two JDK methods TM4E finds by
 * name. No FFM flags, since nothing here binds native code.
 */
package dev.vexelray.framework.highlight;
