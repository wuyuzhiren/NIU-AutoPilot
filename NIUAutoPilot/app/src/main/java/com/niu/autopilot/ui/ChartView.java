package com.niu.autopilot.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 实时 RSSI 折线图（纯自绘，无需第三方库）。
 * Y 轴固定 -40（顶，信号最强）~ -100（底，信号最弱），越靠上信号越强。
 */
public class ChartView extends View {
    private static final int Y_TOP = -40;      // 顶部
    private static final int Y_BOTTOM = -100;  // 底部

    private Paint linePaint, fillPaint, gridPaint, textPaint, dotPaint;
    private List<Integer> data = new ArrayList<>();

    public ChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initPaints();
    }

    private void initPaints() {
        linePaint = new Paint();
        linePaint.setColor(Color.parseColor("#2196F3"));
        linePaint.setStrokeWidth(4f);
        linePaint.setAntiAlias(true);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        fillPaint = new Paint();
        fillPaint.setColor(Color.parseColor("#332196F3"));
        fillPaint.setAntiAlias(true);
        fillPaint.setStyle(Paint.Style.FILL);

        gridPaint = new Paint();
        gridPaint.setColor(Color.parseColor("#22000000"));
        gridPaint.setStrokeWidth(1f);
        gridPaint.setAntiAlias(true);

        textPaint = new Paint();
        textPaint.setColor(Color.parseColor("#99000000"));
        textPaint.setTextSize(24f);
        textPaint.setAntiAlias(true);

        dotPaint = new Paint();
        dotPaint.setColor(Color.parseColor("#1565C0"));
        dotPaint.setAntiAlias(true);
        dotPaint.setStyle(Paint.Style.FILL);
    }

    /** 更新数据并触发重绘；传入 null 或空列表即清空 */
    public void updateData(List<Integer> newData) {
        this.data = (newData == null) ? new ArrayList<>() : new ArrayList<>(newData);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float padL = 56, padR = 20, padT = 20, padB = 32;
        float plotW = w - padL - padR;
        float plotH = h - padT - padB;
        if (plotW <= 0 || plotH <= 0) return;

        float span = Y_BOTTOM - Y_TOP; // 恒为负值

        // 横向网格 + Y 轴刻度
        int[] ticks = {-40, -60, -80, -100};
        for (int tick : ticks) {
            float y = padT + (tick - Y_TOP) * plotH / span;
            canvas.drawLine(padL, y, w - padR, y, gridPaint);
            canvas.drawText(String.valueOf(tick), 4, y + 8, textPaint);
        }

        if (data.isEmpty()) {
            canvas.drawText("等待信号…", padL, h / 2, textPaint);
            canvas.drawText("点击「开始采集」后从远处走向车辆", padL, h / 2 + 30, textPaint);
            return;
        }

        int n = data.size();
        float xStep = (n > 1) ? plotW / (n - 1) : 0;

        // 折线
        Path path = new Path();
        for (int i = 0; i < n; i++) {
            float x = padL + i * xStep;
            int v = data.get(i);
            if (v > Y_TOP) v = Y_TOP;
            if (v < Y_BOTTOM) v = Y_BOTTOM;
            float y = padT + (v - Y_TOP) * plotH / span;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        canvas.drawPath(path, linePaint);

        // 面积填充
        if (n >= 2) {
            Path area = new Path(path);
            area.lineTo(padL + (n - 1) * xStep, padT + plotH);
            area.lineTo(padL, padT + plotH);
            area.close();
            canvas.drawPath(area, fillPaint);
        }

        // 最新点高亮
        int lastV = data.get(n - 1);
        if (lastV > Y_TOP) lastV = Y_TOP;
        if (lastV < Y_BOTTOM) lastV = Y_BOTTOM;
        float lastX = padL + (n - 1) * xStep;
        float lastY = padT + (lastV - Y_TOP) * plotH / span;
        canvas.drawCircle(lastX, lastY, 7, dotPaint);

        // X 轴标签
        canvas.drawText("已采集 " + n + " 个点", padL, h - 8, textPaint);
    }
}
