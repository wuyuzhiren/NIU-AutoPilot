package com.niu.autopilot.vehicle.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * 设备动画能力分级
 *
 * 根据运行内存、API 级别、CPU 核心数自动判定动画等级，
 * 用于低端机降级：减少粒子数、关闭发光阴影、缩短时长等。
 *
 * 等级说明：
 *   LEVEL_HIGH   (3) — 全特效：粒子、扫描线、发光、冲击波
 *   LEVEL_MEDIUM (2) — 中特效：精简粒子、无扫描线、静态冲击波
 *   LEVEL_LOW    (1) — 保流畅：仅透明度/缩放动画，无发光，无粒子
 */
object DeviceCapability {

    const val LEVEL_LOW = 1
    const val LEVEL_MEDIUM = 2
    const val LEVEL_HIGH = 3

    @Volatile
    private var cachedLevel: Int = -1

    /** 当前设备动画能力等级（首次调用时计算并缓存） */
    fun level(context: Context): Int {
        if (cachedLevel != -1) return cachedLevel
        cachedLevel = computeLevel(context)
        return cachedLevel
    }

    private fun computeLevel(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalMemMB = (memInfo.totalMem / (1024 * 1024)).toInt()

        val runtime = Runtime.getRuntime()
        val processors = runtime.availableProcessors()

        return when {
            // 高端：运存 ≥ 6GB，API 29+，4核+
            totalMemMB >= 5800 && Build.VERSION.SDK_INT >= 29 && processors >= 4 -> LEVEL_HIGH
            // 中端：运存 ≥ 4GB，API 26+，4核+
            totalMemMB >= 3800 && Build.VERSION.SDK_INT >= 26 && processors >= 4 -> LEVEL_MEDIUM
            // 低端：其余
            else -> LEVEL_LOW
        }
    }

    /** 是否应启用发光阴影（setShadowLayer） */
    fun enableGlow(context: Context): Boolean = level(context) >= LEVEL_MEDIUM

    /** 是否应启用粒子效果 */
    fun enableParticles(context: Context): Boolean = level(context) >= LEVEL_MEDIUM

    /** 是否应启用扫描线 */
    fun enableScanLine(context: Context): Boolean = level(context) >= LEVEL_HIGH

    /** 粒子数量上限 */
    fun maxParticles(context: Context): Int = when (level(context)) {
        LEVEL_HIGH -> 80
        LEVEL_MEDIUM -> 40
        else -> 0
    }

    /** 动画时长缩放系数（低端机缩短时长保流畅） */
    fun durationScale(context: Context): Float = when (level(context)) {
        LEVEL_HIGH -> 1.0f
        LEVEL_MEDIUM -> 0.85f
        else -> 0.7f
    }

    /** 是否启用雷达/能量环持续旋转 */
    fun enableRotatingRing(context: Context): Boolean = level(context) >= LEVEL_MEDIUM

    /** 是否启用车辆悬浮呼吸 */
    fun enableFloating(context: Context): Boolean = level(context) >= LEVEL_HIGH
}
