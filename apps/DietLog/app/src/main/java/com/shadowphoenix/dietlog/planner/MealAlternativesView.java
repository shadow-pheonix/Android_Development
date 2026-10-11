package com.shadowphoenix.dietlog.planner;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import java.util.function.IntConsumer;

/** One recipe per page; swiping browses without selecting or logging a meal. */
public final class MealAlternativesView extends HorizontalScrollView {
    private int width, requestedPage, touchStartPage;
    private boolean flung;
    private IntConsumer changed;
    private final Runnable snap = () -> showPage(currentPage());
    private final Runnable positionAfterLayout =
            () -> {
                requestedPage = bounded(requestedPage);
                scrollTo(requestedPage * width, 0);
                notifyPage(requestedPage);
            };

    public MealAlternativesView(Context context) {
        super(context);
        setFillViewport(true);
        setHorizontalScrollBarEnabled(true);
        setOverScrollMode(View.OVER_SCROLL_NEVER);
    }

    public void setOnPageChanged(IntConsumer listener) {
        changed = listener;
    }

    public void move(int direction) {
        // Keep rapid button presses even while the previous page is still animating.
        showPage(requestedPage + direction);
    }

    private int count() {
        return getChildCount() == 0 ? 0 : ((LinearLayout) getChildAt(0)).getChildCount();
    }

    private int bounded(int page) {
        return Math.max(0, Math.min(Math.max(0, count() - 1), page));
    }

    private int currentPage() {
        return width == 0
                ? bounded(requestedPage)
                : bounded(Math.round(getScrollX() / (float) width));
    }

    private void notifyPage(int page) {
        if (changed != null) changed.accept(bounded(page));
    }

    public void showPage(int page) {
        removeCallbacks(snap);
        requestedPage = bounded(page);
        if (width > 0) smoothScrollTo(requestedPage * width, 0);
        notifyPage(requestedPage);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        width = w;
        if (getChildCount() > 0) {
            LinearLayout strip = (LinearLayout) getChildAt(0);
            for (int i = 0; i < strip.getChildCount(); i++) {
                View child = strip.getChildAt(i);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
                lp.width = w;
                child.setLayoutParams(lp);
            }
        }
        removeCallbacks(snap);
        removeCallbacks(positionAfterLayout);
        post(positionAfterLayout);
    }

    @Override
    public void fling(int velocityX) {
        flung = true;
        int page = currentPage();
        // A drag that already passed a page midpoint must not skip an extra page on release.
        if (Math.abs(velocityX) > 300)
            page =
                    velocityX > 0
                            ? Math.max(page, touchStartPage + 1)
                            : Math.min(page, touchStartPage - 1);
        showPage(page);
    }

    private void beginGesture() {
        removeCallbacks(snap);
        touchStartPage = currentPage();
        requestedPage = touchStartPage;
        flung = false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        // Children receive the first touch; record its page before intercepting a later drag.
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) beginGesture();
        return super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) beginGesture();
        boolean handled = super.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP && !flung
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) post(snap);
        return handled;
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (width > 0) notifyPage(currentPage());
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(snap);
        removeCallbacks(positionAfterLayout);
        super.onDetachedFromWindow();
    }
}
