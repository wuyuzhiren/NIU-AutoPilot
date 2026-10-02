package com.niu.autopilot.vehicle

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.*
import com.niu.autopilot.vehicle.util.AnimationGuard
import com.niu.autopilot.vehicle.util.DeviceCapability

/**
 * 车辆动画编排器
 *
 * 职责：将「开机 / 关机 / 坐桶」三段动画封装为完整的 AnimatorSet，
 * 统一对接 [VehicleStageView] 的视觉属性与 [VehicleStateMachine] 的状态流转。
 *
 * 设计文档对应：
 *   - 开机动画 → 设计方案 §3.1（总时长约 2.5s，核心视觉 1.5s 内完成）
 *   - 关机动画 → 设计方案 §3.2（总时长约 2.0s，节奏比开机更快）
 *   - 坐桶动画 → 设计方案 §3.3（总时长约 2.0s）
 *
 * 线程安全：所有动画操作必须在主线程执行。
 */
class VehicleAnimationController(
    private val stageView: VehicleStageView,
    private val stateMachine: VehicleStateMachine,
    private val listener: VehicleAnimationListener? = null
) {
    private val guard = AnimationGuard()
    private var currentAnimator: AnimatorSet? = null
    private val context = stageView.context
    private val durationScale = DeviceCapability.durationScale(context)
    private var cancelledFlag = false

    /** 当前是否正在播放动画 */
    val isAnimating: Boolean get() = guard.locked

    /**
     * 播放开机动画
     *
     * 时序（已按 [DeviceCapability.durationScale] 自动缩放）：
     *   0ms    : 底座光晕脉冲扩散 (alpha 0.3→1.0)
     *   300ms  : 扫描线从顶部扫到底部（高端机）
     *   500ms  : 能量环从中心扩散 (scale 0→1, alpha 0→0.6)
     *   800ms  : 车身从灰度渐变全彩 (sat 0.2→1, brightness 0.6→1)
     *   1000ms : 前大灯点亮 + 光束射出 (alpha 0→1)
     *   1200ms : 仪表盘辉光环亮起 (alpha 0→1)
     *   1400ms : 尾灯点亮 (alpha 0→0.9)
     *   1800ms : 车辆轻微上浮 (floatOffset 0→-4dp)
     *   2000ms : 能量环进入持续旋转稳态
     */
    fun playPowerOnAnimation(): Boolean {
        if (!guard.lock()) return false
        if (!stateMachine.transition(VehicleState.PoweringOn)) {
            guard.unlock()
            return false
        }

        listener?.onAnimationStarted(VehicleState.PoweringOn)
        cancelCurrent()

        val ds = durationScale
        val set = AnimatorSet()

        // Step 1: 底座光晕扩散
        val glowExpand = ObjectAnimator.ofFloat(stageView, "glowAlpha", 0.3f, 1.0f).apply {
            duration = (300 * ds).toLong()
            interpolator = AccelerateDecelerateInterpolator()
        }

        // Step 2: 扫描线（高端机）
        val scanLineAnimator = if (DeviceCapability.enableScanLine(context)) {
            AnimatorSet().apply {
                playSequentially(
                    ObjectAnimator.ofFloat(stageView, "scanLineY", 0f, 1f).apply {
                        duration = (500 * ds).toLong()
                        interpolator = LinearInterpolator()
                    },
                    ObjectAnimator.ofFloat(stageView, "scanLineAlpha", 0.3f, 0f).apply {
                        duration = (200 * ds).toLong()
                    }
                )
            }
        } else null

        // Step 3: 能量环扩散
        val ringScale = ObjectAnimator.ofFloat(stageView, "ringScale", 0f, 1f).apply {
            duration = (700 * ds).toLong()
            startDelay = (500 * ds).toLong()
            interpolator = OvershootInterpolator(1.2f)
        }
        val ringAlpha = ObjectAnimator.ofFloat(stageView, "ringAlpha", 0f, 0.6f).apply {
            duration = (700 * ds).toLong()
            startDelay = (500 * ds).toLong()
        }

        // Step 4: 车身从灰度→全彩 + 亮度提升
        val bodySat = ObjectAnimator.ofFloat(stageView, "bodySaturation", 0.2f, 1.0f).apply {
            duration = (700 * ds).toLong()
            startDelay = (800 * ds).toLong()
            interpolator = DecelerateInterpolator()
        }
        val bodyBright = ObjectAnimator.ofFloat(stageView, "bodyBrightness", 0.6f, 1.0f).apply {
            duration = (700 * ds).toLong()
            startDelay = (800 * ds).toLong()
            interpolator = DecelerateInterpolator()
        }

        // Step 5: 前大灯点亮 + 光束射出（光束 0→0.9，稍作延迟跟随大灯）
        val headLampAlpha = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (600 * ds).toLong()
            startDelay = (1000 * ds).toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                stageView.setLightAlpha(VehicleLightPoint.Type.HEADLAMP_LEFT, it.animatedValue as Float)
            }
        }
        val beamAlphaAnim = ObjectAnimator.ofFloat(stageView, "beamAlpha", 0f, 0.9f).apply {
            duration = (500 * ds).toLong()
            startDelay = (1100 * ds).toLong()
            interpolator = DecelerateInterpolator()
        }

        // Step 6: 仪表盘辉光环
        val dashboardAlpha = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (600 * ds).toLong()
            startDelay = (1200 * ds).toLong()
            addUpdateListener {
                stageView.setLightAlpha(VehicleLightPoint.Type.DASHBOARD_RING, it.animatedValue as Float)
            }
        }

        // Step 7: 尾灯点亮
        val tailLampAlpha = ValueAnimator.ofFloat(0f, 0.9f).apply {
            duration = (400 * ds).toLong()
            startDelay = (1400 * ds).toLong()
            addUpdateListener {
                stageView.setLightAlpha(VehicleLightPoint.Type.TAILLAMP, it.animatedValue as Float)
            }
        }

        // Step 8: 车辆轻微上浮
        val floatUp = ObjectAnimator.ofFloat(stageView, "floatOffsetDp", 0f, -4f).apply {
            duration = (700 * ds).toLong()
            startDelay = (1800 * ds).toLong()
            interpolator = DecelerateInterpolator()
        }

        // 组装
        set.play(glowExpand)
        scanLineAnimator?.let { set.play(it).after((300 * ds).toLong()) }
        set.play(ringScale).with(ringAlpha)
        set.play(bodySat).with(bodyBright)
        set.play(headLampAlpha)
        set.play(beamAlphaAnim)
        set.play(dashboardAlpha)
        set.play(tailLampAlpha)
        set.play(floatUp)

        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!cancelledFlag) {
                    stateMachine.transition(VehicleState.PoweredOn)
                    listener?.onAnimationCompleted(VehicleState.PoweredOn)
                }
                cancelledFlag = false
                guard.unlock()
                currentAnimator = null
            }

            override fun onAnimationCancel(animation: Animator) {
                cancelledFlag = true
                listener?.onAnimationCancelled(VehicleState.PoweringOn)
                guard.unlock()
                currentAnimator = null
            }
        })

        currentAnimator = set
        set.start()
        return true
    }

    /**
     * 播放关机动画
     *
     * 时序：
     *   0ms    : 光束熄灭 + 仪表盘环收缩
     *   200ms  : 尾灯熄灭
     *   400ms  : 前大灯熄灭
     *   600ms  : 车身变暗 + 降饱和度
     *   1000ms : 能量环收缩消失
     *   1200ms : 光晕收缩
     *   1500ms : 车辆下沉恢复
     *   结束   : 双闪效果（左右尾灯 0.5s 周期闪烁，持续约 4 秒，模拟真实锁车警示）
     */
    fun playPowerOffAnimation(): Boolean {
        if (!guard.lock()) return false
        if (!stateMachine.transition(VehicleState.PoweringOff)) {
            guard.unlock()
            return false
        }

        listener?.onAnimationStarted(VehicleState.PoweringOff)
        cancelCurrent()

        val ds = durationScale
        val set = AnimatorSet()

        // 光束熄灭
        val beamOff = ObjectAnimator.ofFloat(stageView, "beamAlpha", stageView.beamAlpha, 0f).apply {
            duration = (250 * ds).toLong()
        }

        // 仪表盘熄灭
        val dashboardOff = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = (300 * ds).toLong()
            addUpdateListener {
                stageView.setLightAlpha(VehicleLightPoint.Type.DASHBOARD_RING, it.animatedValue as Float)
            }
        }

        // 尾灯熄灭
        val tailLampOff = ValueAnimator.ofFloat(0.9f, 0f).apply {
            duration = (300 * ds).toLong()
            startDelay = (200 * ds).toLong()
            addUpdateListener {
                stageView.setLightAlpha(VehicleLightPoint.Type.TAILLAMP, it.animatedValue as Float)
            }
        }

        // 前大灯熄灭
        val headLampOff = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = (400 * ds).toLong()
            startDelay = (400 * ds).toLong()
            addUpdateListener {
                stageView.setLightAlpha(VehicleLightPoint.Type.HEADLAMP_LEFT, it.animatedValue as Float)
            }
        }

        // 车身变暗 + 灰度
        val bodySatOff = ObjectAnimator.ofFloat(stageView, "bodySaturation", 1.0f, 0.2f).apply {
            duration = (500 * ds).toLong()
            startDelay = (600 * ds).toLong()
            interpolator = AccelerateInterpolator()
        }
        val bodyBrightOff = ObjectAnimator.ofFloat(stageView, "bodyBrightness", 1.0f, 0.6f).apply {
            duration = (500 * ds).toLong()
            startDelay = (600 * ds).toLong()
            interpolator = AccelerateInterpolator()
        }

        // 能量环收缩
        val ringScaleOff = ObjectAnimator.ofFloat(stageView, "ringScale", 1f, 0f).apply {
            duration = (400 * ds).toLong()
            startDelay = (1000 * ds).toLong()
            interpolator = AccelerateInterpolator()
        }
        val ringAlphaOff = ObjectAnimator.ofFloat(stageView, "ringAlpha", 0.6f, 0f).apply {
            duration = (400 * ds).toLong()
            startDelay = (1000 * ds).toLong()
        }

        // 光晕收缩
        val glowOff = ObjectAnimator.ofFloat(stageView, "glowAlpha", 1.0f, 0.3f).apply {
            duration = (400 * ds).toLong()
            startDelay = (1200 * ds).toLong()
        }

        // 车辆下沉
        val floatDown = ObjectAnimator.ofFloat(stageView, "floatOffsetDp", -4f, 0f).apply {
            duration = (500 * ds).toLong()
            startDelay = (1500 * ds).toLong()
            interpolator = DecelerateInterpolator()
        }

        set.play(beamOff)
        set.play(dashboardOff)
        set.play(tailLampOff)
        set.play(headLampOff)
        set.play(bodySatOff).with(bodyBrightOff)
        set.play(ringScaleOff).with(ringAlphaOff)
        set.play(glowOff)
        set.play(floatDown)

        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!cancelledFlag) {
                    stateMachine.transition(VehicleState.Standby)
                    listener?.onAnimationCompleted(VehicleState.Standby)
                    // 触发双闪（模拟锁车后危险警告灯）
                    startHazardFlash()
                }
                cancelledFlag = false
                guard.unlock()
                currentAnimator = null
            }

            override fun onAnimationCancel(animation: Animator) {
                cancelledFlag = true
                listener?.onAnimationCancelled(VehicleState.PoweringOff)
                guard.unlock()
                currentAnimator = null
            }
        })

        currentAnimator = set
        set.start()
        return true
    }

    /**
     * 双闪效果：左右尾灯交替闪烁（0.5s 周期），持续约 4 秒后自动停止。
     * 使用 ValueAnimator 循环，避免与主动画冲突。
     */
    private var hazardAnimator: ValueAnimator? = null
    private var hazardCount = 0

    private fun startHazardFlash() {
        hazardAnimator?.cancel()
        hazardCount = 0
        val flash = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                // 尾灯在 0.15~0.9 之间明暗闪烁，制造警示感
                val alpha = 0.15f + 0.75f * v
                stageView.setLightAlpha(VehicleLightPoint.Type.TAILLAMP, alpha)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationRepeat(animation: Animator) {
                    hazardCount++
                    if (hazardCount >= 8) { // 约 4 秒（8 个 250ms 半周期 = 8*250ms*2 ≈ 4s）
                        (animation as ValueAnimator).cancel()
                        stageView.setLightAlpha(VehicleLightPoint.Type.TAILLAMP, 0f)
                    }
                }
            })
        }
        hazardAnimator = flash
        flash.start()
    }

    /**
     * 播放坐桶打开动画
     *
     * 时序：
     *   0ms    : 坐桶图层淡入 + 坐垫以尾部铰链旋转掀起（带回弹）
     *   300ms  : 橙色轮廓光渐亮
     *   800ms  : 橙色能量脉冲扩散一次
     *   1200ms : 稳定为坐桶打开状态
     */
    fun playBucketOpenAnimation(): Boolean {
        if (!guard.lock()) return false
        if (!stateMachine.transition(VehicleState.BucketOpening)) {
            guard.unlock()
            return false
        }

        listener?.onAnimationStarted(VehicleState.BucketOpening)
        cancelCurrent()

        val ds = durationScale
        val set = AnimatorSet()

        // 坐桶图层从收起态弹开：先快速弹起，再微回弹（Overshoot 模拟阻尼）
        val bucketFadeIn = ObjectAnimator.ofFloat(stageView, "bucketVisible", 0f, 1f).apply {
            duration = (500 * ds).toLong()
            interpolator = DecelerateInterpolator()
        }
        val bucketLift = ObjectAnimator.ofFloat(stageView, "bucketLiftDp", 0f, -12f).apply {
            duration = (650 * ds).toLong()
            interpolator = OvershootInterpolator(0.6f)
        }
        // 旋转掀开：以尾部为铰链，从 0° 旋转到 -10° 再回弹到 -6°（Overshoot 自带回弹）
        val bucketRotate = ObjectAnimator.ofFloat(stageView, "bucketRotationDeg", 0f, -10f).apply {
            duration = (700 * ds).toLong()
            interpolator = OvershootInterpolator(0.7f)
        }

        // 坐桶区域亮起橙色轮廓光
        val bucketGlow = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (500 * ds).toLong()
            startDelay = (250 * ds).toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                stageView.setLightAlpha(VehicleLightPoint.Type.BUCKET_AREA, it.animatedValue as Float)
            }
        }

        // 能量脉冲（ring 快速扩散一次）
        val pulseScale = ObjectAnimator.ofFloat(stageView, "ringScale", 0.5f, 1.3f).apply {
            duration = (400 * ds).toLong()
            startDelay = (800 * ds).toLong()
            interpolator = DecelerateInterpolator()
        }
        val pulseAlpha = ObjectAnimator.ofFloat(stageView, "ringAlpha", 0.3f, 0.6f, 0f).apply {
            duration = (600 * ds).toLong()
            startDelay = (800 * ds).toLong()
        }

        set.play(bucketFadeIn)
        set.play(bucketLift)
        set.play(bucketRotate)
        set.play(bucketGlow)
        set.play(pulseScale).with(pulseAlpha)

        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!cancelledFlag) {
                    stateMachine.transition(VehicleState.BucketOpened)
                    listener?.onAnimationCompleted(VehicleState.BucketOpened)
                }
                cancelledFlag = false
                guard.unlock()
                currentAnimator = null
            }

            override fun onAnimationCancel(animation: Animator) {
                cancelledFlag = true
                listener?.onAnimationCancelled(VehicleState.BucketOpening)
                guard.unlock()
                currentAnimator = null
            }
        })

        currentAnimator = set
        set.start()
        return true
    }

    /**
     * 播放坐桶关闭动画（回到已开机态）
     */
    fun playBucketCloseAnimation(): Boolean {
        if (!guard.lock()) return false
        if (!stateMachine.transition(VehicleState.BucketClosing)) {
            guard.unlock()
            return false
        }

        listener?.onAnimationStarted(VehicleState.BucketClosing)
        cancelCurrent()
        val ds = durationScale
        val set = AnimatorSet()

        val bucketOff = ObjectAnimator.ofFloat(stageView, "bucketVisible", 1f, 0f).apply {
            duration = (400 * ds).toLong()
        }
        val seatDown = ObjectAnimator.ofFloat(stageView, "bucketLiftDp", -12f, 0f).apply {
            duration = (400 * ds).toLong()
            interpolator = DecelerateInterpolator()
        }
        val seatRotateBack = ObjectAnimator.ofFloat(stageView, "bucketRotationDeg", -6f, 0f).apply {
            duration = (400 * ds).toLong()
            interpolator = DecelerateInterpolator()
        }
        val bucketGlowOff = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = (400 * ds).toLong()
            addUpdateListener {
                stageView.setLightAlpha(VehicleLightPoint.Type.BUCKET_AREA, it.animatedValue as Float)
            }
        }

        set.play(bucketOff).with(seatDown).with(seatRotateBack).with(bucketGlowOff)
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!cancelledFlag) {
                    stateMachine.transition(VehicleState.PoweredOn)
                    listener?.onAnimationCompleted(VehicleState.PoweredOn)
                }
                cancelledFlag = false
                guard.unlock()
                currentAnimator = null
            }

            override fun onAnimationCancel(animation: Animator) {
                cancelledFlag = true
                listener?.onAnimationCancelled(VehicleState.BucketClosing)
                guard.unlock()
                currentAnimator = null
            }
        })

        currentAnimator = set
        set.start()
        return true
    }

    /**
     * 指令失败回滚：立即取消当前动画，并将视觉状态快速恢复到最后稳定态。
     *
     * 典型调用时机：蓝牙指令返回失败 / 超时后，由 ViewModel 调用。
     */
    fun rollback() {
        hazardAnimator?.cancel()
        cancelCurrent()
        if (!guard.lock()) {
            // 极端并发下 guard 未释放，跳过本次回滚
            return
        }
        // 快速恢复到 Standby 视觉
        val ds = durationScale
        val set = AnimatorSet()
        set.playTogether(
            ObjectAnimator.ofFloat(stageView, "bodySaturation", stageView.bodySaturation, 0.2f).apply {
                duration = (300 * ds).toLong()
            },
            ObjectAnimator.ofFloat(stageView, "bodyBrightness", stageView.bodyBrightness, 0.6f).apply {
                duration = (300 * ds).toLong()
            },
            ObjectAnimator.ofFloat(stageView, "glowAlpha", stageView.glowAlpha, 0.3f).apply {
                duration = (300 * ds).toLong()
            },
            ObjectAnimator.ofFloat(stageView, "ringAlpha", stageView.ringAlpha, 0f).apply {
                duration = (300 * ds).toLong()
            },
            ObjectAnimator.ofFloat(stageView, "ringScale", stageView.ringScale, 0f).apply {
                duration = (300 * ds).toLong()
            },
            ObjectAnimator.ofFloat(stageView, "floatOffsetDp", stageView.floatOffsetDp, 0f).apply {
                duration = (300 * ds).toLong()
            },
            ObjectAnimator.ofFloat(stageView, "beamAlpha", stageView.beamAlpha, 0f).apply {
                duration = (300 * ds).toLong()
            },
            ObjectAnimator.ofFloat(stageView, "bucketVisible", stageView.bucketVisible, 0f).apply {
                duration = (300 * ds).toLong()
            },
            ObjectAnimator.ofFloat(stageView, "bucketLiftDp", stageView.bucketLiftDp, 0f).apply {
                duration = (300 * ds).toLong()
            },
            ObjectAnimator.ofFloat(stageView, "bucketRotationDeg", stageView.bucketRotationDeg, 0f).apply {
                duration = (300 * ds).toLong()
            }
        )
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                stageView.setAllLightsAlpha(0f)
                stateMachine.rollback()
                guard.unlock()
                currentAnimator = null
            }
        })
        currentAnimator = set
        set.start()
    }

    /** 取消当前动画（页面销毁、状态冲突时调用） */
    fun cancelCurrent() {
        hazardAnimator?.cancel()
        currentAnimator?.cancel()
        currentAnimator = null
    }

    /** 释放资源（在 Fragment onDestroyView 中调用） */
    fun release() {
        hazardAnimator?.cancel()
        cancelCurrent()
        stageView.release()
    }
}
