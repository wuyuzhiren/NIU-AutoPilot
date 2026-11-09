package com.niu.autopilot.vehicle

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer

/**
 * 车辆状态机管理器
 *
 * 职责：
 * 1. 维护当前状态并对外暴露 LiveData（方便与 ViewModel/Fragment 绑定）
 * 2. 校验状态转换合法性，拒绝非法跳转
 * 3. 记录上一次成功状态，用于 Error 回滚
 * 4. 提供 reset() 从 Error 恢复
 *
 * 线程安全：所有状态读写应在主线程执行（LiveData 约束）。
 */
class VehicleStateMachine {

    private val _state = MutableLiveData<VehicleState>(VehicleState.Offline)
    val state: LiveData<VehicleState> = _state

    /** 上一次非过渡、非 Error 的稳定状态，用于失败回滚 */
    private var lastStableState: VehicleState = VehicleState.Offline

    /** 内部 observer 引用，用于 dispose() 时清理 */
    private val stateObserver = Observer<VehicleState> { newState ->
        if (!newState.isTransient && newState !is VehicleState.Error) {
            lastStableState = newState
        }
    }

    init {
        _state.observeForever(stateObserver)
    }

    /**
     * 释放资源，移除 LiveData observer。
     * 在 StateMachine 生命周期结束（如 Fragment onDestroy）时调用，防止 observer 泄漏。
     */
    fun dispose() {
        _state.removeObserver(stateObserver)
    }

    /** 查询当前状态 */
    val current: VehicleState
        get() = _state.value ?: VehicleState.Offline

    /**
     * 校验目标状态是否可从当前状态合法转移。
     * 注：Error 状态只能通过 reset() 离开。
     */
    fun canTransition(to: VehicleState): Boolean = when (current) {
        is VehicleState.Offline -> to is VehicleState.Standby
        is VehicleState.Standby -> to is VehicleState.PoweringOn
        is VehicleState.PoweringOn ->
            to is VehicleState.PoweredOn || to is VehicleState.Standby || to is VehicleState.Error
        is VehicleState.PoweredOn ->
            to is VehicleState.PoweringOff || to is VehicleState.BucketOpening || to is VehicleState.Error
        is VehicleState.PoweringOff ->
            to is VehicleState.Standby || to is VehicleState.PoweredOn || to is VehicleState.Error
        is VehicleState.BucketOpening ->
            to is VehicleState.BucketOpened || to is VehicleState.PoweredOn || to is VehicleState.Error
        is VehicleState.BucketOpened ->
            to is VehicleState.BucketClosing || to is VehicleState.PoweredOn || to is VehicleState.Error
        is VehicleState.BucketClosing ->
            to is VehicleState.PoweredOn || to is VehicleState.BucketOpened || to is VehicleState.Error
        is VehicleState.Error -> false // Error 只能通过 reset() 离开
    }

    /**
     * 执行状态转换。若非法则静默忽略（返回 false）。
     * 调用方应在转换前用 canTransition() 判断并给出用户反馈。
     */
    fun transition(to: VehicleState): Boolean {
        if (!canTransition(to)) return false
        _state.value = to
        return true
    }

    /**
     * 指令失败回滚：从过渡态或 Error 恢复到上一次稳定状态。
     * 典型调用时机：蓝牙指令返回失败 / 超时。
     */
    fun rollback() {
        when (current) {
            is VehicleState.PoweringOn,
            is VehicleState.PoweringOff,
            is VehicleState.BucketOpening,
            is VehicleState.BucketClosing,
            is VehicleState.Error -> {
                _state.value = lastStableState
            }
            else -> { /* 非过渡态无需回滚 */ }
        }
    }

    /** 从 Error 重置到 Standby，或从任意状态强制重置 */
    fun reset(to: VehicleState = VehicleState.Standby) {
        _state.value = to
        if (!to.isTransient && to !is VehicleState.Error) {
            lastStableState = to
        }
    }

    /**
     * 进入 Error 状态并记录原因（可选）。
     * Error 状态会阻断一切交互，需用户确认后 reset()。
     */
    fun setError(reason: String = "") {
        _state.value = VehicleState.Error
        // 可在此扩展：将 reason 写入日志或暴露给 UI
    }
}
