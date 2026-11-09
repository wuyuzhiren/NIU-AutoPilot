package com.niu.autopilot.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * dBm 阈值刻度条 v2.13
 * 绘制 -90 ~ -40 横向数轴：
 *   - 靠近/离开两条阈值竖线（红/蓝）
 *   - 滞回区间半透明色带
 *   - 当前 RSSI 圆点（无信号时不画）
 * 供控制页状态卡实时显示信号与阈值关系。
 */
public class RssiScaleView extends View {
    private static final int AXIS_MIN = -90;
    private static final int AXIS_MAX = -40;

    private int nearThreshold = -60;
    private int farThreshold = -80;
    private boolean hasSignal = false;
    private float currentRssi = 0f;

    private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint farPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RssiScaleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        bandPaint.setColor(0x5500D4FF); // 滞回带：能量蓝
        axisPaint.setColor(0xFF94A3B8);
        axisPaint.setStrokeWidth(2f);
        nearPaint.setColor(0xFF00D4FF);
        nearPaint.setStrokeWidth(2f);
        farPaint.setColor(0xFFFF6B35);
        farPaint.setStrokeWidth(2f);
        dotPaint.setColor(0xFF00D4FF);
        textPaint.setColor(0xFF94A3B8);
        textPaint.setTextSize(22f);
        tickPaint.setColor(0xFF94A3B8);
        tickPaint.setStrokeWidth(1f);
    }

    public void setThresholds(int near, int far) {
        nearThreshold = near;
        farThreshold = far;
        invalidate();
    }

    /** rssi 传 null 表示当前无信号 */
    public void setCurrentRssi(Integer rssi) {
        hasSignal = (rssi != null);
        if (hasSignal) currentRssi = rssi;
        invalidate();
    }

    private float toX(float dbm) {
        float w = getWidth() - 24;
        float x = 14 + (dbm - AXIS_MIN) / (float) (AXIS_MAX - AXIS_MIN) * w;
        return Math.max(14, Math.min(getWidth() - 14, x));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float h = getHeight();
        float axisY = h - 10;      // 数轴底部
        float topY = 8;            // 色带/竖线顶部

        // 滞回带（靠近~离开之间）
        float xNear = toX(Math.max(nearThreshold, AXIS_MIN));
        float xFar = toX(Math.max(farThreshold, AXIS_MIN));
        float left = Math.min(xNear, xFar);
        float right = Math.max(xNear, xFar);
        canvas.drawRect(left, topY, right, axisY - 4, bandPaint);

        // 靠近阈值竖线（能量蓝）+ 标签
        canvas.drawLine(xNear, topY, xNear, axisY - 4, nearPaint);
        textPaint.setColor(0xFF00D4FF);
        canvas.drawText("靠近", Math.max(0, xNear - 24), topY + 16, textPaint);

        // 离开阈值竖线（能量橙）+ 标签
        canvas.drawLine(xFar, topY, xFar, axisY - 4, farPaint);
        textPaint.setColor(0xFFFF6B35);
        canvas.drawText("离开", Math.max(0, xFar - 24), topY + 32, textPaint);

        // 数轴
        canvas.drawLine(14, axisY, getWidth() - 14, axisY, axisPaint);

        // 刻度与标注
        int[] marks = {-90, -80, -70, -60, -50, -40};
        for (int m : marks) {
            float x = toX(m);
            canvas.drawLine(x, axisY - 5, x, axisY + 5, tickPaint);
            textPaint.setColor(0xFF94A3B8);
            canvas.drawText(String.valueOf(m), x - 16, axisY + 20, textPaint);
        }

        // 当前信号点
        if (hasSignal) {
            float x = toX(currentRssi);
            float cy = (topY + axisY) / 2f;
            dotPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, cy, 7f, dotPaint);
            dotPaint.setStyle(Paint.Style.STROKE);
            dotPaint.setStrokeWidth(3f);
            dotPaint.setColor(0xFF66BFFF);
            canvas.drawCircle(x, cy, 12f, dotPaint);
            dotPaint.setColor(0xFF00D4FF);
        }
    }
}
