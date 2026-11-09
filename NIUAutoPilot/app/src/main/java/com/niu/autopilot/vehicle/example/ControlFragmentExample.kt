package com.niu.autopilot.vehicle.example

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import com.niu.autopilot.R
import com.niu.autopilot.vehicle.*

/**
 * 接入示例：展示如何在现有 ControlFragment 中 3~5 行接入车辆动画组件
 *
 * 前提：已将本代码包拷贝到工程中（见 README「拷贝步骤」）
 * 假设：原 Fragment 已存在四个按钮 btnPowerOn / btnPowerOff / btnBucket / btnStatus
 */
class ControlFragmentExample : Fragment() {

    // ====== 1. 声明组件（核心就这 3 行）======
    private lateinit var vehicleStage: VehicleStageView
    private lateinit var stateMachine: VehicleStateMachine
    private lateinit var animationController: VehicleAnimationController

    private lateinit var tvStatus: TextView
    private lateinit var btnPowerOn: ImageButton
    private lateinit var btnPowerOff: ImageButton
    private lateinit var btnBucket: ImageButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // 假设你使用本包提供的 fragment_vehicle_stage.xml 作为布局
        return inflater.inflate(R.layout.fragment_vehicle_stage, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 绑定视图
        vehicleStage = view.findViewById(R.id.vehicle_stage)
        tvStatus = view.findViewById(R.id.tv_vehicle_status)
        btnPowerOn = view.findViewById(R.id.btn_power_on)
        btnPowerOff = view.findViewById(R.id.btn_power_off)
        btnBucket = view.findViewById(R.id.btn_bucket)

        // ====== 2. 初始化（第 4~5 行）======
        stateMachine = VehicleStateMachine()
        animationController = VehicleAnimationController(vehicleStage, stateMachine, animationListener)

        // 设置车身图（用户需将 nx_body.png 放入 drawable）
        vehicleStage.setBodyImageResource(R.drawable.nx_body)

        // 观察状态变化更新 UI 文字/颜色
        stateMachine.state.observe(viewLifecycleOwner, stateObserver)

        // 按钮点击：指令与动画解耦并行执行
        btnPowerOn.setOnClickListener { onPowerOnClicked() }
        btnPowerOff.setOnClickListener { onPowerOffClicked() }
        btnBucket.setOnClickListener { onBucketClicked() }
    }

    // ====== 3. 点击处理：指令与动画并行，失败回滚 ======

    private fun onPowerOnClicked() {
        // 先校验状态合法性
        if (!stateMachine.canTransition(VehicleState.PoweringOn)) return

        // (a) 立即下发蓝牙指令（异步，不阻塞）
        // bleManager.sendCommand(POWER_ON) { result ->
        //     if (!result.success) {
        //         requireActivity().runOnUiThread {
        //             animationController.rollback()
        //             showToast("开机失败: ${result.error}")
        //         }
        //     }
        // }

        // (b) 立即播放动画（给用户即时反馈）
        animationController.playPowerOnAnimation()
    }

    private fun onPowerOffClicked() {
        if (!stateMachine.canTransition(VehicleState.PoweringOff)) return
        // bleManager.sendCommand(POWER_OFF) { ... }
        animationController.playPowerOffAnimation()
    }

    private fun onBucketClicked() {
        when (stateMachine.current) {
            is VehicleState.PoweredOn -> {
                // bleManager.sendCommand(OPEN_BUCKET) { ... }
                animationController.playBucketOpenAnimation()
            }
            is VehicleState.BucketOpened -> {
                // bleManager.sendCommand(CLOSE_BUCKET) { ... }
                animationController.playBucketCloseAnimation()
            }
            else -> { /* 忽略 */ }
        }
    }

    // ====== 4. 状态监听：更新文字与颜色 ======

    private val stateObserver = Observer<VehicleState> { state ->
        tvStatus.text = state.displayName
        val color = ContextCompat.getColor(requireContext(), state.themeColorRes)
        tvStatus.setTextColor(color)

        // 按钮可用性随状态联动
        btnPowerOn.isEnabled = state is VehicleState.Standby
        btnPowerOff.isEnabled = state is VehicleState.PoweredOn || state is VehicleState.BucketOpened
        btnBucket.isEnabled = state is VehicleState.PoweredOn || state is VehicleState.BucketOpened
    }

    private val animationListener = object : SimpleVehicleAnimationListener() {
        override fun onAnimationCompleted(state: VehicleState) {
            // 动画完成后的额外业务逻辑（如震动反馈、音效）
        }
    }

    // ====== 5. 生命周期清理（防泄漏） ======

    override fun onDestroyView() {
        super.onDestroyView()
        animationController.release()
    }
}
