package com.shadowphoenix.dietlog.ui;

import android.app.DatePickerDialog;
import android.content.res.Resources;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.DatePicker;
import android.widget.TextView;

import com.shadowphoenix.dietlog.R;

/** Medium native calendars, retaining normal touch targets and font scaling. */
public final class CalendarDialogs {
    private CalendarDialogs() {}

    public static void show(DatePickerDialog dialog) {
        DatePicker picker = dialog.getDatePicker();
        Resources resources = picker.getResources();
        float maxTextSize = resources.getDimension(R.dimen.calendar_max_text_size);
        // Framework header typography is fixed in its layout on recent Android
        // versions. Cap oversized header text through public View APIs instead
        // of depending on internal Android resource IDs or scaling the widget.
        ViewTreeObserver.OnGlobalLayoutListener layout = () -> compactText(picker, maxTextSize);
        picker.getViewTreeObserver().addOnGlobalLayoutListener(layout);
        dialog.setOnDismissListener(ignored -> {
            ViewTreeObserver observer = picker.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(layout);
        });
        compactText(picker, maxTextSize);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            int preferred = resources.getDimensionPixelSize(R.dimen.calendar_dialog_width);
            int margin = resources.getDimensionPixelSize(R.dimen.calendar_dialog_margin);
            int available = Math.max(1, resources.getDisplayMetrics().widthPixels - 2 * margin);
            window.setLayout(Math.min(preferred, available), WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private static void compactText(View view, float maxSize) {
        // Adapter rows can restore their text appearance whenever they rebind.
        // Leave them to the native picker to avoid a rebind/relayout cycle.
        if (view instanceof AdapterView) return;
        if (view instanceof TextView && ((TextView) view).getTextSize() > maxSize) {
            ((TextView) view).setTextSize(TypedValue.COMPLEX_UNIT_PX, maxSize);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) compactText(group.getChildAt(i), maxSize);
        }
    }
}
