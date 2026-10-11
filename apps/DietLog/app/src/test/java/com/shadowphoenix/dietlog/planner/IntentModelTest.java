package com.shadowphoenix.dietlog.planner;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class IntentModelTest {
    @Test
    public void duplicateLogsDoNotBecomeMultipleDaysOrOverrideExplicitIntent() {
        List<IntentModel.Observation> logs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) logs.add(new IntentModel.Observation("rice", 1, 100));
        IntentModel model = new IntentModel(logs, 100);
        PlannerProfile p = new PlannerProfile();
        assertEquals(1, model.distinctDays("rice", 1));
        assertTrue(model.score("rice", 1, p) < FoodPreference.OKAY.score);
        p.ratings.put("rice", FoodPreference.NEVER);
        assertEquals(Double.NEGATIVE_INFINITY, model.score("rice", 1, p), 0);
        p.ratings.put("rice", FoodPreference.AVOID);
        assertEquals(Double.NEGATIVE_INFINITY, model.score("rice", 1, p), 0);
        p.ratings.put("rice", FoodPreference.OKAY);
        assertEquals(1, model.score("rice", 1, p), 0);
    }

    @Test
    public void learningUsesMealAndRecencyAndCanBeDisabled() {
        PlannerProfile p = new PlannerProfile();
        IntentModel m =
                new IntentModel(
                        Arrays.asList(
                                new IntentModel.Observation("egg", 0, 100),
                                new IntentModel.Observation("egg", 0, 99),
                                new IntentModel.Observation("egg", 0, 101)),
                        100);
        assertEquals(2, m.distinctDays("egg", 0));
        assertEquals(0, m.score("egg", 3, p), 0);
        assertTrue(m.score("egg", 0, p) > 0);
        p.learnFromHistory = false;
        assertEquals(0, m.score("egg", 0, p), 0);
    }

    @Test
    public void tasteAndCostFeedbackRemainSeparate() {
        Map<IntentModel.Feedback, Integer> feedback = new EnumMap<>(IntentModel.Feedback.class);
        feedback.put(IntentModel.Feedback.EXPENSIVE, 2);
        assertEquals(0, IntentModel.feedbackPenalty(feedback, 10, 1, 0), 0);
        assertTrue(IntentModel.feedbackPenalty(feedback, 10, 1, 100) > 0);
    }

    @Test
    public void likesAreBoundedAndDoNotEraseIndependentCostFeedback() {
        Map<IntentModel.Feedback, Integer> f = new EnumMap<>(IntentModel.Feedback.class);
        f.put(IntentModel.Feedback.LIKED, 100);
        assertEquals(-2, IntentModel.feedbackPenalty(f, 10, 1, 100), 0);
        f.put(IntentModel.Feedback.EXPENSIVE, 2);
        assertEquals(0, IntentModel.feedbackPenalty(f, 10, 1, 100), 0);
    }

    @Test
    public void rejectsInvalidBudgetsAndEmptyRoutine() {
        PlannerProfile p = new PlannerProfile();
        p.dailyBudget = Double.NaN;
        try {
            p.validate();
            fail();
        } catch (IllegalArgumentException expected) {
        }
        p.dailyBudget = 100;
        p.weeklyBudget = 350;
        assertEquals(50, p.effectiveDailyBudget(), 0);
        Arrays.fill(p.meals, false);
        try {
            p.validate();
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void inferredSelectionSignalsStayWeakAndCanBeDisabled() {
        Map<IntentModel.Feedback, Integer> counts = new EnumMap<>(IntentModel.Feedback.class);
        counts.put(IntentModel.Feedback.SELECTED, 100);
        assertEquals(-1, IntentModel.feedbackPenalty(counts, 20, 1, 100, true), 0);
        assertEquals(0, IntentModel.feedbackPenalty(counts, 20, 1, 100, false), 0);
        counts.clear();
        counts.put(IntentModel.Feedback.SWAPPED, 100);
        assertEquals(1, IntentModel.feedbackPenalty(counts, 20, 1, 100, true), 0);
    }

    @Test
    public void routineNeedsSeveralBroadlyLoggedDaysAndOnlyGentlyAdjustsShares() {
        List<IntentModel.Observation> logs = new ArrayList<>();
        for (int d = 100; d > 96; d--) {
            logs.add(new IntentModel.Observation("a", 0, d, 700));
            logs.add(new IntentModel.Observation("b", 1, d, 200));
            logs.add(new IntentModel.Observation("c", 3, d, 100));
        }
        IntentModel m = new IntentModel(logs, 100);
        assertTrue(m.hasLearnedRoutine());
        double[] shares = m.routineShares();
        assertTrue(shares[0] > 0.25);
        assertTrue(shares[0] < 0.4);
        assertTrue(shares[3] > 0.1);
        double sum = 0;
        for (double share : shares) sum += share;
        assertEquals(1, sum, 0.000001);
        assertFalse(
                new IntentModel(Arrays.asList(logs.get(0), logs.get(1)), 100).hasLearnedRoutine());
    }

    @Test
    public void learningIdentityIsOrderIndependentAndChangesWhenDistinctEvidenceChanges() {
        List<IntentModel.Observation> logs =
                new ArrayList<>(
                        Arrays.asList(
                                new IntentModel.Observation("rice", 1, 100),
                                new IntentModel.Observation("egg", 0, 99)));
        String first = new IntentModel(logs, 100).identity();
        Collections.reverse(logs);
        assertEquals(first, new IntentModel(logs, 100).identity());
        logs.add(new IntentModel.Observation("rice", 1, 99));
        assertNotEquals(first, new IntentModel(logs, 100).identity());
    }
}
