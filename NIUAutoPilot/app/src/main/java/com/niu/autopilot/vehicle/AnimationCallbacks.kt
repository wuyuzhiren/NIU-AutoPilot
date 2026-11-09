package com.niu.autopilot.vehicle

/**
 * 动画事件回调接口：将「指令下发」与「动画播放」彻底解耦。
 *
 * 设计原则：
 * 1. 动画不阻塞蓝牙/网络指令下发——点击按钮后指令与动画并行执行
 * 2. 指令失败时，通过 [onCommandFailed] 触发动画回滚
 * 3. 动画完成时，通过 [onAnimationCompleted] 通知业务层更新 UI
 *
 * 接入示例见 README「接入示例」章节。
 */
interface VehicleAnimationListener {

    /** 动画开始播放 */
    fun onAnimationStarted(state: VehicleState) {}

    /** 动画正常播放完毕（未取消） */
    fun onAnimationCompleted(state: VehicleState) {}

    /** 动画被取消（页面销毁、用户打断、状态冲突等） */
    fun onAnimationCancelled(state: VehicleState) {}
}

/**
 * 指令结果回调，用于与 ViewModel / BLE 层桥接。
 *
 * 典型时序：
 *   1. 用户点击「开机」
 *   2. VM 立即调用 ble.sendPowerOn(this)
 *   3. VM 同时调用 controller.playPowerOnAnimation()
 *   4. 若蓝牙 3s 内返回失败 → onCommandFailed() → controller.rollback()
 *   5. 若蓝牙返回成功 → 动画继续到完成 → onAnimationCompleted(PoweredOn)
 */
interface CommandResultCallback {

    /** 指令下发成功（设备已执行） */
    fun onCommandSuccess(command: VehicleCommand)

    /** 指令下发失败（超时 / 拒绝 / 断开） */
    fun onCommandFailed(command: VehicleCommand, errorCode: Int, errorMsg: String)
}

/** 可下发的车辆指令枚举 */
enum class VehicleCommand {
    POWER_ON,
    POWER_OFF,
    OPEN_BUCKET,
    CLOSE_BUCKET,
    QUERY_STATUS
}

/**
 * 默认空实现，方便业务方按需覆写。
 */
open class SimpleVehicleAnimationListener : VehicleAnimationListener
