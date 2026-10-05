package com.niu.autopilot

import android.animation.*
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.niu.autopilot.vehicle.VehicleStageView

/**
 * ScifiPowerAnimator — 零依赖科幻开关机动画套件
 *
 * 核心设计：
 * - 只使用 Android 标准动画 API（ObjectAnimator / ValueAnimator / AnimatorSet）
 * - 通过 rootView.findViewById 按 ID 定位视图，零 import 用户自定义类
 * - 动态创建扫描线 / 能量环 / 光晕等 overlay，不依赖布局已有结构
 * - ID 缺失时安全降级，输出诊断日志（tag = SciFiAnim）
 * - 支持深浅主题自动适配，也可通过 setLightTheme() 注入自定义主题状态
 *
 * 用法：
 *   ScifiPowerAnimator.playPowerOn(rootView)
 *   ScifiPowerAnimator.playPowerOff(rootView)
 *   ScifiPowerAnimator.playSeatOpen(rootView)
 *   // 在 Fragment.onDestroyView 中：
 *   ScifiPowerAnimator.onDestroy(view)
 *   // 若 App 使用自定义主题切换，在主题变更处调用：
 *   ScifiPowerAnimator.setLightTheme(isLight)
 */
object ScifiPowerAnimator {

    private const val TAG = "SciFiAnim"

    // ---- 默认视图 ID（基于 v2.77 布局）----
    // 如用户工程 ID 不同，请修改以下常量
    private val ID_VEHICLE_STAGE     = R.id.vehicle_anim
    private val ID_VEHICLE_CONTAINER = R.id.vehicle_stage_container
    private val ID_STATUS_TEXT       = R.id.tv_power_state
    private val ID_BTN_POWER_ON      = R.id.btn_power_on
    private val ID_BTN_POWER_OFF     = R.id.btn_power_off
    private val ID_BTN_SEAT          = R.id.btn_seat

    // ---- Overlay tag ----
    private const val TAG_SCAN_LINE       = "scifi_scan_line"
    private const val TAG_ENERGY_RING     = "scifi_energy_ring"
    private const val TAG_HEADLIGHT_GLOW  = "scifi_headlight_glow"
    private const val TAG_DARKEN_OVERLAY  = "scifi_darken_overlay"

    // ---- 动画池 ----
    private var currentSet: AnimatorSet? = null
    private val activeAnimators = mutableListOf<Animator>()

    // ---- 持续旋转 animator（开机后）----
    private var idleRotation: ObjectAnimator? = null

    // ---- 主题覆盖（可注入）----
    private var lightThemeOverride: Boolean? = null

    // ============================================================
    // 公共 API
    // ============================================================

    /**
     * 手动设置当前主题模式（覆盖系统夜间模式判断）。
     * 适用于 App 拥有自定义主题切换机制（不跟随系统）的场景。
     * 在主题切换处调用，Animator 内部会在下次播放动画时生效。
     *
     * @param isLight true=浅色主题，false=深色主题
     */
    fun setLightTheme(isLight: Boolean) {
        lightThemeOverride = isLight
    }

    /**
     * 开机动画（约 2.5s）
     * 编排：扫描线 → 能量环扩散 → 车辆变亮 → 大灯亮起 → 文字跳动 → 按钮高亮 → 车辆上浮
     */
    fun playPowerOn(rootView: View) {
        cancelAll()
        val context = rootView.context
        val theme = getThemeColors(context)

        // 安全获取视图
        val vehicleView = safeFind<View>(rootView, ID_VEHICLE_STAGE, "vehicle_anim")
        val container   = safeFind<ViewGroup>(rootView, ID_VEHICLE_CONTAINER, "vehicle_stage_container")
        val statusText  = safeFind<TextView>(rootView, ID_STATUS_TEXT, "tv_vehicle_status")
        val btnOn       = safeFind<View>(rootView, ID_BTN_POWER_ON, "btn_power_on")
        val btnOff      = safeFind<View>(rootView, ID_BTN_POWER_OFF, "btn_power_off")
        val btnSeat     = safeFind<View>(rootView, ID_BTN_SEAT, "btn_seat")

        if (container == null) {
            Log.e(TAG, "未找到 vehicle_stage_container，无法播放开机动画")
            return
        }

        // 确保 overlay 存在
        val scanLine       = ensureScanLine(container, theme.energyBlue)
        val energyRing     = ensureEnergyRing(container, theme.energyBlue)
        val headlightGlow  = ensureHeadlightGlow(container, theme.energyBlue, theme.glowAlpha)

        // ---- 准备初始状态 ----
        vehicleView?.let {
            it.alpha = 0.4f
            it.translationY = 0f
            applyColorMatrix(it, saturation = 0f, brightness = 0.6f)
        }
        energyRing.alpha = 0f
        energyRing.scaleX = 0f
        energyRing.scaleY = 0f
        headlightGlow.alpha = 0f
        headlightGlow.scaleX = 0.5f
        headlightGlow.scaleY = 0.5f
        scanLine.alpha = 0f
        scanLine.translationY = -dpToPx(context, 10f).toFloat()

        // ---- 构建动画 ----

        // 1. 扫描线：从上到下扫过 (0~800ms)
        val scanLineAlphaIn = ObjectAnimator.ofFloat(scanLine, "alpha", 0f, 0.35f).apply {
            duration = 200
        }
        val scanLineAlphaOut = ObjectAnimator.ofFloat(scanLine, "alpha", 0.35f, 0f).apply {
            duration = 300
            startDelay = 300
        }
        val scanLineMove = ObjectAnimator.ofFloat(
            scanLine, "translationY",
            -dpToPx(context, 10f).toFloat(),
            container.height.toFloat()
        ).apply {
            duration = 800
            interpolator = AccelerateDecelerateInterpolator()
        }

        // 2. 能量环扩散 (500~1200ms)
        val ringFadeIn = ObjectAnimator.ofFloat(energyRing, "alpha", 0f, 0.6f).apply {
            duration = 700
            startDelay = 500
            interpolator = OvershootInterpolator(1.2f)
        }
        val ringScaleX = ObjectAnimator.ofFloat(energyRing, "scaleX", 0f, 1f).apply {
            duration = 700
            startDelay = 500
            interpolator = OvershootInterpolator(1.2f)
        }
        val ringScaleY = ObjectAnimator.ofFloat(energyRing, "scaleY", 0f, 1f).apply {
            duration = 700
            startDelay = 500
            interpolator = OvershootInterpolator(1.2f)
        }

        // 3. 车辆变亮 (800~1500ms)
        val vehicleBrightnessAnim = vehicleView?.let {
            createBrightnessAnimator(
                it, fromSat = 0f, toSat = 1f,
                fromBright = 0.6f, toBright = 1f, duration = 700
            ).apply { startDelay = 800 }
        }
        val vehicleAlphaAnim = vehicleView?.let {
            ObjectAnimator.ofFloat(it, "alpha", 0.4f, 1f).apply {
                duration = 700
                startDelay = 800
                interpolator = DecelerateInterpolator()
            }
        }

        // 4. 大灯亮起 (1000~1600ms)
        val headlightAlpha = ObjectAnimator.ofFloat(headlightGlow, "alpha", 0f, 1f).apply {
            duration = 600
            startDelay = 1000
            interpolator = DecelerateInterpolator()
        }
        val headlightScaleX = ObjectAnimator.ofFloat(headlightGlow, "scaleX", 0.5f, 1f).apply {
            duration = 600
            startDelay = 1000
            interpolator = DecelerateInterpolator()
        }
        val headlightScaleY = ObjectAnimator.ofFloat(headlightGlow, "scaleY", 0.5f, 1f).apply {
            duration = 600
            startDelay = 1000
            interpolator = DecelerateInterpolator()
        }

        // 5. 状态文字切换 + 跳动 (1200~1800ms)
        val textSwitch = statusText?.let {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1
                startDelay = 1200
                addUpdateListener { _ ->
                    it.text = "已开机"
                    it.setTextColor(theme.statusOnColor)
                }
            }
        }
        val textScaleXUp = statusText?.let {
            ObjectAnimator.ofFloat(it, "scaleX", 1f, 1.1f).apply {
                duration = 150
                startDelay = 1250
            }
        }
        val textScaleYUp = statusText?.let {
            ObjectAnimator.ofFloat(it, "scaleY", 1f, 1.1f).apply {
                duration = 150
                startDelay = 1250
            }
        }
        val textScaleXDown = statusText?.let {
            ObjectAnimator.ofFloat(it, "scaleX", 1.1f, 1f).apply {
                duration = 150
                startDelay = 1400
            }
        }
        val textScaleYDown = statusText?.let {
            ObjectAnimator.ofFloat(it, "scaleY", 1.1f, 1f).apply {
                duration = 150
                startDelay = 1400
            }
        }

        // 6. 按钮高亮 (1500~2000ms)
        val btnOnAlpha = btnOn?.let {
            ObjectAnimator.ofFloat(it, "alpha", 0.5f, 1f).apply {
                duration = 500
                startDelay = 1500
            }
        }
        val btnOffAlpha = btnOff?.let {
            ObjectAnimator.ofFloat(it, "alpha", 1f, 0.4f).apply {
                duration = 500
                startDelay = 1500
            }
        }
        val btnSeatAlpha = btnSeat?.let {
            ObjectAnimator.ofFloat(it, "alpha", 0.4f, 1f).apply {
                duration = 500
                startDelay = 1500
            }
        }

        // 7. 车辆上浮 (1800~2500ms)
        val vehicleFloatUp = vehicleView?.let {
            ObjectAnimator.ofFloat(
                it, "translationY",
                0f, -dpToPx(context, 4f).toFloat()
            ).apply {
                duration = 700
                startDelay = 1800
                interpolator = AccelerateDecelerateInterpolator()
            }
        }

        // ---- 组合 AnimatorSet ----
        val animSet = AnimatorSet()
        animSet.playTogether(*listOfNotNull(
            scanLineAlphaIn, scanLineAlphaOut, scanLineMove,
            ringFadeIn, ringScaleX, ringScaleY,
            vehicleBrightnessAnim, vehicleAlphaAnim,
            headlightAlpha, headlightScaleX, headlightScaleY,
            textSwitch, textScaleXUp, textScaleYUp, textScaleXDown, textScaleYDown,
            btnOnAlpha, btnOffAlpha, btnSeatAlpha,
            vehicleFloatUp
        ).toTypedArray())
        animSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                activeAnimators.remove(animSet)
                startIdleRotation(energyRing)
                Log.d(TAG, "开机动画完成")
            }
        })

        currentSet = animSet
        animSet.start()
        activeAnimators.add(animSet)
    }

    /**
     * 关机动画（约 2.0s）
     * 编排：大灯熄灭 → 能量环变暗 → 车辆变暗 → 能量环收缩 → 文字震动 → 车辆下沉
     */
    fun playPowerOff(rootView: View) {
        cancelAll()
        val context = rootView.context
        val theme = getThemeColors(context)

        val vehicleView = safeFind<View>(rootView, ID_VEHICLE_STAGE, "vehicle_anim")
        val container   = safeFind<ViewGroup>(rootView, ID_VEHICLE_CONTAINER, "vehicle_stage_container")
        val statusText  = safeFind<TextView>(rootView, ID_STATUS_TEXT, "tv_vehicle_status")
        val btnOn       = safeFind<View>(rootView, ID_BTN_POWER_ON, "btn_power_on")
        val btnOff      = safeFind<View>(rootView, ID_BTN_POWER_OFF, "btn_power_off")
        val btnSeat     = safeFind<View>(rootView, ID_BTN_SEAT, "btn_seat")

        if (container == null) {
            Log.e(TAG, "未找到 vehicle_stage_container，无法播放关机动画")
            return
        }

        stopIdleRotation()

        val energyRing    = container.findViewWithTag<View>(TAG_ENERGY_RING)
        val headlightGlow = container.findViewWithTag<View>(TAG_HEADLIGHT_GLOW)

        // 1. 大灯熄灭 (0~400ms)
        val headlightFade = headlightGlow?.let {
            ObjectAnimator.ofFloat(it, "alpha", 1f, 0f).apply {
                duration = 400
                interpolator = AccelerateInterpolator()
            }
        }
        val headlightShrinkX = headlightGlow?.let {
            ObjectAnimator.ofFloat(it, "scaleX", 1f, 0.8f).apply {
                duration = 400
                interpolator = AccelerateInterpolator()
            }
        }
        val headlightShrinkY = headlightGlow?.let {
            ObjectAnimator.ofFloat(it, "scaleY", 1f, 0.8f).apply {
                duration = 400
                interpolator = AccelerateInterpolator()
            }
        }

        // 2. 能量环变暗 (200~800ms)
        val ringDim = energyRing?.let {
            ObjectAnimator.ofFloat(it, "alpha", 0.6f, 0.2f).apply {
                duration = 600
                startDelay = 200
            }
        }
        val ringGray = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            startDelay = 200
            addUpdateListener { anim ->
                energyRing?.let { ring ->
                    val progress = anim.animatedValue as Float
                    val color = blendColors(theme.energyBlue, Color.GRAY, progress)
                    updateRingColor(ring, color)
                }
            }
        }

        // 3. 车辆变暗 (400~1000ms)
        val vehicleDarken = vehicleView?.let {
            createBrightnessAnimator(
                it, fromSat = 1f, toSat = 0f,
                fromBright = 1f, toBright = 0.6f, duration = 600
            ).apply { startDelay = 400 }
        }
        val vehicleFade = vehicleView?.let {
            ObjectAnimator.ofFloat(it, "alpha", 1f, 0.6f).apply {
                duration = 600
                startDelay = 400
                interpolator = AccelerateInterpolator()
            }
        }

        // 4. 能量环收缩 (600~1400ms)
        val ringShrinkX = energyRing?.let {
            ObjectAnimator.ofFloat(it, "scaleX", 1f, 0f).apply {
                duration = 800
                startDelay = 600
                interpolator = AccelerateInterpolator()
            }
        }
        val ringShrinkY = energyRing?.let {
            ObjectAnimator.ofFloat(it, "scaleY", 1f, 0f).apply {
                duration = 800
                startDelay = 600
                interpolator = AccelerateInterpolator()
            }
        }
        val ringFadeOut = energyRing?.let {
            ObjectAnimator.ofFloat(it, "alpha", 0.2f, 0f).apply {
                duration = 800
                startDelay = 600
                interpolator = AccelerateInterpolator()
            }
        }

        // 5. 状态文字切换 + 震动 (1200~1800ms)
        val textSwitch = statusText?.let {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1
                startDelay = 1200
                addUpdateListener { _ ->
                    it.text = "已关机"
                    it.setTextColor(theme.statusOffColor)
                }
            }
        }
        val textShake = statusText?.let {
            ObjectAnimator.ofFloat(
                it, "translationX",
                0f, 3f, -3f, 3f, -3f, 0f
            ).apply {
                duration = 200
                startDelay = 1400
            }
        }

        // 6. 车辆下沉 (1500~2000ms)
        val currentTy = vehicleView?.translationY ?: 0f
        val vehicleSink = vehicleView?.let {
            ObjectAnimator.ofFloat(
                it, "translationY", currentTy, 0f
            ).apply {
                duration = 500
                startDelay = 1500
                interpolator = DecelerateInterpolator()
            }
        }

        // 7. 按钮状态
        val btnOnAlpha = btnOn?.let {
            ObjectAnimator.ofFloat(it, "alpha", 1f, 0.4f).apply {
                duration = 500
                startDelay = 1500
            }
        }
        val btnOffAlpha = btnOff?.let {
            ObjectAnimator.ofFloat(it, "alpha", 0.5f, 1f).apply {
                duration = 500
                startDelay = 1500
            }
        }
        val btnSeatAlpha = btnSeat?.let {
            ObjectAnimator.ofFloat(it, "alpha", 1f, 0.4f).apply {
                duration = 500
                startDelay = 1500
            }
        }

        val animSet = AnimatorSet()
        animSet.playTogether(*listOfNotNull(
            headlightFade, headlightShrinkX, headlightShrinkY,
            ringDim, ringGray,
            vehicleDarken, vehicleFade,
            ringShrinkX, ringShrinkY, ringFadeOut,
            textSwitch, textShake,
            vehicleSink,
            btnOnAlpha, btnOffAlpha, btnSeatAlpha
        ).toTypedArray())
        animSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                activeAnimators.remove(animSet)
                Log.d(TAG, "关机动画完成")
            }
        })

        currentSet = animSet
        animSet.start()
        activeAnimators.add(animSet)
    }

    /**
     * 开坐桶动画（约 1.2s）
     * 编排：按钮脉冲两次 + 橙色高亮
     */
    fun playSeatOpen(rootView: View) {
        cancelAll()
        val context = rootView.context
        val theme = getThemeColors(context)

        val btnSeat    = safeFind<View>(rootView, ID_BTN_SEAT, "btn_seat")
        val statusText = safeFind<TextView>(rootView, ID_STATUS_TEXT, "tv_vehicle_status")

        // 按钮脉冲：scale 弹性两次
        val pulse1ScaleX = btnSeat?.let {
            ObjectAnimator.ofFloat(it, "scaleX", 1f, 1.2f, 1f).apply {
                duration = 300
                interpolator = OvershootInterpolator(1.5f)
            }
        }
        val pulse1ScaleY = btnSeat?.let {
            ObjectAnimator.ofFloat(it, "scaleY", 1f, 1.2f, 1f).apply {
                duration = 300
                interpolator = OvershootInterpolator(1.5f)
            }
        }
        val pulse2ScaleX = btnSeat?.let {
            ObjectAnimator.ofFloat(it, "scaleX", 1f, 1.15f, 1f).apply {
                duration = 300
                startDelay = 350
                interpolator = OvershootInterpolator(1.2f)
            }
        }
        val pulse2ScaleY = btnSeat?.let {
            ObjectAnimator.ofFloat(it, "scaleY", 1f, 1.15f, 1f).apply {
                duration = 300
                startDelay = 350
                interpolator = OvershootInterpolator(1.2f)
            }
        }

        // 状态文字变橙
        val textColor = statusText?.let {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1
                startDelay = 200
                addUpdateListener { _ ->
                    it.setTextColor(theme.energyOrange)
                    it.text = "坐桶已开"
                }
            }
        }

        val animSet = AnimatorSet()
        animSet.playTogether(*listOfNotNull(
            pulse1ScaleX, pulse1ScaleY, pulse2ScaleX, pulse2ScaleY, textColor
        ).toTypedArray())
        animSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                activeAnimators.remove(animSet)
                Log.d(TAG, "坐桶动画完成")
            }
        })

        currentSet = animSet
        animSet.start()
        activeAnimators.add(animSet)
    }

    /**
     * 取消所有正在运行的动画（包括持续旋转）
     */
    fun cancelAll() {
        currentSet?.cancel()
        currentSet = null
        stopIdleRotation()

        val iter = activeAnimators.iterator()
        while (iter.hasNext()) {
            val anim = iter.next()
            if (anim.isRunning) anim.cancel()
            iter.remove()
        }
    }

    /**
     * 在 Fragment.onDestroyView 中调用，取消动画并清理动态 overlay
     */
    fun onDestroy(rootView: View?) {
        cancelAll()
        rootView?.let {
            val container = safeFind<ViewGroup>(it, ID_VEHICLE_CONTAINER, "vehicle_stage_container")
            container?.let { c ->
                removeOverlay(c, TAG_SCAN_LINE)
                removeOverlay(c, TAG_ENERGY_RING)
                removeOverlay(c, TAG_HEADLIGHT_GLOW)
                removeOverlay(c, TAG_DARKEN_OVERLAY)
            }
        }
    }

    // ============================================================
    // 内部实现
    // ============================================================

    private fun startIdleRotation(energyRing: View?) {
        if (energyRing == null) return
        stopIdleRotation()
        idleRotation = ObjectAnimator.ofFloat(energyRing, "rotation", 0f, 360f).apply {
            duration = 20000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        activeAnimators.add(idleRotation!!)
    }

    private fun stopIdleRotation() {
        idleRotation?.let {
            activeAnimators.remove(it)
            it.cancel()
        }
        idleRotation = null
    }

    private inline fun <reified T : View> safeFind(root: View, id: Int, name: String): T? {
        return try {
            val v = root.findViewById<T>(id)
            if (v == null) Log.w(TAG, "未找到视图 R.id.$name，相关动画将跳过")
            v
        } catch (e: Exception) {
            Log.w(TAG, "查找视图 R.id.$name 异常: ${e.message}，相关动画将跳过")
            null
        }
    }

    private fun getThemeColors(context: Context): ThemeColors {
        val isLight = isLightTheme(context)
        return if (isLight) {
            ThemeColors(
                energyBlue    = Color.parseColor("#7C3AED"),
                energyOrange  = Color.parseColor("#EA580C"),
                alertRed      = Color.parseColor("#DC2626"),
                signalGreen   = Color.parseColor("#059669"),
                statusOnColor = Color.parseColor("#7C3AED"),
                statusOffColor= Color.parseColor("#DC2626"),
                textPrimary   = Color.parseColor("#1E293B"),
                textSecondary = Color.parseColor("#64748B"),
                glowAlpha     = 0x40
            )
        } else {
            ThemeColors(
                energyBlue    = Color.parseColor("#00D4FF"),
                energyOrange  = Color.parseColor("#FF6B35"),
                alertRed      = Color.parseColor("#EF4444"),
                signalGreen   = Color.parseColor("#10B981"),
                statusOnColor = Color.parseColor("#00D4FF"),
                statusOffColor= Color.parseColor("#EF4444"),
                textPrimary   = Color.parseColor("#F1F5F9"),
                textSecondary = Color.parseColor("#94A3B8"),
                glowAlpha     = 0x66
            )
        }
    }

    private fun isLightTheme(context: Context): Boolean {
        return lightThemeOverride ?: fallbackIsLightTheme(context)
    }

    private fun fallbackIsLightTheme(context: Context): Boolean {
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_NO
    }

    private data class ThemeColors(
        val energyBlue: Int,
        val energyOrange: Int,
        val alertRed: Int,
        val signalGreen: Int,
        val statusOnColor: Int,
        val statusOffColor: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val glowAlpha: Int
    )

    // ---- Overlay 动态创建 ----

    private fun ensureScanLine(container: ViewGroup, color: Int): View {
        container.findViewWithTag<View>(TAG_SCAN_LINE)?.let { return it }
        val view = View(container.context).apply {
            tag = TAG_SCAN_LINE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dpToPx(container.context, 3f)
            ).apply {
                gravity = Gravity.TOP
            }
            alpha = 0f
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, color, Color.TRANSPARENT)
            )
        }
        container.addView(view)
        return view
    }

    private fun ensureEnergyRing(container: ViewGroup, color: Int): View {
        container.findViewWithTag<View>(TAG_ENERGY_RING)?.let { return it }
        val size = dpToPx(container.context, 220f)
        val view = View(container.context).apply {
            tag = TAG_ENERGY_RING
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER
            }
            alpha = 0f
            background = createRingDrawable(color, dpToPx(container.context, 2f))
        }
        container.addView(view)
        return view
    }

    private fun ensureHeadlightGlow(container: ViewGroup, color: Int, glowAlpha: Int): ImageView {
        container.findViewWithTag<View>(TAG_HEADLIGHT_GLOW)?.let { return it as ImageView }
        val w = dpToPx(container.context, 140f)
        val h = dpToPx(container.context, 100f)
        val glowColor = (glowAlpha shl 24) or (color and 0x00FFFFFF)
        val iv = ImageView(container.context).apply {
            tag = TAG_HEADLIGHT_GLOW
            layoutParams = FrameLayout.LayoutParams(w, h).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                topMargin = dpToPx(container.context, 50f)
            }
            alpha = 0f
            scaleX = 0.5f
            scaleY = 0.5f
            setImageDrawable(createRadialGlowDrawable(container.context, glowColor))
        }
        container.addView(iv)
        return iv
    }

    private fun createRingDrawable(color: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(strokeWidth, color)
            setColor(Color.TRANSPARENT)
        }
    }

    private fun createRadialGlowDrawable(context: Context, glowColor: Int): BitmapDrawable {
        val size = dpToPx(context, 140f)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = size / 2f
        val cy = size / 2f
        val radius = size / 2f
        val gradient = RadialGradient(
            cx, cy, radius,
            intArrayOf(glowColor, Color.TRANSPARENT),
            floatArrayOf(0.2f, 1.0f),
            Shader.TileMode.CLAMP
        )
        paint.shader = gradient
        canvas.drawCircle(cx, cy, radius, paint)
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun updateRingColor(ring: View, color: Int) {
        (ring.background as? GradientDrawable)?.let {
            it.setStroke(dpToPx(ring.context, 2f), color)
        }
    }

    private fun removeOverlay(container: ViewGroup, tag: String) {
        container.findViewWithTag<View>(tag)?.let {
            container.removeView(it)
        }
    }

    // ---- 亮度 / 饱和度动画 ----

    private fun createBrightnessAnimator(
        target: View,
        fromSat: Float, toSat: Float,
        fromBright: Float, toBright: Float,
        duration: Long
    ): ValueAnimator {
        return ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            addUpdateListener { anim ->
                val progress = anim.animatedValue as Float
                val sat = fromSat + (toSat - fromSat) * progress
                val bright = fromBright + (toBright - fromBright) * progress
                applyColorMatrix(target, sat, bright)
            }
        }
    }

    private fun applyColorMatrix(target: View?, saturation: Float, brightness: Float) {
        val t = target ?: return
        // vehicle_anim 是 VehicleStageView：走其原生 bodyBrightness/bodySaturation（ColorMatrix 作用于车身图层）
        if (t is VehicleStageView) {
            t.bodySaturation = saturation
            t.bodyBrightness = brightness
        }
        // 普通 View 无法应用饱和度矩阵（View 无 ColorMatrix setter），跳过
    }

    private fun blendColors(from: Int, to: Int, ratio: Float): Int {
        val r = (Color.red(from) * (1 - ratio) + Color.red(to) * ratio).toInt()
        val g = (Color.green(from) * (1 - ratio) + Color.green(to) * ratio).toInt()
        val b = (Color.blue(from) * (1 - ratio) + Color.blue(to) * ratio).toInt()
        val a = (Color.alpha(from) * (1 - ratio) + Color.alpha(to) * ratio).toInt()
        return Color.argb(a.coerceIn(0, 255), r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    private fun dpToPx(context: Context, dp: Float): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
