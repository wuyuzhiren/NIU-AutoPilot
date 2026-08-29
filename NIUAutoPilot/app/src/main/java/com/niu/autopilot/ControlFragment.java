package com.niu.autopilot;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * 控制页：车辆状态 + 快捷控制(2x2) + 自动开关机
 */
public class ControlFragment extends Fragment {
    private MainActivity act;
    private TextView tvPowerState, tvStateDetail, tvStatusBadge, tvAutoRunning, tvLogPreview;
    private TextView tvPowerOnLabel, tvPowerOffLabel;
    private ImageView ivPowerIcon, ivPowerOnIcon, ivPowerOffIcon;
    private Switch swAutoOn, swAutoOff;
    private TextView btnAuto;
    private View btnPowerOn, btnPowerOff, btnSeat, btnCheck;
    private boolean refreshing = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_control, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        act = (MainActivity) getActivity();
        tvPowerState = view.findViewById(R.id.tv_power_state);
        tvStateDetail = view.findViewById(R.id.tv_state_detail);
        tvStatusBadge = view.findViewById(R.id.tv_status_badge);
        tvAutoRunning = view.findViewById(R.id.tv_auto_running);
        tvLogPreview = view.findViewById(R.id.tv_log_preview);
        ivPowerIcon = view.findViewById(R.id.iv_power_icon);
        ivPowerOnIcon = view.findViewById(R.id.iv_power_on_icon);
        ivPowerOffIcon = view.findViewById(R.id.iv_power_off_icon);
        tvPowerOnLabel = view.findViewById(R.id.tv_power_on_label);
        tvPowerOffLabel = view.findViewById(R.id.tv_power_off_label);
        swAutoOn = view.findViewById(R.id.sw_auto_on);
        swAutoOff = view.findViewById(R.id.sw_auto_off);
        btnAuto = view.findViewById(R.id.btn_auto);
        btnPowerOn = view.findViewById(R.id.btn_power_on);
        btnPowerOff = view.findViewById(R.id.btn_power_off);
        btnSeat = view.findViewById(R.id.btn_seat);
        btnCheck = view.findViewById(R.id.btn_check_state);

        swAutoOn.setOnCheckedChangeListener((b, c) -> {
            if (!refreshing) { act.settings.setAutoOn(c); if (c) swAutoOff.setChecked(false); }
        });
        swAutoOff.setOnCheckedChangeListener((b, c) -> {
            if (!refreshing) { act.settings.setAutoOff(c); if (c) swAutoOn.setChecked(false); }
        });

        btnPowerOn.setOnClickListener(v ->
                act.sendCommand(Settings.CMD_POWER_ON, "开机", () -> flashCheck(R.id.tv_power_on_label, "开机")));
        btnPowerOff.setOnClickListener(v ->
                act.sendCommand(Settings.CMD_POWER_OFF, "关机", () -> flashCheck(R.id.tv_power_off_label, "关机")));
        btnSeat.setOnClickListener(v ->
                act.sendCommand(Settings.CMD_SEAT, "开坐桶", () -> flashCheck(R.id.tv_seat_label, "开坐桶")));
        btnCheck.setOnClickListener(v -> act.queryState());
        btnAuto.setOnClickListener(v -> act.toggleAutoMode());

        refreshUi();
    }

    @Override
    public void onResume() {
        super.onResume();
        autoCheckStateIfReady();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) autoCheckStateIfReady();
    }

    /** 控制页可见时自动查一次状态（账号/SN已填才查） */
    private void autoCheckStateIfReady() {
        if (act == null) return;
        boolean ready = !act.settings.getAccount().isEmpty()
                && !act.settings.getPassword().isEmpty()
                && !act.settings.getSn().isEmpty();
        if (ready) act.queryState();
    }

    private void flashCheck(int labelId, String original) {
        TextView tv = getView() != null ? getView().findViewById(labelId) : null;
        if (tv == null) return;
        tv.setText("✓ 成功");
        new Handler(Looper.getMainLooper()).postDelayed(() -> tv.setText(original), 1500);
    }

    public void onLogUpdated() {
        if (tvLogPreview != null && act.logText.length() > 0) {
            String log = act.logText.toString();
            int lastNl = log.lastIndexOf('\n');
            String last = lastNl >= 0 ? log.substring(lastNl + 1) : log;
            if (!last.isEmpty()) tvLogPreview.setText(last);
        }
    }

    public void refreshUi() {
        if (act == null || getView() == null) return;
        refreshing = true;

        // 账号/SN是否齐全
        boolean ready = !act.settings.getAccount().isEmpty()
                && !act.settings.getPassword().isEmpty()
                && !act.settings.getSn().isEmpty();

        // 按钮置灰
        setButtonEnabled(btnPowerOn, ready);
        setButtonEnabled(btnPowerOff, ready);
        setButtonEnabled(btnSeat, ready);
        setButtonEnabled(btnCheck, ready);

        // 车辆状态
        boolean isOn = "已开机".equals(act.vehicleState);
        tvPowerState.setText(act.vehicleState);
        int stateColor = isOn ? 0xFF43A047 : 0xFF9E9E9E;
        tvPowerState.setTextColor(stateColor);
        ivPowerIcon.setColorFilter(stateColor);

        // 快捷控制按钮高亮：当前状态对应的按钮亮蓝色
        setPowerButtonActive(btnPowerOn, tvPowerOnLabel, ivPowerOnIcon, isOn);
        setPowerButtonActive(btnPowerOff, tvPowerOffLabel, ivPowerOffIcon, !isOn && !"未知".equals(act.vehicleState));

        // 状态徽章
        String devName = act.settings.getDeviceName();
        boolean connected = act.autoModeRunning && act.rssiValid;
        if (devName == null || devName.isEmpty()) {
            tvStatusBadge.setText("未绑定");
            tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_disconnected);
        } else if (connected) {
            tvStatusBadge.setText("已连接");
            tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_connected);
        } else {
            tvStatusBadge.setText("未连接");
            tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_disconnected);
        }

        // 详情：圆点分隔
        String ble = act.rssiValid ? ("蓝牙信号: " + act.currentRssi + " dBm (" + MainActivity.rssiToMeter(act.currentRssi) + ")") : "蓝牙信号: --";
        String auto = act.autoModeRunning ? "自动模式: 运行中" : "自动模式: 未启动";
        tvStateDetail.setText(ble + "  ●  " + auto);

        // 自动开关
        swAutoOn.setChecked(act.settings.isAutoOn());
        swAutoOff.setChecked(act.settings.isAutoOff());

        // 自动模式按钮
        if (act.autoModeRunning) {
            tvAutoRunning.setText("● 自动模式运行中");
            tvAutoRunning.setVisibility(View.VISIBLE);
            btnAuto.setText("停止自动模式");
            btnAuto.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFE53935));
        } else {
            tvAutoRunning.setVisibility(View.GONE);
            btnAuto.setText("启动自动模式");
            btnAuto.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1565C0));
        }

        // 日志预览
        onLogUpdated();

        refreshing = false;
    }

    private void setButtonEnabled(View btn, boolean enabled) {
        btn.setEnabled(enabled);
        btn.setAlpha(enabled ? 1.0f : 0.4f);
    }

    /** 设置快捷控制按钮的高亮状态：active=蓝色填充白字，inactive=灰色描边灰字 */
    private void setPowerButtonActive(View btn, TextView label, ImageView icon, boolean active) {
        if (active) {
            btn.setBackgroundResource(R.drawable.bg_btn_primary);
            label.setTextColor(0xFFFFFFFF);
            icon.setColorFilter(0xFFFFFFFF);
        } else {
            btn.setBackgroundResource(R.drawable.bg_btn_secondary);
            label.setTextColor(0xFF424242);
            icon.setColorFilter(0xFF424242);
        }
    }
}
