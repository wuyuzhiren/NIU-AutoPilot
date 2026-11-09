package com.niu.autopilot.vehicle

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import com.niu.autopilot.vehicle.util.DeviceCapability

/**
 * 车辆舞台自定义 ViewGroup — 浅色主题适配版
 *
 * 本文件为 v1.1 的 Theme-Aware 升级补丁，主要变更：
 * 1. 新增 themeAccentColor / themeAccentGlowColor 属性，支持运行时切换主强调色
 * 2. OverlayView Canvas 绘制从硬编码 RGB 改为从主题色提取分量
 * 3. 浅色模式下 Glow 透明度降低、车辆不做全灰度处理（保留色相）
 * 4. 新增 setThemeColors() 公共 API，供 ThemeManager 在主题切换时调用
 *
 * 使用方式：
 *   将本文件覆盖原 VehicleStageView.kt（保持包名和类名不变），
 *   在 ControlFragment 中监听 ThemeManager.currentMode，调用 vehicleStage.setThemeColors()。
 *
 * 设计文档对应：§7.3「浅色模式车辆视觉适配」
 */
class VehicleStageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // ==================== 子 View ====================
    private val bodyImage: ImageView = ImageView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    /**
     * 坐桶图层（nx_bucket.png）：与车身同坐标系叠加。
     * 默认 GONE；开坐桶动画时显示并弹开，关闭后隐藏。
     */
    private val bucketImage: ImageView = ImageView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
    }

    private val overlay: OverlayView = OverlayView(context)

    /**
     * v3.3 真实灯效层：SCREEN 混合 + fx_* 贴图分层绘制。
     * 与旧 overlay 并存，通过 useLightFx 开关切换；默认启用新实现。
     */
    val lightFx: LightFxLayer = LightFxLayer(context)

    /**
     * v3.4: 把坐桶几何变换回灌口接上，否则 LightFxAnimator 只能驱动灯、
     * 驱动不了坐垫位移/旋转（设计规范 §5.4 指出的缺口）。
     */
    private val lightFxAnimator: LightFxAnimator = LightFxAnimator(
        lightFx,
        object : LightFxAnimator.BucketTransformSink {
            override fun setBucketLift(dp: Float) { this@VehicleStageView.bucketLiftDp = dp }
            override fun setBucketRotation(deg: Float) { this@VehicleStageView.bucketRotationDeg = deg }
            override fun setBucketVisible(v: Float) { this@VehicleStageView.bucketVisible = v }
        }
    )

    /** 是否使用 v3.3 真实灯效（false 回退旧 OverlayView 行为） */
    var useLightFx: Boolean = true
        set(value) {
            field = value
            lightFx.visibility = if (value) View.VISIBLE else View.GONE
            if (value) overlay.hideLegacyLights()
            overlay.invalidate()
        }


    // ==================== 公开属性 ====================
    var lightPoints: List<VehicleLightPoint> = VehicleLightPoint.defaultPoints()
        set(value) {
            field = value
            overlay.invalidate()
        }

    var bodyBrightness: Float = 0.6f
        set(value) {
            field = value.coerceIn(0f, 1f)
            applyBodyVisuals()
        }

    var bodySaturation: Float = 0.2f
        set(value) {
            field = value.coerceIn(0f, 1f)
            applyBodyVisuals()
        }

    var glowAlpha: Float = 0.3f
        set(value) {
            field = value.coerceIn(0f, 1f)
            overlay.invalidate()
        }

    var ringAlpha: Float = 0.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            overlay.invalidate()
        }

    var ringScale: Float = 0.0f
        set(value) {
            field = value.coerceIn(0f, 2f)
            overlay.invalidate()
        }

    var scanLineY: Float = -1f
        set(value) {
            field = value
            overlay.invalidate()
        }

    var scanLineAlpha: Float = 0.3f
        set(value) {
            field = value.coerceIn(0f, 1f)
            overlay.invalidate()
        }

    val lightAlphas = mutableMapOf<VehicleLightPoint.Type, Float>().apply {
        VehicleLightPoint.Type.entries.forEach { put(it, 0f) }
    }

    var floatOffsetDp: Float = 0f
        set(value) {
            field = value
            bodyImage.translationY = dpToPx(value)
            overlay.invalidate()
        }

    var enableRingRotation: Boolean = DeviceCapability.enableRotatingRing(context)

    // ==================== 光束 / 坐桶弹开属性（v2.75 新增）====================
    /**
     * 大灯光束透明度 0~1。开机动画结束后保持常亮，关机动画置 0。
     */
    var beamAlpha: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            overlay.invalidate()
        }

    /**
     * 坐桶图层可见度 0~1。
     *
     * v3.5 重做：`nx_body.webp` 与 `nx_bucket.webp` 是**同车型、同视角**的两张渲染图
     * （body = 座垫闭合，bucket = 座垫掀开露出储物桶），alpha bbox 分别为
     * (437,70,1063,930) 与 (453,70,1047,930)。
     * 所以「开坐桶」就是**两张图淡变**，不需要任何位移/旋转/整车跳动。
     *
     * 实现要点：body 层始终 alpha=1 垫在下面，bucket 层用 field 做 alpha 叠上去。
     * 逐像素结果 = field*bucket + (1-field)*body，合成 alpha 恒为 1 ——
     * 这本身就是标准交叉淡变，且中途整台车不会「发虚」透出背景。
     * （若两层同时淡，合成 alpha 会掉到 field+(1-field)² < 1，中途车身会半透明。）
     *
     * 两层都保持 MATCH_PARENT + FIT_CENTER，**绝不能各自裁 alpha bbox**
     * （裁完宽度 626 vs 594 会错位）。
     */
    var bucketVisible: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            bucketImage.alpha = field
            bucketImage.visibility = if (field > 0.01f) View.VISIBLE else View.GONE
            overlay.invalidate()
        }

    /**
     * v3.5: 保留接口但**不再产生位移**。
     *
     * 旧实现给 bucketImage 加 translationY（还附带 translationX = value*0.18），
     * 会让整张掀盖图平移 —— 观感就是用户说的「整个车辆跳动」。
     * 真实机械动作已经烘焙进 nx_bucket.webp，任何位移都是错的。
     */
    var bucketLiftDp: Float = 0f
        set(value) {
            field = value
        }

    /**
     * v3.5: 保留接口但**不再旋转**。
     *
     * 旧实现的 pivot 取的是 ImageView 宽高的 0.62/0.38，而车身是按 FIT_CENTER
     * 等比缩放后居中绘制的，铰链点实际落在车身之外 → 旋转时整图「飘」。
     * 掀开角度已烘焙进 nx_bucket.webp，不需要再旋转。
     */
    var bucketRotationDeg: Float = 0f
        set(value) {
            field = value
        }

    // ==================== 主题感知属性（新增）====================
    /**
     * 当前主题强调色。
     * 深色默认：电光蓝 #00D4FF；浅色默认：电光紫 #7C3AED。
     * 通过 setThemeColors() 自动设置。
     */
    var themeAccentColor: Int = Color.argb(255, 0, 212, 255)
        set(value) {
            field = value
            // 清除缓存的 Shader，触发重绘
            overlay.clearCachedShaders()
            overlay.invalidate()
        }

    /**
     * 是否为浅色主题。
     * 影响：车辆灰度阈值、Glow 透明度、光晕颜色风格。
     */
    var isLightTheme: Boolean = false
        set(value) {
            field = value
            // 浅色模式下未连接状态仅降低饱和度至 40%（保留色相可辨识）
            if (value && bodySaturation < 0.15f) {
                bodySaturation = 0.4f
            }
            overlay.invalidate()
        }

    // ==================== 内部绘制参数 ====================
    private val capLevel = DeviceCapability.level(context)
    private val enableGlow = DeviceCapability.enableGlow(context)
    private val enableScanLine = DeviceCapability.enableScanLine(context)

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2f)
    }
    private val scanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bodyColorMatrix = ColorMatrix()
    private var ringRotationAnimator: ValueAnimator? = null
    private var ringRotationAngle = 0f

    init {
        addView(bodyImage)
        // 坐桶图层置于车身之上（zIndex 更高）
        addView(bucketImage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { z = 1f })
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { z = 2f })
        // v3.3 真实灯效层置于最上层（z=3），SCREEN 混合叠加在车身上
        addView(lightFx, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { z = 3f })
        lightFx.visibility = if (useLightFx) View.VISIBLE else View.GONE
        if (useLightFx) overlay.hideLegacyLights()
        applyBodyVisuals()
        if (enableRingRotation) startRingRotation()
    }

    // ==================== 公开 API ====================

    fun setBodyImageResource(resId: Int) {
        bodyImage.setImageResource(resId)
    }

    fun setBodyImageBitmap(bitmap: Bitmap) {
        bodyImage.setImageBitmap(bitmap)
    }

    fun setBucketImageResource(resId: Int) {
        bucketImage.setImageResource(resId)
    }

    fun setBucketImageBitmap(bitmap: Bitmap) {
        bucketImage.setImageBitmap(bitmap)
    }

    // ==================== v3.3 真实灯效 API ====================

    /** 播放开机灯效：大灯照亮 → 光束推出 → 尾灯自检闪 → 地面光斑 */
    fun playLightPowerOn(onEnd: (() -> Unit)? = null) {
        if (useLightFx) lightFxAnimator.playPowerOn(onEnd)
    }

    /** 播放关机灯效：双闪（左右同相）→ 光束收缩 → 灯全灭 */
    fun playLightPowerOff(onEnd: (() -> Unit)? = null) {
        if (useLightFx) lightFxAnimator.playPowerOff(onEnd)
    }

    /** 开坐桶：欠阻尼弹簧 + 箱内透光 */
    fun playLightBucketOpen(onEnd: (() -> Unit)? = null) {
        if (useLightFx) lightFxAnimator.playBucketOpen(onEnd)
    }

    fun playLightBucketClose(onEnd: (() -> Unit)? = null) {
        if (useLightFx) lightFxAnimator.playBucketClose(onEnd)
    }

    /** 全部熄灭 */
    fun resetLights() {
        lightFxAnimator.stopAll()
    }

    /** 设置动画速度倍率（无障碍"关闭动画"时为 0，直接跳终态） */
    fun setAnimationScale(scale: Float) {
        lightFxAnimator.durationScale = scale.coerceIn(0f, 3f)
    }

    fun setLightAlpha(type: VehicleLightPoint.Type, alpha: Float) {
        lightAlphas[type] = alpha.coerceIn(0f, 1f)
        overlay.invalidate()
    }

    fun setAllLightsAlpha(alpha: Float) {
        VehicleLightPoint.Type.entries.forEach { lightAlphas[it] = alpha.coerceIn(0f, 1f) }
        overlay.invalidate()
    }

    /**
     * 设置主题颜色（由 ThemeManager 调用）。
     *
     * @param accentColor 主强调色（能量环、光晕、扫描线）
     * @param lightTheme  是否为浅色主题
     */
    fun setThemeColors(accentColor: Int, lightTheme: Boolean) {
        isLightTheme = lightTheme
        themeAccentColor = accentColor
        // 浅色模式下扫描线透明度降低
        if (lightTheme) {
            scanLinePaint.color = Color.argb(60, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        } else {
            scanLinePaint.color = Color.argb(51, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        }
    }

    fun startRingRotation() {
        if (!enableRingRotation) return
        ringRotationAnimator?.cancel()
        ringRotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 20_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                ringRotationAngle = it.animatedValue as Float
                overlay.invalidate()
            }
            start()
        }
    }

    fun stopRingRotation() {
        ringRotationAnimator?.cancel()
        ringRotationAnimator = null
    }

    fun release() {
        stopRingRotation()
        bodyImage.animate().cancel()
        bodyImage.setImageDrawable(null)
        bucketImage.animate().cancel()
        bucketImage.setImageDrawable(null)
    }

    // ==================== 内部方法 ====================

    private fun applyBodyVisuals() {
        bodyColorMatrix.setSaturation(bodySaturation)
        val scale = bodyBrightness
        val brightnessMatrix = ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, 0f,
            0f, scale, 0f, 0f, 0f,
            0f, 0f, scale, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        bodyColorMatrix.postConcat(brightnessMatrix)
        val filter = ColorMatrixColorFilter(bodyColorMatrix)
        bodyImage.colorFilter = filter
        // v3.5: 坐桶图层必须套同一个 colorFilter。
        // 否则关机态（brightness 0.6 / saturation 0.2）掀开坐桶时，
        // 掀盖图会以「全亮全饱和」叠在压暗的车身上，看起来像贴纸。
        bucketImage.colorFilter = filter
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    // ==================== 覆盖层绘制（主题感知版）====================

    private inner class OverlayView(context: Context) : View(context) {

        private val glowRect = RectF()

        /** v3.3：启用 LightFxLayer 后关闭旧光点/光束/扫描线绘制 */
        private var legacyLightsHidden = false

        // 缓存 Shader 与 PathEffect
        private var cachedGlowShader: RadialGradient? = null
        private var cachedGlowAlpha = -1f
        private val cachedGlowRect = RectF()
        private val cachedDashIntervals = floatArrayOf(dpToPx(8f), dpToPx(12f))
        private var cachedDashEffect: DashPathEffect? = null
        private var cachedDashPhase = Float.MIN_VALUE

        init {
            setWillNotDraw(false)
            if (capLevel >= DeviceCapability.LEVEL_MEDIUM) {
                setLayerType(LAYER_TYPE_HARDWARE, null)
            }
        }

        /** 清除缓存的 Shader（主题切换时调用） */
        fun clearCachedShaders() {
            cachedGlowShader = null
            cachedGlowAlpha = -1f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            drawGlow(canvas, w, h)
            if (enableScanLine && !legacyLightsHidden) drawScanLine(canvas, w, h)
            drawRadarRings(canvas, w, h)
            if (!legacyLightsHidden) {
                drawLightPoints(canvas, w, h)
                drawBeam(canvas, w, h)
            }
        }

        /**
         * v3.3：启用新 LightFxLayer 后关闭旧的光点/光束/扫描线绘制，
         * 避免两套灯效叠加（这是 v3.2 "生硬" 的原因之一）。
         * 底座光晕和雷达环保留（它们是氛围元素，不是灯）。
         */
        fun hideLegacyLights() {
            legacyLightsHidden = true
            invalidate()
        }

        /** 底座光晕：椭圆径向渐变 — 使用 themeAccentColor */
        private fun drawGlow(canvas: Canvas, w: Float, h: Float) {
            if (glowAlpha <= 0.01f) return
            val cx = w / 2f
            val cy = h * 0.78f
            val rx = w * 0.35f
            val ry = h * 0.08f

            glowRect.set(cx - rx, cy - ry, cx + rx, cy + ry)
            if (cachedGlowShader == null || cachedGlowAlpha != glowAlpha || cachedGlowRect != glowRect) {
                val accent = themeAccentColor
                val r = Color.red(accent)
                val g = Color.green(accent)
                val b = Color.blue(accent)

                // 浅色模式下 Glow 透明度降低（防过曝）
                val alphaMultiplier = if (isLightTheme) 0.4f else 0.6f

                cachedGlowShader = RadialGradient(
                    cx, cy, rx,
                    intArrayOf(
                        Color.argb((255 * glowAlpha * alphaMultiplier).toInt(), r, g, b),
                        Color.argb((255 * glowAlpha * 0.2f).toInt(), r / 2, g / 2, (b + 50).coerceAtMost(255)),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                cachedGlowAlpha = glowAlpha
                cachedGlowRect.set(glowRect)
            }
            glowPaint.shader = cachedGlowShader
            canvas.drawOval(glowRect, glowPaint)
            glowPaint.shader = null
        }

        /** 扫描线 — 使用 themeAccentColor */
        private fun drawScanLine(canvas: Canvas, w: Float, h: Float) {
            if (scanLineY < 0f || scanLineY > 1f) return
            val y = h * scanLineY
            canvas.drawRect(0f, y - dpToPx(0.5f), w, y + dpToPx(0.5f), scanLinePaint)
        }

        /** 能量环：多层同心圆 — 使用 themeAccentColor */
        private fun drawRadarRings(canvas: Canvas, w: Float, h: Float) {
            if (ringAlpha <= 0.01f) return
            val cx = w / 2f
            val cy = h * 0.45f
            val maxRadius = w * 0.38f * ringScale

            val ringCount = when (capLevel) {
                DeviceCapability.LEVEL_HIGH -> 3
                DeviceCapability.LEVEL_MEDIUM -> 2
                else -> 1
            }

            val accent = themeAccentColor
            val ar = Color.red(accent)
            val ag = Color.green(accent)
            val ab = Color.blue(accent)

            // 浅色模式下环透明度降低
            val baseAlphaFactor = if (isLightTheme) 0.65f else 1.0f

            for (i in 0 until ringCount) {
                val fraction = (i + 1f) / ringCount
                val r = maxRadius * fraction
                if (r <= 0f) continue

                val breathe = 0.7f + 0.3f * kotlin.math.sin(
                    System.currentTimeMillis() / 1000.0 * Math.PI * 2 / 2.5 + i * 1.2
                ).toFloat()
                val alpha = (ringAlpha * breathe * 255 * baseAlphaFactor).toInt().coerceIn(0, 255)

                ringPaint.color = Color.argb(alpha, ar, ag, ab)
                ringPaint.strokeWidth = dpToPx(1.5f - i * 0.3f)

                canvas.save()
                canvas.rotate(ringRotationAngle * (1f - i * 0.15f), cx, cy)
                val phase = i * 20f
                if (cachedDashEffect == null || cachedDashPhase != phase) {
                    cachedDashEffect = DashPathEffect(cachedDashIntervals, phase)
                    cachedDashPhase = phase
                }
                ringPaint.pathEffect = cachedDashEffect
                canvas.drawCircle(cx, cy, r, ringPaint)
                ringPaint.pathEffect = null
                canvas.restore()
            }
        }

        /** 灯光点位绘制 — 光晕使用 themeAccentColor 风格 */
        private fun drawLightPoints(canvas: Canvas, w: Float, h: Float) {
            for (point in lightPoints) {
                val alpha = lightAlphas[point.type] ?: 0f
                if (alpha <= 0.01f) continue

                val px = w * point.relX
                val py = h * point.relY
                val radius = dpToPx(point.radiusDp)
                val glowR = dpToPx(point.glowRadiusDp)

                // 发光层
                if (enableGlow && glowR > radius) {
                    val glowAlphaInt = (255 * alpha * if (isLightTheme) 0.25f else 0.4f).toInt().coerceIn(0, 255)
                    lightPaint.color = Color.argb(glowAlphaInt,
                        Color.red(point.color),
                        Color.green(point.color),
                        Color.blue(point.color)
                    )
                    canvas.drawCircle(px, py, glowR, lightPaint)
                }

                // 核心光点
                val coreAlpha = (255 * alpha * point.maxAlpha).toInt().coerceIn(0, 255)
                lightPaint.color = Color.argb(coreAlpha,
                    Color.red(point.color),
                    Color.green(point.color),
                    Color.blue(point.color)
                )
                canvas.drawCircle(px, py, radius, lightPaint)

                // 高光中心
                if (capLevel >= DeviceCapability.LEVEL_MEDIUM && alpha > 0.5f) {
                    lightPaint.color = Color.argb((coreAlpha * 0.6f).toInt(), 255, 255, 255)
                    canvas.drawCircle(px, py, radius * 0.4f, lightPaint)
                }
            }
        }

        /** 大灯光束：从车头大灯向右前方延伸的渐变光锥（模拟开机亮灯） */
        private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private var cachedBeamShader: LinearGradient? = null
        private var cachedBeamAlpha = -1f
        private var cachedBeamLight = false

        private fun drawBeam(canvas: Canvas, w: Float, h: Float) {
            if (beamAlpha <= 0.01f) return
            // 车头大灯位置（HEADLAMP_LEFT 点位），光束从该点向右前方展开
            val headLamp = lightPoints.firstOrNull { it.type == VehicleLightPoint.Type.HEADLAMP_LEFT }
            val hx = w * (headLamp?.relX ?: 0.22f)
            val hy = h * (headLamp?.relY ?: 0.42f)
            // 光束长度与宽度（相对画布）
            val beamLen = w * 0.46f
            val baseWidth = w * 0.05f
            val tipWidth = w * 0.13f

            // 梯形光锥：从大灯向外放射，末端渐宽
            val path = Path()
            path.moveTo(hx, hy - baseWidth / 2f)
            path.lineTo(hx + beamLen, hy - tipWidth / 2f + beamLen * 0.12f)
            path.lineTo(hx + beamLen, hy + tipWidth / 2f + beamLen * 0.12f)
            path.lineTo(hx, hy + baseWidth / 2f)
            path.close()

            // 光锥内渐变：根部亮，末端透明
            val accent = themeAccentColor
            val r = Color.red(accent)
            val g = Color.green(accent)
            val b = Color.blue(accent)
            val alphaMul = if (isLightTheme) 0.5f else 0.75f
            if (cachedBeamShader == null || cachedBeamAlpha != beamAlpha || cachedBeamLight != isLightTheme) {
                cachedBeamShader = LinearGradient(
                    hx, hy, hx + beamLen, hy + beamLen * 0.2f,
                    intArrayOf(
                        Color.argb((255 * beamAlpha * alphaMul).toInt(), (r + 140).coerceAtMost(255), (g + 120).coerceAtMost(255), 255),
                        Color.argb((120 * beamAlpha * alphaMul).toInt(), r, g, b),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
                cachedBeamAlpha = beamAlpha
                cachedBeamLight = isLightTheme
            }
            beamPaint.shader = cachedBeamShader
            canvas.drawPath(path, beamPaint)
            beamPaint.shader = null
        }
    }
}
