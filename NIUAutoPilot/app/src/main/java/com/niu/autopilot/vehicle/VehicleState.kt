package com.niu.autopilot.vehicle

import com.niu.autopilot.R

/**
 * 车辆可视化状态机密封类
 *
 * 状态流转规则：
 *   Offline → Standby → PoweringOn → PoweredOn → PoweringOff → Standby
 *                                   ↓           ↓
 *                              BucketOpening → BucketOpened
 *
 * 任意状态均可进入 Error；Error 可经 reset() 回到 Standby
 */
sealed class VehicleState {
    /** 未连接（蓝牙未配对 / 无信号） */
    object Offline : VehicleState()

    /** 已连接，车辆已关机（待机） */
    object Standby : VehicleState()

    /** 开机指令已下发，动画播放中 */
    object PoweringOn : VehicleState()

    /** 车辆已开机 */
    object PoweredOn : VehicleState()

    /** 关机指令已下发，动画播放中 */
    object PoweringOff : VehicleState()

    /** 坐桶指令已下发，动画播放中 */
    object BucketOpening : VehicleState()

    /** 坐桶关闭指令已下发，动画播放中 */
    object BucketClosing : VehicleState()

    /** 坐桶已打开 */
    object BucketOpened : VehicleState()

    /** 指令失败或异常，需回滚或重置 */
    object Error : VehicleState()

    /** 状态描述（用于 UI 状态文字） */
    val displayName: String
        get() = when (this) {
            is Offline -> "未连接"
            is Standby -> "已关机"
            is PoweringOn -> "开机中…"
            is PoweredOn -> "已开机"
            is PoweringOff -> "关机中…"
            is BucketOpening -> "开启中…"
            is BucketClosing -> "关闭中…"
            is BucketOpened -> "坐桶已开"
            is Error -> "异常"
        }

    /** 状态主题色（用于状态文字/边框着色） */
    val themeColorRes: Int
        get() = when (this) {
            is Offline -> R.color.vehicle_text_secondary
            is Standby -> R.color.vehicle_alert_red
            is PoweringOn -> R.color.vehicle_energy_blue
            is PoweredOn -> R.color.vehicle_energy_blue
            is PoweringOff -> R.color.vehicle_alert_red
            is BucketOpening -> R.color.vehicle_energy_orange
            is BucketClosing -> R.color.vehicle_energy_orange
            is BucketOpened -> R.color.vehicle_energy_orange
            is Error -> R.color.vehicle_alert_red
        }

    /** 是否为过渡态（动画播放中，按钮应禁用） */
    val isTransient: Boolean
        get() = this is PoweringOn || this is PoweringOff || this is BucketOpening || this is BucketClosing

    /** 是否已连接（非 Offline 且非 Error） */
    val isConnected: Boolean
        get() = this !is Offline && this !is Error

    companion object {
        /** 从字符串反序列化（用于 Bundle 恢复等场景） */
        fun fromName(name: String): VehicleState = when (name) {
            "Offline" -> Offline
            "Standby" -> Standby
            "PoweringOn" -> PoweringOn
            "PoweredOn" -> PoweredOn
            "PoweringOff" -> PoweringOff
            "BucketOpening" -> BucketOpening
            "BucketClosing" -> BucketClosing
            "BucketOpened" -> BucketOpened
            "Error" -> Error
            else -> Offline
        }
    }
}
