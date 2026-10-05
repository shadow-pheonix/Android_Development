package com.shadowphoenix.dietlog.nutrition;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class MicronutrientValuesTest {
    @Test public void roundTripsKnownValuesAndIgnoresUnknownData() {
        MicronutrientValues values = MicronutrientValues.empty()
                .with(Micronutrient.CALCIUM, 123.4)
                .with(Micronutrient.VITAMIN_C, 18.0)
                .with(Micronutrient.SODIUM, 0.0);
        MicronutrientValues decoded = MicronutrientValues.decode(values.encode() + "|future_key=9|bad=x");
        assertEquals(123.4, decoded.get(Micronutrient.CALCIUM), 0.0001);
        assertEquals(18.0, decoded.get(Micronutrient.VITAMIN_C), 0.0001);
        assertEquals(0.0, decoded.get(Micronutrient.SODIUM), 0.0001);
        assertNull(decoded.get(Micronutrient.IRON));
    }

    @Test public void scalingPreservesUnknownInsteadOfTurningItIntoZero() {
        MicronutrientValues values = MicronutrientValues.empty().with(Micronutrient.IRON, 8.0);
        MicronutrientValues scaled = values.scaled(0.5);
        assertEquals(4.0, scaled.get(Micronutrient.IRON), 0.0001);
        assertFalse(scaled.has(Micronutrient.CALCIUM));
        assertTrue(scaled.has(Micronutrient.IRON));
    }
}
