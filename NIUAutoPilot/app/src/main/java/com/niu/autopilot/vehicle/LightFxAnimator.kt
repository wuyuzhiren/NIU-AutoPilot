package com.niu.autopilot.vehicle

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 车辆灯效动画编排器 v3.4
 *
 * 核心改进（相对 v3.2 的生硬感）：
 * 1. **统一时间轴**：所有灯效交由本类一个 AnimatorSet 编排，不再两套并行打架。
 * 2. **指数余辉波形**：真实 LED 点亮是"瞬时 + 尖峰"，熄灭是"瞬时 0 + 指数余辉"，
 *    而不是 LinearInterpolator 的三角波。公式 I(t) = A·exp(-t/τ)。
 * 3. **双闪左右同相**：真车危险警告灯左右同时闪（交替闪是转向灯）。
 * 4. **坐桶欠阻尼弹簧**：二阶弹簧模拟，有回弹超调，不是 OvershootInterpolator 的一次性过冲。
 * 5. **开机顺序点亮**：大灯照亮 → 光束推出 → 尾灯自检 2 闪 → 地面光斑。
 *
 * v3.4 变更（设计规范 §5.4 的接口缺口修复）：
 *   本类原本只持有 layer（灯光层），拿不到 VehicleStageView，
 *   因此 playBucketOpen/Close 只驱动了箱内透光、**没有驱动坐垫几何变换**。
 *   现通过 [BucketTransformSink] 把坐桶位移/旋转回灌给舞台。
 */
class LightFxAnimator(
    private val layer: LightFxLayer,
    /** v3.4: 坐桶几何变换回灌口。为 null 时只做灯效，不做几何（向后兼容）。 */
    private val bucketSink: BucketTransformSink? = null
) {

    /**
     * v3.4: 坐桶几何变换接收方（由 VehicleStageView 实现）。
     * 之所以用接口而不是直接传 View，是为了让 LightFxAnimator 不依赖具体舞台实现。
     */
    interface BucketTransformSink {
        /** 坐垫抬升位移（dp，负值=向上） */
        fun setBucketLift(dp: Float)
        /** 坐垫掀开角度（度，负值=向上翻） */
        fun setBucketRotation(deg: Float)
        /** 坐桶图层可见度 0~1 */
        fun setBucketVisible(v: Float)
    }

    private var current: AnimatorSet? = null

    /** 系统动画缩放（无障碍"关闭动画"时直接跳终态） */
    var durationScale = 1f

    // ==================== 物理参数常量 ====================
    companion object {
        /** 双闪：1.43Hz（国标危险警告灯约 86 次/分） */
        private const val HAZARD_PERIOD_MS = 700L
        /** 双闪亮段占比 50% */
        private const val HAZARD_ON_MS = 350L
        /** LED 熄灭余辉时间常数 τ（毫秒） */
        private const val GLOW_TAU_MS = 150.0
        /** 双闪次数（锁车确认用，2 次足够；过长会烦） */
        private const val HAZARD_FLASHES = 2

        /** 坐桶：v3.5 起改为交叉淡变，不再用弹簧几何，保留常量仅为兼容旧调用方 */
        private const val BUCKET_OPEN_K = 180f
        private const val BUCKET_OPEN_ZETA = 0.62
        private const val BUCKET_OPEN_OMEGA = 18.0
        /** 坐桶弹簧：关（更硬、更快） */
        private const val BUCKET_CLOSE_ZETA = 0.75
        private const val BUCKET_CLOSE_OMEGA = 22.0
    }

    /**
     * 欠阻尼二阶弹簧响应，返回 0..1 归一化位置（会超过 1 产生回弹）
     * x(t) = 1 - e^(-ζωₙt)·[cos(ωd t) + (ζωₙ/ωd)·sin(ωd t)]
     */
    private fun springValue(tSec: Double, zeta: Double, omega: Double): Float {
        val wd = omega * sqrt(1.0 - zeta * zeta)
        val e = exp(-zeta * omega * tSec)
        val v = 1.0 - e * (cos(wd * tSec) + (zeta * omega / wd) * sin(wd * tSec))
        return v.toFloat()
    }

    /** 生成弹簧插值器（供 ValueAnimator 使用） */
    private fun springInterpolator(zeta: Double, omega: Double, totalSec: Double): Interpolator =
        Interpolator { input ->
            val t = (input * totalSec).coerceAtLeast(0.0)
            springValue(t, zeta, omega).coerceIn(-0.2f, 1.3f)
        }

    /** 指数余辉插值器：模拟 LED 熄灭拖尾 */
    private fun decayInterpolator(tauMs: Double, totalMs: Double): Interpolator =
        Interpolator { input ->
            val ms = input * totalMs
            exp(-ms / tauMs).toFloat()
        }

    private fun cancel() {
        current?.cancel()
        current = null
    }

    private fun play(set: AnimatorSet, onEnd: (() -> Unit)? = null) {
        cancel()
        // 无障碍"移除动画"场景：直接跳到终态
        if (durationScale <= 0.01f) {
            set.end()
            onEnd?.invoke()
            return
        }
        set.startDelay = 0
        current = set
        if (onEnd != null) {
            set.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { onEnd() }
            })
        }
        set.start()
    }

    // ==================== 开机 ====================

    /**
     * 开机动画（v3.4，总时长 1750ms，设计规范 §5.2）
     *
     * 叙事顺序（用户直觉的因果链）：
     *   灯芯预闪 → 光晕绽开 → 光锥推出（往前照出去）→ 光束纹理 → 尾灯自检 2 闪 → 地面光斑
     *
     * 关键修正：旧实现把地面光斑放在 300ms、尾灯在 380ms，两者交叠且顺序反了；
     * 现在改为「光束落定 → 尾灯确认 → 地面照亮」的因果链。
     */
    fun playPowerOn(onEnd: (() -> Unit)? = null) {
        layer.resetAll()

        val d = { ms: Long -> (ms * durationScale.coerceAtLeast(0.1f)).toLong() }

        // ① 灯芯预闪 0–120ms：先有一个亮点，才有"灯被点亮"的因果
        val core = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = d(120)
            interpolator = DecelerateInterpolator(2.5f)
            addUpdateListener { layer.headCoreAlpha = it.animatedValue as Float }
        }
        // ② 光晕绽开 0–420ms：与①同时，稍慢，形成"亮起来"的体积感
        val halo = ValueAnimator.ofFloat(0f, 0.92f).apply {
            duration = d(420)
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { layer.haloAlpha = it.animatedValue as Float }
        }
        // ③ 光锥推出 120–680ms：核心镜头「往前照出去」
        val cone = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = d(560)
            startDelay = d(120)
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val p = it.animatedValue as Float
                layer.coneProgress = p
                layer.coneAlpha = p.coerceAtMost(0.85f)
            }
        }
        // ④ 光束纹理 260–680ms：跟随光锥末端，不要比光锥早到
        val tex = ValueAnimator.ofFloat(0f, 0.85f).apply {
            duration = d(420)
            startDelay = d(260)
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { layer.beamTexAlpha = it.animatedValue as Float }
        }
        // ⑤ 尾灯自检 2 闪 700–1160ms（460ms）：独立成拍，"确认"语义
        //    点亮=尖峰(前50ms到位)、熄灭=指数余辉 τ=150ms（不要线性归零）
        val tail = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = d(460)
            startDelay = d(700)
            addUpdateListener {
                val t = (it.animatedValue as Float) * 460f  // 换算到毫秒
                layer.tailAlpha = selfTestWave(t).coerceIn(0f, 1f)
            }
        }
        // ⑥ 地面光斑 1160–1650ms（490ms）：最后落定，镜头收尾
        val ground = ValueAnimator.ofFloat(0f, 0.9f).apply {
            duration = d(490)
            startDelay = d(1160)
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { layer.groundAlpha = it.animatedValue as Float }
        }

        val set = AnimatorSet()
        set.playTogether(core, halo, cone, tex, ground, tail)
        play(set) {
            // 稳态：尾灯保持微亮（已开机状态有生命感）
            layer.tailAlpha = 0.72f
            onEnd?.invoke()
        }
    }

    // ==================== 关机 ====================

    /**
     * 关机动画（v3.4，总时长 2080ms，设计规范 §5.3）
     * 顺序：双闪（左右同相）2 次 0–1400ms → 光锥收缩 1400–1820ms → 淡出
     *
     * 关键修正：旧实现 shrink 的 startDelay = 1280ms，即在最后一次双闪的
     * 指数余辉里就开始收缩，把尾音吞掉了。现在改为双闪完整结束(1400ms)再收缩。
     */
    fun playPowerOff(onEnd: (() -> Unit)? = null) {
        val d = { ms: Long -> (ms * durationScale.coerceAtLeast(0.1f)).toLong() }
        val hazardTotal = HAZARD_PERIOD_MS * HAZARD_FLASHES  // 1400ms

        // 1) 双闪：左右同相，2 次，700ms 周期 / 350ms 亮段
        //    点亮=前50ms尖峰到位，熄灭=指数余辉 τ=150ms
        val hazard = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = d(hazardTotal)
            addUpdateListener {
                val total = (it.animatedValue as Float) * hazardTotal
                val phase = total % HAZARD_PERIOD_MS
                layer.turnAlpha = if (phase < HAZARD_ON_MS) {
                    if (phase < 50f) phase / 50f else 1f
                } else {
                    val offElapsed = phase - HAZARD_ON_MS
                    (exp(-offElapsed / GLOW_TAU_MS) * 0.92f).toFloat().coerceIn(0f, 1f)
                }
            }
        }
        // 2) 光锥收缩：双闪【完整结束】后才开始
        val shrink = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = d(420)
            startDelay = d(hazardTotal)
            interpolator = DecelerateInterpolator(2.0f)
            addUpdateListener {
                val p = it.animatedValue as Float
                layer.coneProgress = p
                layer.coneAlpha = p * 0.85f
                layer.beamTexAlpha = p * 0.85f
            }
        }
        // 3) 淡出：与收缩重叠 60ms（避免"先黑再收"），但木要早于双闪结束
        val dim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = d(360)
            startDelay = d(hazardTotal + 60)
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val p = it.animatedValue as Float
                layer.haloAlpha = 0.92f * p
                layer.headCoreAlpha = p
                layer.groundAlpha = 0.9f * p
                layer.tailAlpha = 0.72f * p
            }
        }

        val set = AnimatorSet()
        set.playTogether(hazard, shrink, dim)
        play(set) {
            layer.resetAll()
            onEnd?.invoke()
        }
    }

    // ==================== 坐桶 ====================

    /**
     * 开坐桶（v3.5，520ms）
     *
     * v3.5 重做：**不再做任何几何变换**，改为 body → bucket 两张图的交叉淡变。
     * 因为 `nx_body.webp` 与 `nx_bucket.webp` 是同车型同视角同坐标系的配对图，
     * 掀盖动作已经烘焙在 bucket 图里。旧实现（位移 -12dp + 旋转 -10°，pivot 还
     * 算错成 ImageView 宽高的 0.62/0.38）就是用户抱怨的「整个车辆跳动」。
     *
     * 曲线：用「慢起快收」的 Decelerate —— 机械掀盖是弹簧顶起，
     * 前段慢（气体弹簧建立压力）、后段快（越过临界点后加速）。
     * 淡变用 alpha 而非位移，所以不需要弹簧超调（超调会让两张图来回闪）。
     *
     * 箱内透光：淡变过半后亮起，因为只有掀开后才看得见储物桶内部。
     */
    fun playBucketOpen(onEnd: (() -> Unit)? = null) {
        val d = { ms: Long -> (ms * durationScale.coerceAtLeast(0.1f)).toLong() }
        val totalMs = 520L

        val fade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = d(totalMs)
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val p = it.animatedValue as Float
                bucketSink?.setBucketVisible(p.coerceIn(0f, 1f))
                // 箱内透光：掀开过半才亮起
                layer.bucketAlpha = ((p - 0.45f) / 0.55f).coerceIn(0f, 1f) * 0.85f
            }
        }

        val set = AnimatorSet()
        set.playTogether(fade)
        play(set) {
            bucketSink?.setBucketVisible(1f)
            onEnd?.invoke()
        }
    }

    /**
     * 关坐桶（v3.5，400ms）
     *
     * 合盖比掀开快、且更「硬」（没有弹簧回弹的余地，是自由落体 + 阻尼），
     * 用 Accelerate 起步慢后段快，箱灯在合盖后 120ms 才熄（灯被盖住才暗下去）。
     */
    fun playBucketClose(onEnd: (() -> Unit)? = null) {
        val d = { ms: Long -> (ms * durationScale.coerceAtLeast(0.1f)).toLong() }
        val totalMs = 400L

        val fade = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = d(totalMs)
            interpolator = AccelerateInterpolator(1.4f)
            addUpdateListener {
                val p = it.animatedValue as Float
                bucketSink?.setBucketVisible(p.coerceIn(0f, 1f))
            }
        }

        // 箱灯在合盖中段开始衰减，结束时归零
        val lightOff = ValueAnimator.ofFloat(0.85f, 0f).apply {
            duration = d(280)
            startDelay = d(120)
            addUpdateListener { layer.bucketAlpha = it.animatedValue as Float }
        }

        val set = AnimatorSet()
        set.playTogether(fade, lightOff)
        play(set) {
            bucketSink?.setBucketVisible(0f)
            layer.bucketAlpha = 0f
            onEnd?.invoke()
        }
    }

    // ==================== 波形工具 ====================

    /**
     * 尾灯自检波形（460ms 内 2 次短闪，设计规范 §5.2）
     * 亮=前50ms线性到位后保持；灭=指数余辉 τ=150ms
     *
     * @param t 相对起点的毫秒偏移 (0..460)
     */
    private fun selfTestWave(t: Float): Float {
        return when {
            // 亮 1：0-140ms
            t < 140f -> if (t < 50f) t / 50f * 0.9f else 0.9f
            // 灭 1：140-230ms（指数余辉，非线性归零）
            t < 230f -> (0.9f * exp(-((t - 140f) / GLOW_TAU_MS))).toFloat()
            // 亮 2：230-370ms
            t < 370f -> {
                val u = t - 230f
                if (u < 50f) u / 50f * 0.9f else 0.9f
            }
            // 灭 2：370-460ms
            else -> (0.9f * exp(-((t - 370f) / GLOW_TAU_MS))).toFloat()
        }
    }


    /** 稳态呼吸（已开机时尾灯轻微呼吸，周期 5.5s） */
    fun startIdleBreath() {
        cancel()
        val breath = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (5500 * durationScale.coerceAtLeast(0.1f)).toLong()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener {
                val t = it.animatedValue as Float
                // 余弦呼吸：0.66 ~ 0.78
                layer.tailAlpha = 0.72f + 0.06f * cos(2 * PI * t).toFloat()
            }
        }
        breath.start()
    }

    fun stopAll() {
        cancel()
        layer.resetAll()
    }
}
