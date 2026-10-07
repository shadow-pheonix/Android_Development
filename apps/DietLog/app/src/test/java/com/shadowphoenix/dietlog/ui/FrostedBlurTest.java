package com.shadowphoenix.dietlog.ui;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class FrostedBlurTest {
    @Test public void uniformBackdropRetainsItsColor() {
        int[] pixels = new int[35];
        Arrays.fill(pixels, 0xFF285F71);
        int[] original = pixels.clone();
        FrostedBlur.blur(pixels, 7, 5, 6);
        assertArrayEquals(original, pixels);
    }

    @Test public void brightDetailIsSoftenedSymmetricallyWithoutColorBleeding() {
        int[] pixels = new int[81];
        Arrays.fill(pixels, 0xFF000000);
        pixels[40] = 0xFF00FF00;
        FrostedBlur.blur(pixels, 9, 9, 1);
        int center = (pixels[40] >> 8) & 255;
        assertTrue(center > 0 && center < 255);
        assertTrue(((pixels[39] >> 8) & 255) > 0);
        assertEquals(pixels[39], pixels[41]);
        assertEquals(pixels[31], pixels[49]);
        for (int color : pixels) {
            assertEquals(0xFF000000, color & 0xFFFF00FF);
        }
    }

    @Test public void narrowBackdropAndLargeRadiusRemainBounded() {
        int[] pixels = {0xFF347A9C};
        FrostedBlur.blur(pixels, 1, 1, 100);
        assertEquals(0xFF347A9C, pixels[0]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidDimensionsAreRejected() {
        FrostedBlur.blur(new int[2], 3, 2, 4);
    }
}
