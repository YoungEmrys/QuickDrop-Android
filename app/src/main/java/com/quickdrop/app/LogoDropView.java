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

/** Slow looping water-drop/ripple animation using the existing QuickDrop logo artwork. */
public class LogoDropView extends View {
    private final Bitmap logo;
    private final Bitmap drop;
    private final Bitmap ripple;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator animator;
    private float phase = 0f;
    private boolean running = false;
    private final int blue = 0xFF0055F5;

    public LogoDropView(Context context) {
        super(context);
        logo = BitmapFactory.decodeResource(context.getResources(), R.drawable.quickdrop_logo);
        // Crop the droplet and ripple from the supplied 512x512 logo artwork.
        drop = Bitmap.createBitmap(logo, 184, 112, 144, 180);
        ripple = Bitmap.createBitmap(logo, 104, 280, 304, 112);
        pulsePaint.setStyle(Paint.Style.STROKE);
        pulsePaint.setStrokeWidth(dp(1.8f));
        pulsePaint.setColor(0xAAFFFFFF);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setAnimating(boolean animate) {
        if (animate == running) return;
        running = animate;
        if (animate) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f);
                animator.setDuration(1650);
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
        canvas.drawColor(blue);
        // Keep the original ripple artwork anchored while the drop falls into it.
        Rect rippleSrc = new Rect(0, 0, ripple.getWidth(), ripple.getHeight());
        RectF rippleDst = new RectF(w * .10f, h * .57f, w * .90f, h * .88f);
        canvas.drawBitmap(ripple, rippleSrc, rippleDst, paint);

        float dropTop = h * (.08f + .34f * phase);
        Rect dropSrc = new Rect(0, 0, drop.getWidth(), drop.getHeight());
        RectF dropDst = new RectF(w * .34f, dropTop, w * .66f, dropTop + h * .34f);
        canvas.drawBitmap(drop, dropSrc, dropDst, paint);

        if (running && phase > .60f) {
            float p = (phase - .60f) / .40f;
            pulsePaint.setAlpha((int)(150 * (1f - p)));
            float radius = dp(5) + dp(18) * p;
            canvas.drawCircle(w * .5f, h * .76f, radius, pulsePaint);
            canvas.drawCircle(w * .5f, h * .76f, radius * .58f, pulsePaint);
        }
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}
