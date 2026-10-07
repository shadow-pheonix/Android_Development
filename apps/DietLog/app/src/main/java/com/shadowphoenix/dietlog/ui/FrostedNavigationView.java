package com.shadowphoenix.dietlog.ui;

import android.annotation.TargetApi;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;

/** Blurs only the backdrop crop behind the floating bar, never the bar's icons/labels. */
public final class FrostedNavigationView extends LinearLayout {
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds = new RectF();
    private final Path clip = new Path();
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::refreshBackdrop;
    private View source;
    private RenderNode node;
    private Bitmap fallback;
    private int[] pixels;
    private long lastCapture;
    private boolean dirty = true;
    private final RectF selectionBounds = new RectF();
    private final RectF selectionStart = new RectF();
    private final RectF selectionTarget = new RectF();
    private final FrostedSurfaceDrawable selection;
    private View selectedTab;
    private ValueAnimator selectionAnimator;
    private boolean uiMotionAllowed;

    public FrostedNavigationView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        selection = new FrostedSurfaceDrawable(context, FrostedSurfaceDrawable.Material.SELECTION);
    }

    /** The target must be a direct tab child; Add remains a separate action. */
    public void setSelectedTab(View tab) {
        if (tab == null || tab.getParent() != this) throw new IllegalArgumentException("Tab must belong to navigation");
        if (selectedTab == tab) return;
        selectedTab = tab;
        cancelSelectionAnimation();
        if (tab.getWidth() == 0 || tab.getHeight() == 0) {
            requestLayout();
            return;
        }
        updateSelectionTarget();
        if (selectionBounds.isEmpty() || !uiMotionAllowed || !isShown() || !isAttachedToWindow()
                || !UiMotion.preferencesAllowMotion(getContext())) {
            finishSelectionAnimation();
            return;
        }
        selectionStart.set(selectionBounds);
        selectionAnimator = ValueAnimator.ofFloat(0, 1);
        selectionAnimator.setDuration(450);
        selectionAnimator.setInterpolator(new PathInterpolator(0.25f, 0.1f, 0.25f, 1f));
        selectionAnimator.addUpdateListener(animation -> {
            if (!uiMotionAllowed || !UiMotion.preferencesAllowMotion(getContext())) {
                finishSelectionAnimation();
                return;
            }
            float fraction = (float) animation.getAnimatedValue();
            selectionBounds.set(
                    selectionStart.left + (selectionTarget.left - selectionStart.left) * fraction,
                    selectionStart.top + (selectionTarget.top - selectionStart.top) * fraction,
                    selectionStart.right + (selectionTarget.right - selectionStart.right) * fraction,
                    selectionStart.bottom + (selectionTarget.bottom - selectionStart.bottom) * fraction);
            invalidate();
        });
        selectionAnimator.start();
    }

    public void setUiMotionAllowed(boolean allowed) {
        uiMotionAllowed = allowed;
        if (!allowed) finishSelectionAnimation();
    }

    private void updateSelectionTarget() {
        if (selectedTab != null) selectionTarget.set(selectedTab.getLeft(), selectedTab.getTop(),
                selectedTab.getRight(), selectedTab.getBottom());
    }

    private void cancelSelectionAnimation() {
        if (selectionAnimator != null) {
            selectionAnimator.cancel();
            selectionAnimator.removeAllUpdateListeners();
            selectionAnimator = null;
        }
    }

    private void finishSelectionAnimation() {
        // View's constructor can dispatch visibility callbacks before field initialization.
        if (selectionBounds == null) return;
        cancelSelectionAnimation();
        updateSelectionTarget();
        selectionBounds.set(selectionTarget);
        invalidate();
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // Size, insets and RTL changes use actual laid-out tab bounds.
        if (changed || selectionBounds.isEmpty() || (selectedTab != null
                && (selectionTarget.left != selectedTab.getLeft() || selectionTarget.top != selectedTab.getTop()
                || selectionTarget.right != selectedTab.getRight() || selectionTarget.bottom != selectedTab.getBottom()))) {
            finishSelectionAnimation();
        }
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        if (!selectionBounds.isEmpty()) {
            int save = canvas.save();
            canvas.translate(selectionBounds.left, selectionBounds.top);
            selection.setBounds(0, 0, Math.round(selectionBounds.width()), Math.round(selectionBounds.height()));
            selection.draw(canvas);
            canvas.restoreToCount(save);
        }
        super.dispatchDraw(canvas);
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) finishSelectionAnimation();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) finishSelectionAnimation();
    }

    public void setBackdropSource(View source) { this.source = source; refreshBackdrop(); }

    public void refreshBackdrop() { dirty = true; invalidate(); }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnScrollChangedListener(scrollListener);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        bounds.set(0, 0, w, h);
        float radius = 30 * getResources().getDisplayMetrics().density;
        clip.reset(); clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
        dirty = true;
    }

    @Override public void draw(Canvas canvas) {
        int save = canvas.save();
        canvas.clipPath(clip);
        if (source != null && getWidth() > 0 && getHeight() > 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated()) {
                drawHardwareBackdrop(canvas);
            } else {
                drawFallbackBackdrop(canvas);
            }
        }
        super.draw(canvas);
        canvas.restoreToCount(save);
    }

    @TargetApi(31)
    private void drawHardwareBackdrop(Canvas canvas) {
        if (node == null) {
            node = new RenderNode("DietLog frosted navigation backdrop");
            float blur = 22 * getResources().getDisplayMetrics().density;
            node.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP));
        }
        if (dirty || !node.hasDisplayList()) {
            node.setPosition(0, 0, getWidth(), getHeight());
            Canvas recording = node.beginRecording(getWidth(), getHeight());
            try { captureBackdrop(recording); } finally { node.endRecording(); }
            dirty = false;
        }
        canvas.drawRenderNode(node);
    }

    private void drawFallbackBackdrop(Canvas canvas) {
        // A quarter-resolution crop plus a bounded blur keeps Android 10/11 work small.
        int w = Math.max(1, (getWidth() + 3) / 4), h = Math.max(1, (getHeight() + 3) / 4);
        if (fallback == null || fallback.getWidth() != w || fallback.getHeight() != h) {
            fallback = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            pixels = new int[w * h];
            dirty = true;
            lastCapture = 0;
        }
        long now = SystemClock.uptimeMillis();
        if (dirty && (lastCapture == 0 || now - lastCapture >= 64)) {
            Canvas recording = new Canvas(fallback);
            recording.scale(w / (float) getWidth(), h / (float) getHeight());
            captureBackdrop(recording);
            fallback.getPixels(pixels, 0, w, 0, 0, w, h);
            FrostedBlur.blur(pixels, w, h, 4);
            fallback.setPixels(pixels, 0, w, 0, 0, w, h);
            lastCapture = now;
            dirty = false;
        } else if (dirty) {
            postInvalidateDelayed(64);
        }
        canvas.drawBitmap(fallback, null, bounds, bitmapPaint);
    }

    private void captureBackdrop(Canvas canvas) {
        canvas.drawColor(0xFF081219);
        int save = canvas.save();
        // Both views are direct children of appRoot. Capturing source excludes this bar.
        canvas.translate(-getLeft(), -getTop());
        View parent = (View) getParent();
        Drawable background = parent.getBackground();
        if (background != null) background.draw(canvas);
        canvas.translate(source.getLeft(), source.getTop());
        source.draw(canvas);
        canvas.restoreToCount(save);
    }

    @Override protected void onDetachedFromWindow() {
        finishSelectionAnimation();
        if (getViewTreeObserver().isAlive()) getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
        if (node != null) node.discardDisplayList();
        node = null;
        // Release references; bitmaps may still be referenced by an in-flight RenderThread frame.
        fallback = null;
        pixels = null;
        super.onDetachedFromWindow();
    }
}
