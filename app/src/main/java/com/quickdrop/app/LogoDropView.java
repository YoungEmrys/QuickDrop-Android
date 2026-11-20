package com.quickdrop.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Transparent drop animation using the original logo's own ripple artwork. */
public class LogoDropView extends View {
    private final Bitmap logo;
    private final Bitmap drop;
    private final Bitmap ripple;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private ValueAnimator animator;
    private float phase = 0f;
    private boolean running = false;

    public LogoDropView(Context context) {
        super(context);
        logo = BitmapFactory.decodeResource(context.getResources(), R.drawable.quickdrop_logo);
        drop = Bitmap.createBitmap(logo, 184, 112, 144, 180);
        ripple = Bitmap.createBitmap(logo, 104, 280, 304, 112);
        setBackgroundColor(android.graphics.Color.TRANSPARENT);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setAnimating(boolean animate) {
        if (animate == running) return;
        running = animate;
        if (animate) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f);
                animator.setDuration(2300);
                animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.setRepeatMode(ValueAnimator.REVERSE);
                animator.setInterpolator(new LinearInterpolator());
                animator.addUpdateListener(a -> { phase = (float) a.getAnimatedValue(); invalidate(); });
            }
            animator.start();
        } else {
            if (animator != null) animator.cancel();
            phase = 0f;
            invalidate();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        Rect rippleSrc = new Rect(0, 0, ripple.getWidth(), ripple.getHeight());
        RectF rippleDst = new RectF(w * .08f, h * .61f, w * .92f, h * .93f);
        canvas.drawBitmap(ripple, rippleSrc, rippleDst, paint);
        float dropTop = h * (.05f + .39f * phase);
        Rect dropSrc = new Rect(0, 0, drop.getWidth(), drop.getHeight());
        RectF dropDst = new RectF(w * .34f, dropTop, w * .66f, dropTop + h * .36f);
        canvas.drawBitmap(drop, dropSrc, dropDst, paint);
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}
