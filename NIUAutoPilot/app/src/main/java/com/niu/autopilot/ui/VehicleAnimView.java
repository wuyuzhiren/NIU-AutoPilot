package com.niu.autopilot.ui;

import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import com.niu.autopilot.R;

import java.util.HashMap;
import java.util.Map;

/**
 * v2.67 科幻版车辆动画View
 * - 素材：紫色小牛NX 45°视角（nx_body_alpha 正常 / nx_bucket_alpha 坐桶打开）
 * - 背景：深空渐变 + 科幻网格 + 水平扫描线（HUD风格）
 * - 开机：能量环扩散 + 白色大灯光束 + 车身提亮 + 光晕呼吸
 * - 关机：暗化 + 双闪警示灯
 * - 坐桶：整图切换坐桶打开图 + 弹起动画
 * - 待机：呼吸光点提示
 */
public class VehicleAnimView extends View {

    private static Bitmap cachedCarAlpha = null;
    private static boolean alphaProcessing = false;
    private static final Map<String, Bitmap> staticCache = new HashMap<>();

    private Bitmap carBitmap;          // 正常车身（已透明底）
    private Bitmap carBitmapBucket;    // 坐桶打开（已透明底）
    private float carLeft, carTop, carW, carH;

    // 状态
    private boolean powerOn = false;
    private boolean seatOpen = false;
    private boolean hazardOn = false;

    // 动画值
    private float beamAlpha = 0f;        // 大灯亮度 0~1
    private float haloAlpha = 0f;        // 光晕亮度 0~1
    private float seatLift = 0f;         // 坐桶弹起位移 0~1
    private float hazardLeftAlpha = 0f;
    private float hazardRightAlpha = 0f;
    private float scanLightProgress = -1f; // 通电扫描 -1=off, 0~1=position
    private float glowPulse = 0f;        // 呼吸光晕相位 0~1循环
    private float powerFlashAlpha = 0f;  // 开/关机闪烁 0~1
    private float carScale = 1f;         // 开机弹性缩放
    private float scanlineY = 0f;        // HUD水平扫描线位置 0~1

    // 画笔
    private Paint beamPaint, haloPaint, hazardPaint, seatPaint, dimPaint;
    private Paint scanLightPaint, glowPaint, flashPaint, idlePaint;
    private Paint gridPaint, scanlinePaint, energyRingPaint;

    private Handler handler = new Handler(Looper.getMainLooper());
    private int hazardCount = 0;
    private Runnable hazardRunnable;
    private ValueAnimator glowAnimator;
    private ValueAnimator scanlineAnimator;

    public VehicleAnimView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        carBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.nx_body_alpha);
        carBitmapBucket = BitmapFactory.decodeResource(getResources(), R.drawable.nx_bucket_alpha);

        beamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hazardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        seatPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dimPaint.setColor(0x66000000);
        scanLightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        idlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // 科幻网格
        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(0xFF1E3A5F);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setAlpha(70);

        // HUD扫描线
        scanlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        scanlinePaint.setStyle(Paint.Style.FILL);
        scanlinePaint.setColor(0xFF00D4FF);
        scanlinePaint.setAlpha(26);

        // 能量环
        energyRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        energyRingPaint.setStyle(Paint.Style.STROKE);
        energyRingPaint.setStrokeWidth(3f);
        energyRingPaint.setColor(0xFF00D4FF);

        // 启动呼吸光晕动画（开机状态下持续运行）
        startGlowPulse();
        // 启动HUD扫描线循环
        startScanlineLoop();
    }

    /** 开机后持续呼吸光晕 */
    private void startGlowPulse() {
        glowAnimator = ValueAnimator.ofFloat(0f, 1f);
        glowAnimator.setDuration(2000);
        glowAnimator.setRepeatCount(ValueAnimator.INFINITE);
        glowAnimator.setRepeatMode(ValueAnimator.REVERSE);
        glowAnimator.addUpdateListener(a -> {
            glowPulse = (float) a.getAnimatedValue();
            if (powerOn) invalidate();
        });
        glowAnimator.start();
    }

    /** HUD水平扫描线循环（3.2秒一圈） */
    private void startScanlineLoop() {
        scanlineAnimator = ValueAnimator.ofFloat(0f, 1f);
        scanlineAnimator.setDuration(3200);
        scanlineAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scanlineAnimator.setRepeatMode(ValueAnimator.RESTART);
        scanlineAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        scanlineAnimator.addUpdateListener(a -> {
            scanlineY = (float) a.getAnimatedValue();
            invalidate();
        });
        scanlineAnimator.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        calculateCarRect();
    }

    private void calculateCarRect() {
        Bitmap bmp = carBitmap;
        if (bmp == null || getWidth() == 0 || getHeight() == 0) return;
        int vw = getWidth(), vh = getHeight();
        float bw = bmp.getWidth(), bh = bmp.getHeight();
        float scale = Math.min(vw / bw, vh / bh) * 0.90f;
        carW = bw * scale;
        carH = bh * scale;
        carLeft = (vw - carW) / 2f;
        carTop = (vh - carH) / 2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap bmp = carBitmap;
        if (bmp == null) return;
        if (carW == 0) calculateCarRect();

        float cx = carLeft + carW / 2f;
        float cy = carTop + carH / 2f;

        // ===== 0. 科幻背景：深空渐变 + 网格 + 扫描线 =====
        drawSciFiBackground(canvas);

        // ===== 1. 开机能量环（底部椭圆光晕 + 扩散环） =====
        if (powerOn && glowPulse >= 0) {
            float glowRadius = carW * 0.58f;
            float glowAlpha = 0.06f + glowPulse * 0.10f;
            glowPaint.setStyle(Paint.Style.FILL);
            glowPaint.setShader(new RadialGradient(cx, cy + carH * 0.30f, glowRadius,
                    new int[]{(int)(glowAlpha * 255) << 24 | 0xFF00D4FF, 0x0000D4FF},
                    null, Shader.TileMode.CLAMP));
            canvas.drawOval(new RectF(cx - glowRadius, cy - glowRadius * 0.5f,
                    cx + glowRadius, cy + glowRadius * 0.7f), glowPaint);
            glowPaint.setShader(null);

            // 扩散能量环
            energyRingPaint.setAlpha((int)(140 * (1 - glowPulse * 0.3f)));
            float ringR = carW * (0.30f + glowPulse * 0.25f);
            canvas.drawOval(new RectF(cx - ringR, cy + carH * 0.28f - ringR * 0.28f,
                    cx + ringR, cy + carH * 0.28f + ringR * 0.28f), energyRingPaint);
        }

        // ===== 2. 绘制车身 =====
        canvas.save();
        if (carScale != 1f) {
            canvas.scale(carScale, carScale, cx, cy);
        }
        Bitmap drawBmp = seatOpen ? carBitmapBucket : carBitmap;
        if (seatOpen && seatLift > 0) {
            // 坐桶弹起：整图轻微上移模拟
            canvas.translate(0, -6f * seatLift);
        }
        if (!powerOn && !seatOpen) {
            // 关机：先画车再压暗
            canvas.drawBitmap(drawBmp, carLeft, carTop, null);
            canvas.drawRect(carLeft, carTop, carLeft + carW, carTop + carH, dimPaint);
        } else {
            canvas.drawBitmap(drawBmp, carLeft, carTop, null);
        }
        canvas.restore();

        // ===== 3. 大灯光晕（开机时明亮白色+青色） =====
        if (haloAlpha > 0) {
            float hlx = carLeft + carW * 0.18f, hly = carTop + carH * 0.42f;
            float hr = carW * (0.16f + glowPulse * 0.03f);
            haloPaint.setStyle(Paint.Style.FILL);
            haloPaint.setShader(new RadialGradient(hlx, hly, hr,
                    new int[]{(int)(haloAlpha * 255) << 24 | 0xFFFFFFFF,
                              (int)(haloAlpha * 190) << 24 | 0xFFCCF4FF,
                              0x0000D4FF},
                    null, Shader.TileMode.CLAMP));
            canvas.drawCircle(hlx, hly, hr, haloPaint);
            haloPaint.setShader(null);

            float innerR = hr * 0.38f;
            haloPaint.setColor(Color.WHITE);
            haloPaint.setAlpha((int)(haloAlpha * 220));
            canvas.drawCircle(hlx, hly, innerR, haloPaint);
        }

        // ===== 4. 前射光束（开机时，车头朝左） =====
        if (beamAlpha > 0) {
            float hlx = carLeft + carW * 0.18f, hly = carTop + carH * 0.42f;
            Path beam = new Path();
            float r = carW * 0.05f;
            beam.moveTo(hlx - r * 0.3f, hly - r * 0.7f);
            beam.lineTo(hlx - r * 0.3f, hly + r * 0.7f);
            beam.lineTo(hlx - carW * 0.75f, hly + carH * 0.28f);
            beam.lineTo(hlx - carW * 0.75f, hly - carH * 0.28f);
            beam.close();
            beamPaint.setStyle(Paint.Style.FILL);
            beamPaint.setShader(new LinearGradient(hlx, hly, hlx - carW * 0.75f, hly,
                    new int[]{(int)(beamAlpha * 230) << 24 | 0xFFFFFFFF,
                              (int)(beamAlpha * 130) << 24 | 0xFF88DDFF,
                              0x0000D4FF},
                    null, Shader.TileMode.CLAMP));
            canvas.drawPath(beam, beamPaint);
            beamPaint.setShader(null);
        }

        // ===== 5. 双闪警示灯（关机时，左右交替） =====
        if (hazardOn) {
            float lx = carLeft + carW * 0.20f, ly = carTop + carH * 0.45f;
            float rx = carLeft + carW * 0.86f, ry = carTop + carH * 0.52f;
            hazardPaint.setStyle(Paint.Style.FILL);
            if (hazardLeftAlpha > 0) {
                hazardPaint.setColor(0xFFFF9800);
                hazardPaint.setAlpha((int)(hazardLeftAlpha * 230));
                canvas.drawCircle(lx, ly, carW * 0.05f, hazardPaint);
                hazardPaint.setAlpha((int)(hazardLeftAlpha * 80));
                canvas.drawCircle(lx, ly, carW * 0.11f, hazardPaint);
            }
            if (hazardRightAlpha > 0) {
                hazardPaint.setColor(0xFFFF9800);
                hazardPaint.setAlpha((int)(hazardRightAlpha * 230));
                canvas.drawCircle(rx, ry, carW * 0.05f, hazardPaint);
                hazardPaint.setAlpha((int)(hazardRightAlpha * 80));
                canvas.drawCircle(rx, ry, carW * 0.11f, hazardPaint);
            }
        }

        // ===== 6. 通电扫描光带 =====
        if (scanLightProgress >= 0) {
            float scanX = carLeft + carW * scanLightProgress;
            float bandW = carW * 0.18f;
            scanLightPaint.setStyle(Paint.Style.FILL);
            scanLightPaint.setShader(new LinearGradient(
                    scanX - bandW, 0, scanX + bandW, 0,
                    new int[]{0x0000D4FF, 0x9900D4FF, 0x0000D4FF},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(scanX - bandW, carTop - 10, scanX + bandW, carTop + carH + 10, scanLightPaint);
            scanLightPaint.setShader(null);
        }

        // ===== 7. 开/关机闪烁 =====
        if (powerFlashAlpha > 0) {
            flashPaint.setStyle(Paint.Style.FILL);
            flashPaint.setColor(Color.WHITE);
            flashPaint.setAlpha((int)(powerFlashAlpha * 130));
            canvas.drawRect(carLeft - 10, carTop - 10,
                    carLeft + carW + 10, carTop + carH + 10, flashPaint);
        }

        // ===== 8. 待机呼吸小点（关机且无其他动画时） =====
        if (!powerOn && !hazardOn && scanLightProgress < 0 && powerFlashAlpha <= 0) {
            float dotX = carLeft + carW * 0.5f;
            float dotY = carTop + carH * 0.88f;
            float dotAlpha = 0.3f + 0.3f * glowPulse;
            idlePaint.setStyle(Paint.Style.FILL);
            idlePaint.setColor(0xFF00D4FF);
            idlePaint.setAlpha((int)(dotAlpha * 255));
            canvas.drawCircle(dotX, dotY, 4f, idlePaint);
            idlePaint.setAlpha((int)(dotAlpha * 80));
            canvas.drawCircle(dotX, dotY, 8f + glowPulse * 4f, idlePaint);
        }
    }

    /** 科幻HUD背景：深空渐变 + 网格 + 水平扫描线 */
    private void drawSciFiBackground(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        // 深空渐变
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xFF0A0E17, 0xFF0D1526, 0xFF0A0E17},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, bg);
        bg.setShader(null);

        // 网格（水平+垂直）
        int step = h / 6;
        for (int y = 0; y <= h; y += step) {
            canvas.drawLine(0, y, w, y, gridPaint);
        }
        for (int x = 0; x <= w; x += step) {
            canvas.drawLine(x, 0, x, h, gridPaint);
        }

        // HUD水平扫描线（青色半透明）
        float sy = scanlineY * h;
        canvas.drawRect(0, sy, w, sy + 2f, scanlinePaint);
        // 扫描线拖尾
        Paint trail = new Paint(Paint.ANTI_ALIAS_FLAG);
        trail.setColor(0xFF00D4FF);
        trail.setAlpha(12);
        canvas.drawRect(0, sy + 2f, w, sy + h * 0.18f, trail);
        trail.setAlpha(6);
        canvas.drawRect(0, sy + h * 0.18f, w, sy + h * 0.30f, trail);
    }

    // ========== 状态控制 API ==========

    public void setPowerOn(boolean on) {
        if (powerOn == on) return;
        powerOn = on;
        if (on) {
            seatOpen = false;
            seatLift = 0f;
            stopHazard();
            // 开机序列：闪烁 → 光束 → 光晕 → 扫描光 → 弹性缩放
            animateFlash(1f, 0f, 400);
            animateBeam(0f, 1f, 500);
            animateHalo(0f, 1f, 600);
            animateScanLight();
            animateScale(0.95f, 1f, 400);
        } else {
            // 关机序列：闪烁 → 灭灯 → 双闪
            animateFlash(1f, 0f, 300);
            animateBeam(beamAlpha, 0f, 300);
            animateHalo(haloAlpha, 0f, 400);
            animateScale(1f, 0.97f, 200);
            startHazard();
        }
        invalidate();
    }

    public void setSeatOpen(boolean open) {
        if (seatOpen == open) return;
        seatOpen = open;
        if (open) {
            // 弹起位移动画（轻微上移 + 回弹）
            ValueAnimator lift = ValueAnimator.ofFloat(0f, 1f, 0.6f, 1f);
            lift.setDuration(550);
            lift.setInterpolator(new OvershootInterpolator(1.5f));
            lift.addUpdateListener(a -> { seatLift = (float) a.getAnimatedValue(); invalidate(); });
            lift.start();
        } else {
            ValueAnimator close = ValueAnimator.ofFloat(seatLift, 0f);
            close.setDuration(250);
            close.addUpdateListener(a -> { seatLift = (float) a.getAnimatedValue(); invalidate(); });
            close.start();
        }
    }

    // ========== 动画方法 ==========

    private void startHazard() {
        hazardOn = true;
        hazardCount = 0;
        hazardRunnable = new Runnable() {
            @Override
            public void run() {
                hazardCount++;
                if (hazardCount > 10) { stopHazard(); return; }
                if (hazardCount % 2 == 1) {
                    hazardLeftAlpha = 1f; hazardRightAlpha = 0f;
                } else {
                    hazardLeftAlpha = 0f; hazardRightAlpha = 1f;
                }
                invalidate();
                handler.postDelayed(this, 400);
            }
        };
        handler.post(hazardRunnable);
    }

    private void stopHazard() {
        if (hazardRunnable != null) {
            handler.removeCallbacks(hazardRunnable);
            hazardRunnable = null;
        }
        hazardOn = false;
        hazardLeftAlpha = 0f;
        hazardRightAlpha = 0f;
        invalidate();
    }

    private void animateBeam(float from, float to, long duration) {
        ValueAnimator anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(duration);
        anim.addUpdateListener(a -> { beamAlpha = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    private void animateHalo(float from, float to, long duration) {
        ValueAnimator anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(duration);
        anim.addUpdateListener(a -> { haloAlpha = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    private void animateFlash(float from, float to, long duration) {
        ValueAnimator anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(duration);
        anim.addUpdateListener(a -> { powerFlashAlpha = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    private void animateScale(float from, float to, long duration) {
        ValueAnimator anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(duration);
        anim.setInterpolator(new OvershootInterpolator(2f));
        anim.addUpdateListener(a -> { carScale = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    private void animateScanLight() {
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(800);
        anim.setInterpolator(new AccelerateDecelerateInterpolator());
        anim.addUpdateListener(a -> { scanLightProgress = (float) a.getAnimatedValue(); invalidate(); });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                scanLightProgress = -1f; invalidate();
            }
        });
        anim.start();
    }

    // ========== 小部件静态图 ==========

    public static Bitmap generateStaticBitmap(Context context, int state, int width, int height) {
        String key = state + "_" + width + "_" + height;
        Bitmap cached = staticCache.get(key);
        if (cached != null && !cached.isRecycled()) return cached;

        Bitmap car = BitmapFactory.decodeResource(context.getResources(), R.drawable.nx_body_alpha);
        Bitmap bucket = BitmapFactory.decodeResource(context.getResources(), R.drawable.nx_bucket_alpha);
        if (car == null) return null;

        boolean seat = (state == 2);
        Bitmap src = seat ? bucket : car;

        float scale = Math.min((float) width / src.getWidth(), (float) height / src.getHeight()) * 0.90f;
        int cw = (int)(src.getWidth() * scale);
        int ch = (int)(src.getHeight() * scale);
        Bitmap scaled = Bitmap.createScaledBitmap(src, cw, ch, true);

        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        float left = (width - cw) / 2f;
        float top = (height - ch) / 2f;
        canvas.drawBitmap(scaled, left, top, null);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        if (state == 0) {
            // 关机：压暗 + 橙色双闪小灯
            p.setColor(0x66000000);
            canvas.drawRect(left, top, left + cw, top + ch, p);
            p.setColor(0xFFFF9800);
            p.setAlpha(200);
            float lx = left + cw * 0.20f, ly = top + ch * 0.45f;
            canvas.drawCircle(lx, ly, cw * 0.04f, p);
            p.setAlpha(120);
            float rx = left + cw * 0.86f, ry = top + ch * 0.52f;
            canvas.drawCircle(rx, ry, cw * 0.03f, p);
        } else if (state == 1) {
            // 开机：青色底部光晕 + 大灯 + 光束
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(left + cw * 0.5f, top + ch * 0.7f, cw * 0.4f,
                    new int[]{0x3300D4FF, 0x0000D4FF}, null, Shader.TileMode.CLAMP));
            canvas.drawOval(new RectF(left, top + ch * 0.3f, left + cw, top + ch), p);
            p.setShader(null);

            float hlx = left + cw * 0.18f, hly = top + ch * 0.42f;
            p.setShader(new RadialGradient(hlx, hly, cw * 0.14f,
                    new int[]{0xCCFFFFFF, 0x66BBEFFF, 0x0000D4FF}, null, Shader.TileMode.CLAMP));
            p.setAlpha(210);
            canvas.drawCircle(hlx, hly, cw * 0.14f, p);
            p.setShader(null);

            Path beam = new Path();
            float r = cw * 0.04f;
            beam.moveTo(hlx - r * 0.3f, hly - r * 0.6f);
            beam.lineTo(hlx - r * 0.3f, hly + r * 0.6f);
            beam.lineTo(hlx - cw * 0.55f, hly + ch * 0.20f);
            beam.lineTo(hlx - cw * 0.55f, hly - ch * 0.20f);
            beam.close();
            p.setShader(new LinearGradient(hlx, hly, hlx - cw * 0.55f, hly,
                    new int[]{0xEEFFFFFF, 0x44AAEEFF, 0x0000D4FF}, null, Shader.TileMode.CLAMP));
            p.setAlpha(170);
            canvas.drawPath(beam, p);
            p.setShader(null);
        } else if (state == 2) {
            // 坐桶打开：轻微上移 + 琥珀提示
            p.setColor(0x55FF9800);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3f);
            float rr = cw * 0.34f;
            canvas.drawOval(new RectF(left + cw*0.5f - rr, top + ch*0.72f - rr*0.3f,
                    left + cw*0.5f + rr, top + ch*0.72f + rr*0.3f), p);
        }

        staticCache.put(key, out);
        return out;
    }

    public static void clearStaticCache() {
        for (Bitmap b : staticCache.values()) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        staticCache.clear();
    }
}
