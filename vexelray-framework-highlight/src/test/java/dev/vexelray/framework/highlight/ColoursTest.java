package dev.vexelray.framework.highlight;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Paint;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A colour table: which rule wins, and the standard table holding up in every look the stack ships. */
final class ColoursTest {

    private static final Color A = Color.rgb(0x111111);
    private static final Color B = Color.rgb(0x222222);
    private static final Color C = Color.rgb(0x333333);

    @Test
    void aRuleMatchesItsScopeAndWhatIsUnderItAtADot() {
        Colour keyword = Colour.of("keyword", A);
        assertTrue(keyword.matches("keyword"));
        assertTrue(keyword.matches("keyword.control.java"));
        assertFalse(keyword.matches("keywords"), "a prefix that is not at a dot is another scope");
        assertFalse(keyword.matches("key"));
    }

    @Test
    void theFirstRuleThatMatchesWins() {
        Colours colours = Colours.of(Colour.of("constant.numeric", A), Colour.of("constant", B));
        assertEquals(Paint.of(A), colours.paintOf("constant.numeric.java"));
        assertEquals(Paint.of(B), colours.paintOf("constant.language.java"));
        assertNull(colours.paintOf("string.quoted"));
        assertNull(colours.paintOf((String) null));
    }

    /** The innermost scope a rule colours decides, however early a rule for an outer one comes. */
    @Test
    void theMostSpecificScopeWinsOverTheTablesOrder() {
        Colours colours = Colours.of(Colour.of("meta.class", A), Colour.of("storage", B));
        assertEquals(Paint.of(B), colours.paintOf(List.of("source.java", "meta.class.java", "storage.modifier.java")));
        assertEquals(Paint.of(A), colours.paintOf(List.of("source.java", "meta.class.java", "entity.name.java")),
                "an inner scope no rule colours falls back to the next one out");
        assertNull(colours.paintOf(List.of("source.java")));
    }

    @Test
    void aTableIsACopyAndComparesByItsRules() {
        List<Colour> rules = new ArrayList<>(List.of(Colour.of("comment", A)));
        Colours colours = Colours.of(rules);
        rules.add(Colour.of("string", B));
        assertEquals(1, colours.rules().size(), "changing the list afterwards changes nothing");
        assertThrows(UnsupportedOperationException.class, () -> colours.rules().add(Colour.of("x", C)));
        assertEquals(Colours.of(Colour.of("comment", A)), colours);
        assertEquals(Colour.of("comment", A), Colour.of("comment", Paint.of(A)), "a colour is the fixed paint of it");
    }

    @Test
    void aRuleNamesOneScope() {
        assertThrows(IllegalArgumentException.class, () -> Colour.of("", A));
        assertThrows(IllegalArgumentException.class, () -> Colour.of("source.java keyword", A),
                "a selector of several scopes is not one this table matches");
        assertThrows(NullPointerException.class, () -> Colour.of("comment", (Paint) null));
    }

    // ---- the standard table ----

    static Stream<Theme> looks() {
        return Stream.of(Theme.DARK, Theme.LIGHT);
    }

    /** Every colour the standard table draws reads as text on the page, in both of the stack's looks. */
    @ParameterizedTest
    @MethodSource("looks")
    void everyStandardColourReadsOnThePage(Theme look) {
        for (Colour rule : Colours.standard().rules()) {
            double ratio = contrast(rule.paint().in(look), look.color(Role.PAGE));
            assertTrue(ratio >= 4.5, rule.scope() + " is " + String.format("%.2f", ratio) + ":1 on the page; it needs 4.5:1");
        }
    }

    /** Roles, so marked text follows a change of look with nothing re-marked; each has an answer in each look. */
    @Test
    void theStandardColoursAreRolesThatFollowTheLook() {
        for (Colour rule : Colours.standard().rules()) {
            assertInstanceOf(Role.class, rule.paint(), rule.scope() + " should be a role, not a fixed colour");
            assertNotEquals(rule.paint().in(Theme.DARK), rule.paint().in(Theme.LIGHT), rule.scope());
        }
    }

    /** Held once: two marks of the same text compare equal only if the table hands out the same paints. */
    @Test
    void theStandardTableIsOneTable() {
        assertSame(Colours.standard(), Colours.standard());
        assertSame(Colours.standard().paintOf("keyword"), Colours.standard().paintOf("storage.type"),
                "keywords and storage are one colour, held once");
    }

    /** The families are told apart: a keyword, a string, a number, a type and a comment are five colours. */
    @ParameterizedTest
    @MethodSource("looks")
    void theStandardFamiliesAreDistinct(Theme look) {
        List<Color> seen = new ArrayList<>();
        for (String scope : List.of("keyword", "string", "constant.numeric", "entity.name.type", "comment",
                "entity.name.function", "entity.name.tag")) {
            Color c = Colours.standard().paintOf(scope).in(look);
            assertFalse(seen.contains(c), scope + " shares a colour with another family");
            seen.add(c);
        }
    }

    private static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * linear(c.r()) + 0.7152 * linear(c.g()) + 0.0722 * linear(c.b());
    }

    private static double linear(float v) {
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
}
