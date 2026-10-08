package com.shadowphoenix.dietlog.food;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import static org.junit.Assert.*;

public final class FoodSearchTest {
    @Test public void unicodeAndPunctuationNormalizeWithoutLosingTelugu() {
        assertEquals("cafe curd rice", FoodSearch.normalize(" CAFÉ / curd-rice "));
        assertEquals("పెసరట్టు", FoodSearch.normalize("పెసరట్టు"));
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals("idli", FoodSearch.normalize("IDLI"));
        } finally { Locale.setDefault(before); }
    }

    @Test public void allWordsAreRequiredInAnyOrderWithPrefixMatching() {
        assertEquals("\"rice*\" AND \"tam*\"", new FoodSearch.Query("rice tam").match());
        assertEquals("\"amul*\" AND \"milk*\"", new FoodSearch.Query("AMUL milk").match());
    }

    @Test public void commonCatalogueMisspellingsUseCanonicalFoodNames() {
        FoodSearch.Query q = new FoodSearch.Query("chiken curry");
        assertEquals("chicken curry", q.text);
        assertTrue(q.spellingAdjusted);
        assertEquals("paneer", new FoodSearch.Query("panner").text);
        assertEquals("biryani", new FoodSearch.Query("biriyani").text);
    }

    @Test public void syntaxAndWildcardsFromInputCannotBecomeOperators() {
        assertEquals("\"milk*\" AND \"or*\" AND \"name*\" AND \"rice*\"",
                new FoodSearch.Query("milk\" OR name:rice* % _").match());
        assertEquals("", new FoodSearch.Query("% _ ' : *").match());
    }

    @Test public void pastedQueriesAreBoundedAndBarcodesStayExact() {
        assertEquals(8, new FoodSearch.Query("one two three four five six seven eight nine ten").tokens.size());
        assertTrue(new FoodSearch.Query("890100000001").barcode);
        assertFalse(new FoodSearch.Query("890100 oats").barcode);
        assertTrue(new FoodSearch.Query(String.join("", Collections.nCopies(1000,"a"))).text.length() <= 128);
    }

    @Test public void cookedAliasOutranksPackagedMixAndCategoryOnlyMatch() {
        FoodSearch.Query q = new FoodSearch.Query("upma");
        int cooked = FoodSearch.score(q,"semolina upma suji rava upma","","upma\nrava upma","", "INDB", true);
        int packaged = FoodSearch.score(q,"upma mix","example","","", "OFF", true);
        int category = FoodSearch.score(q,"breakfast powder","example","","upma mixes", "OFF", true);
        assertTrue(cooked < packaged);
        assertTrue(packaged < category);
    }

    @Test public void brandTermsAreNotDiscardedForGenericFoods() {
        FoodSearch.Query q = new FoodSearch.Query("milk amul");
        assertTrue(FoodSearch.score(q,"milk","amul","","", "OFF",true)
                < FoodSearch.score(q,"milk","","","", "USDA",true));
    }

    @Test public void exactTeluguAliasesRankBeforeOtherRiceDishes() {
        FoodSearch.Query q = new FoodSearch.Query("అన్నం");
        assertTrue(FoodSearch.score(q,"boiled rice","","అన్నం","", "INDB",true)
                < FoodSearch.score(q,"curd rice","","పెరుగు అన్నం","", "INDB",true));
    }

    @Test public void typoCorrectionHandlesSwapsAndEditsWithBoundedAlternatives() {
        assertEquals(1, FoodSearch.distance("briyani","biryani"));
        assertEquals(1, FoodSearch.distance("chiken","chicken"));
        assertEquals(Arrays.asList("paneer"), FoodSearch.corrections("panner", Arrays.asList(
                new FoodSearch.Term("rice",100), new FoodSearch.Term("paneer",10))));
        assertFalse(FoodSearch.canCorrect("dal"));
        assertFalse(FoodSearch.canCorrect("పప్పు"));
        assertTrue(FoodSearch.corrections("milk", Arrays.asList(new FoodSearch.Term("masala",10))).isEmpty());
    }

    @Test public void closeMatchesPreferTheClosestSpelling() {
        FoodSearch.Query q = new FoodSearch.Query("chiken curry");
        assertTrue(FoodSearch.score(q,"chicken curry","","","", "INDB",true,true)
                < FoodSearch.score(q,"chickpeas curry","","","", "INDB",true,true));
    }

    @Test public void completenessBreaksTiesWithoutHidingAnExactProduct() {
        FoodSearch.Query q = new FoodSearch.Query("milk");
        assertTrue(FoodSearch.score(q,"milk","","","", "OFF",true)
                < FoodSearch.score(q,"milk","","","", "OFF",false));
        assertTrue(FoodSearch.score(q,"milk","","","", "OFF",false)
                < FoodSearch.score(q,"milk powder","","","", "OFF",true));
    }
}
