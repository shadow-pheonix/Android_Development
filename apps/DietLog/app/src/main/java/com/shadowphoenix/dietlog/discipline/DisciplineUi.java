package com.shadowphoenix.dietlog.discipline;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Small native view vocabulary; blue frost never inherits FuelNote's green surfaces. */
public final class DisciplineUi {
    public static final int BLUE = 0xFF8ABBFF;
    public static final int TEXT = 0xFFF2F6FF;
    public static final int MUTED = 0xFFC0CEE4;

    private DisciplineUi() {}

    public static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    public static LinearLayout column(Context c) {
        LinearLayout result = new LinearLayout(c);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    public static LinearLayout row(Context c) {
        LinearLayout result = new LinearLayout(c);
        result.setOrientation(LinearLayout.HORIZONTAL);
        result.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return result;
    }

    public static TextView text(Context c, String value, int size, int color, boolean bold) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setLineSpacing(dp(c, 3), 1);
        return view;
    }

    public static GradientDrawable surface(Context c, int accent, boolean selected) {
        GradientDrawable drawable =
                new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        selected
                                ? new int[] {blend(accent, 0xFF17253B, .34f), 0xF4192942}
                                : new int[] {0xEC263650, 0xE8142033});
        drawable.setCornerRadius(dp(c, 24));
        drawable.setStroke(dp(c, 1), selected ? blend(accent, 0xFF42536C, .55f) : 0x705D7596);
        return drawable;
    }

    private static int blend(int a, int b, float f) {
        return android.graphics.Color.rgb(
                Math.round(
                        android.graphics.Color.red(a) * f
                                + android.graphics.Color.red(b) * (1 - f)),
                Math.round(
                        android.graphics.Color.green(a) * f
                                + android.graphics.Color.green(b) * (1 - f)),
                Math.round(
                        android.graphics.Color.blue(a) * f
                                + android.graphics.Color.blue(b) * (1 - f)));
    }

    public static void clickable(View view, int accent, boolean selected) {
        Context c = view.getContext();
        view.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(0x35FFFFFF), surface(c, accent, selected), null));
        view.setFocusable(true);
        view.setClickable(true);
    }

    public static TextView button(Context c, String title, Runnable action) {
        return button(c, title, BLUE, action);
    }

    public static TextView button(Context c, String title, int accent, Runnable action) {
        TextView view = text(c, title, 14, accent, true);
        view.setGravity(android.view.Gravity.CENTER);
        view.setMinHeight(dp(c, 48));
        view.setMinWidth(dp(c, 48));
        view.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        clickable(view, accent, false);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    public static LinearLayout card(Context c) {
        LinearLayout card = column(c);
        card.setPadding(dp(c, 18), dp(c, 18), dp(c, 18), dp(c, 18));
        card.setBackground(surface(c, BLUE, false));
        return card;
    }

    public static void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(parent.getContext(), top);
        parent.addView(child, params);
    }

    public static void weighted(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
        p.setMarginStart(dp(parent.getContext(), margin));
        parent.addView(child, p);
    }

    public static LinearLayout page(Activity activity, int background) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundResource(background);
        LinearLayout body = column(activity);
        body.setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 28));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        activity.setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    int l, t, r, b;
                    if (Build.VERSION.SDK_INT >= 30) {
                        android.graphics.Insets bars =
                                insets.getInsets(
                                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                        l = bars.left;
                        t = bars.top;
                        r = bars.right;
                        b = bars.bottom;
                    } else {
                        l = insets.getSystemWindowInsetLeft();
                        t = insets.getSystemWindowInsetTop();
                        r = insets.getSystemWindowInsetRight();
                        b = insets.getSystemWindowInsetBottom();
                    }
                    v.setPadding(l, t, r, b);
                    return insets;
                });
        scroll.requestApplyInsets();
        return body;
    }
}
