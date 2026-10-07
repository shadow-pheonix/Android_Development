package com.shadowphoenix.dietlog.ui;

/** Bounded, opaque backdrop blur for the small navigation crop on Android 10/11. */
public final class FrostedBlur {
    private FrostedBlur() {}

    public static void blur(int[] pixels, int width, int height, int radius) {
        if (width <= 0 || height <= 0 || pixels.length != width * height) {
            throw new IllegalArgumentException("Invalid backdrop dimensions");
        }
        if (radius <= 0) return;
        radius = Math.min(radius, 32);
        int[] scratch = new int[pixels.length];
        // Three separable box passes approximate a Gaussian without bitmap-sized kernels.
        for (int pass = 0; pass < 3; pass++) {
            horizontal(pixels, scratch, width, height, radius);
            vertical(scratch, pixels, width, height, radius);
        }
    }

    private static void horizontal(int[] src, int[] dst, int width, int height, int radius) {
        int window = 2 * radius + 1;
        for (int y = 0; y < height; y++) {
            int r = 0, g = 0, b = 0, row = y * width;
            for (int i = -radius; i <= radius; i++) {
                int color = src[row + clamp(i, width)];
                r += (color >> 16) & 255; g += (color >> 8) & 255; b += color & 255;
            }
            for (int x = 0; x < width; x++) {
                dst[row + x] = 0xFF000000 | ((r / window) << 16) | ((g / window) << 8) | (b / window);
                int remove = src[row + clamp(x - radius, width)];
                int add = src[row + clamp(x + radius + 1, width)];
                r += ((add >> 16) & 255) - ((remove >> 16) & 255);
                g += ((add >> 8) & 255) - ((remove >> 8) & 255);
                b += (add & 255) - (remove & 255);
            }
        }
    }

    private static void vertical(int[] src, int[] dst, int width, int height, int radius) {
        int window = 2 * radius + 1;
        for (int x = 0; x < width; x++) {
            int r = 0, g = 0, b = 0;
            for (int i = -radius; i <= radius; i++) {
                int color = src[clamp(i, height) * width + x];
                r += (color >> 16) & 255; g += (color >> 8) & 255; b += color & 255;
            }
            for (int y = 0; y < height; y++) {
                dst[y * width + x] = 0xFF000000 | ((r / window) << 16) | ((g / window) << 8) | (b / window);
                int remove = src[clamp(y - radius, height) * width + x];
                int add = src[clamp(y + radius + 1, height) * width + x];
                r += ((add >> 16) & 255) - ((remove >> 16) & 255);
                g += ((add >> 8) & 255) - ((remove >> 8) & 255);
                b += (add & 255) - (remove & 255);
            }
        }
    }

    private static int clamp(int value, int length) {
        return Math.max(0, Math.min(length - 1, value));
    }
}
