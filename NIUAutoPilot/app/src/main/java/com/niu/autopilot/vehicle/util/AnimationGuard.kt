package com.niu.autopilot.vehicle.util

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 动画防重入守卫
 *
 * 职责：防止用户快速重复点击导致动画堆叠、状态机混乱。
 * 同时提供状态互斥：当某段动画正在播放时，禁用其他可能冲突的操作。
 *
 * 使用方式：
 *   guard.withLock { playPowerOnAnimation() }
 *   或手动 lock()/unlock()
 *
 * 注意：lock() 成功返回 true 后，**必须**保证在 finally 中 unlock()，
 *       否则按钮将永久锁定。建议优先使用 withLock {}。
 */
class AnimationGuard {

    private val isAnimating = AtomicBoolean(false)

    /**
     * 尝试加锁。返回 true 表示获得锁，可继续执行动画；
     * 返回 false 表示当前已有动画在执行，应忽略本次点击。
     */
    fun lock(): Boolean = isAnimating.compareAndSet(false, true)

    /** 释放锁。必须在动画结束或异常时调用。 */
    fun unlock() = isAnimating.set(false)

    /** 查询当前是否被锁定 */
    val locked: Boolean get() = isAnimating.get()

    /**
     * 在锁保护下执行代码块。
     * 获得锁则执行并返回代码块结果；未获得锁返回 null。
     * 保证无论代码块是否抛异常都会释放锁。
     */
    inline fun <T> withLock(block: () -> T): T? {
        if (!lock()) return null
        return try {
            block()
        } finally {
            unlock()
        }
    }

    /**
     * 与 VehicleState 联动的扩展锁：
     * 若当前已有动画在播放，或目标状态与当前状态冲突，则拒绝执行。
     *
     * @param currentState 当前状态
     * @param targetTransient 目标过渡态（如 PoweringOn）
     * @param block 受保护代码块
     */
    inline fun <T> withStateLock(
        currentState: com.niu.autopilot.vehicle.VehicleState,
        targetTransient: com.niu.autopilot.vehicle.VehicleState,
        block: () -> T
    ): T? {
        if (currentState.isTransient) return null
        if (!targetTransient.isTransient) return null
        if (!lock()) return null
        return try {
            block()
        } finally {
            unlock()
        }
    }
}
