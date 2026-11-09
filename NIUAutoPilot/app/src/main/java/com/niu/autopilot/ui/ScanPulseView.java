package com.niu.autopilot.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * v2.39 扫描脉冲动画 — 增强版
 * - 3圈同心环扩散 + 透明度渐隐
 * - 未连接=蓝色，已连接=绿色
 * - 中心呼吸光点 + 旋转渐变底环
 */
public class ScanPulseView extends View {
    private static final int RING_COLOR = 0xFF1565C0;
    private static final int CONNECTED_COLOR = 0xFF43A047;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator animator;
    private float progress = 0f;
    private boolean running = false;
    private boolean connected = false;
    private float sweepAngle = 0f;

    public ScanPulseView(Context context, AttributeSet attrs) {
        super(context, attrs);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2.5f);
        ringPaint.setAntiAlias(true);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setAntiAlias(true);
        sweepPaint.setStyle(Paint.Style.STROKE);
        sweepPaint.setStrokeWidth(1.5f);
        sweepPaint.setAntiAlias(true);
    }

    public void startPulse() {
        if (running) return;
        running = true;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1800);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            sweepAngle = (progress * 360f) % 360f;
            invalidate();
        });
        animator.start();
    }

    public void stopPulse() {
        running = false;
        if (animator != null) { animator.cancel(); animator = null; }
        progress = 0f;
        invalidate();
    }

    public void setConnected(boolean c) { connected = c; invalidate(); }
    public boolean isPulsing() { return running; }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (running) startPulse();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) animator.cancel();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float maxR = Math.min(getWidth(), getHeight()) / 2f - 2;
        int baseColor = connected ? CONNECTED_COLOR : RING_COLOR;

        // 旋转渐变底环
        canvas.save();
        canvas.rotate(sweepAngle * 2, cx, cy);
        sweepPaint.setShader(new SweepGradient(cx, cy,
                new int[]{0x00000000, 0x40000000 | (baseColor & 0x00FFFFFF), 0x00000000},
                null));
        canvas.drawCircle(cx, cy, maxR, sweepPaint);
        canvas.restore();
        sweepPaint.setShader(null);

        // 3圈扩散环
        ringPaint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 3; i++) {
            float p = (progress + i * 0.33f) % 1f;
            float r = 3f + p * maxR;
            int alpha = (int) (220 * (1f - p));
            ringPaint.setColor(Color.argb(Math.max(0, alpha),
                    Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)));
            ringPaint.setStrokeWidth(2.5f - p * 1.5f);
            canvas.drawCircle(cx, cy, r, ringPaint);
        }

        // 中心呼吸光点
        dotPaint.setStyle(Paint.Style.FILL);
        float dotAlpha = 0.7f + 0.3f * (float) Math.sin(progress * Math.PI * 2);
        dotPaint.setColor(baseColor);
        dotPaint.setAlpha((int)(dotAlpha * 255));
        canvas.drawCircle(cx, cy, 4f, dotPaint);
        // 外圈光晕
        dotPaint.setAlpha((int)(dotAlpha * 60));
        canvas.drawCircle(cx, cy, 7f, dotPaint);
    }
}
