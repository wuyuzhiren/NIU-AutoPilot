package com.niu.autopilot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.niu.autopilot.calibration.CalibrationActivity;
import com.niu.autopilot.ui.RssiScaleView;
import com.niu.autopilot.ui.ScanPulseView;
import com.niu.autopilot.vehicle.VehicleAnimationController;
import com.niu.autopilot.vehicle.VehicleLightPoint;
import com.niu.autopilot.vehicle.VehicleStageView;
import com.niu.autopilot.vehicle.VehicleState;
import com.niu.autopilot.vehicle.VehicleStateMachine;

/**
 * v2.39 控制页：圆形渐变按钮 + 车辆动画增强 + 状态反馈优化
 */
public class ControlFragment extends Fragment {
    private MainActivity act;
    private TextView tvPowerState, tvLastCmd, tvStatusBadge, tvAutoRunning, tvLogPreview;
    private TextView tvPowerOnLabel, tvPowerOffLabel, tvSeatLabel, tvCheckLabel;
    private TextView tvSignalBars, tvRssiVal, tvSignalDist, tvBattery, tvAutoState, tvLastCalib;
    private ImageView ivPowerOnIcon, ivPowerOffIcon;
    private Switch swAutoOn, swAutoOff;
    private TextView btnAuto;
    private View btnPowerOn, btnPowerOff, btnSeat, btnCheck, btnDiagnostic, btnCalibrateQuick;
    private TextView tvDiagnosticLabel;
    private Handler diagHandler = new Handler();
    private RssiScaleView rssiScale;
    private android.widget.ProgressBar pbDistance;
    private ScanPulseView scanPulse;
    private VehicleStageView vehicleStage;
    private VehicleStateMachine vehicleStateMachine;
    private VehicleAnimationController vehicleAnimation;
    private android.widget.ProgressBar pbBattery;
    private boolean refreshing = false;
    private boolean commandSending = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_control, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        act = (MainActivity) getActivity();
        tvPowerState = view.findViewById(R.id.tv_power_state);
        tvLastCmd = view.findViewById(R.id.tv_last_cmd);
        tvStatusBadge = view.findViewById(R.id.tv_status_badge);
        tvAutoRunning = view.findViewById(R.id.tv_auto_running);
        tvLogPreview = view.findViewById(R.id.tv_log_preview);
        ivPowerOnIcon = view.findViewById(R.id.iv_power_on_icon);
        ivPowerOffIcon = view.findViewById(R.id.iv_power_off_icon);
        tvPowerOnLabel = view.findViewById(R.id.tv_power_on_label);
        tvPowerOffLabel = view.findViewById(R.id.tv_power_off_label);
        tvSeatLabel = view.findViewById(R.id.tv_seat_label);
        tvCheckLabel = view.findViewById(R.id.tv_check_label);
        swAutoOn = view.findViewById(R.id.sw_auto_on);
        swAutoOff = view.findViewById(R.id.sw_auto_off);
        btnAuto = view.findViewById(R.id.btn_auto);
        btnPowerOn = view.findViewById(R.id.btn_power_on);
        btnPowerOff = view.findViewById(R.id.btn_power_off);
        btnSeat = view.findViewById(R.id.btn_seat);
        btnCheck = view.findViewById(R.id.btn_check_state);
        btnDiagnostic = view.findViewById(R.id.btn_diagnostic);
        btnCalibrateQuick = view.findViewById(R.id.btn_calibrate_quick);
        tvDiagnosticLabel = view.findViewById(R.id.tv_diagnostic_label);
        tvSignalBars = view.findViewById(R.id.tv_signal_bars);
        tvRssiVal = view.findViewById(R.id.tv_rssi_val);
        tvSignalDist = view.findViewById(R.id.tv_signal_dist);
        tvBattery = view.findViewById(R.id.tv_battery);
        tvLastCalib = view.findViewById(R.id.tv_last_calib);
        tvAutoState = view.findViewById(R.id.tv_auto_state);
        rssiScale = view.findViewById(R.id.rssi_scale);
        pbDistance = view.findViewById(R.id.pb_distance);
        scanPulse = view.findViewById(R.id.scan_pulse);
        vehicleStage = view.findViewById(R.id.vehicle_anim);
        pbBattery = view.findViewById(R.id.pb_battery);

        // v1.1 车辆动画组件：状态机 + 动画编排器（接入指南 3-5 行）
        vehicleStateMachine = new VehicleStateMachine();
        vehicleAnimation = new VehicleAnimationController(vehicleStage, vehicleStateMachine, null);
        vehicleStage.setBodyImageResource(R.drawable.nx_body);
        // v2.75 坐桶图层：与车身同坐标系叠加，开坐桶时弹开
        vehicleStage.setBucketImageResource(R.drawable.nx_bucket);
        // 按现有素材微调灯光点位（紫色小牛NX 45°斜前视角）
        vehicleStage.setLightPoints(java.util.Arrays.asList(
                new VehicleLightPoint(VehicleLightPoint.Type.HEADLAMP_LEFT, 0.18f, 0.42f, 5f, 20f, 0xFFFFFFFF, 0.95f),
                new VehicleLightPoint(VehicleLightPoint.Type.TAILLAMP, 0.86f, 0.52f, 4f, 14f, 0xFFEF4444, 0.85f),
                new VehicleLightPoint(VehicleLightPoint.Type.DASHBOARD_RING, 0.35f, 0.28f, 8f, 18f, 0xFF00D4FF, 0.9f),
                new VehicleLightPoint(VehicleLightPoint.Type.BUCKET_AREA, 0.50f, 0.35f, 6f, 22f, 0xFFFF6B35, 0.8f)));
        // v2.2 主题感知：按当前主题模式（0跟随系统/1浅色/2深色）设置动画强调色
        applyThemeToStage();

        swAutoOn.setOnCheckedChangeListener((b, c) -> {
            if (!refreshing) { act.settings.setAutoOn(c); if (c) swAutoOff.setChecked(false); }
        });
        swAutoOff.setOnCheckedChangeListener((b, c) -> {
            if (!refreshing) { act.settings.setAutoOff(c); if (c) swAutoOn.setChecked(false); }
        });

        btnPowerOn.setOnClickListener(v -> runCommand(Settings.CMD_POWER_ON, "开机", R.id.tv_power_on_label, "开机", true));
        btnPowerOff.setOnClickListener(v -> runCommand(Settings.CMD_POWER_OFF, "关机", R.id.tv_power_off_label, "关机", false));
        btnSeat.setOnClickListener(v -> runCommand(Settings.CMD_SEAT, "开坐桶", R.id.tv_seat_label, "开坐桶", null));
        btnCheck.setOnClickListener(v -> {
            animateButtonClick(v);
            act.queryState();
        });
        btnDiagnostic.setOnClickListener(v -> startDiagnostic());
        btnCalibrateQuick.setOnClickListener(v -> {
            String mac = act.settings.getDeviceMac();
            if (mac == null || mac.isEmpty()) {
                Toast.makeText(act, "请先扫描并绑定车辆再校准", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent it = new Intent(act, CalibrationActivity.class);
            it.putExtra("mac_address", mac);
            act.startActivity(it);
        });
        btnAuto.setOnClickListener(v -> act.toggleAutoMode());

        refreshUi();
    }

    @Override
    public void onResume() {
        super.onResume();
        autoCheckStateIfReady();
    }

    @Override
    public void onDestroyView() {
        if (vehicleAnimation != null) vehicleAnimation.release();
        if (vehicleStateMachine != null) vehicleStateMachine.dispose();
        super.onDestroyView();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) autoCheckStateIfReady();
    }

    private void autoCheckStateIfReady() {
        if (act == null) return;
        if (ready()) {
            act.queryState();
            autoRefreshBattery();
        }
    }

    /** v2.2 主题感知：按当前主题模式设置车辆舞台强调色（深空HUD=电光蓝，白昼太空舱=电光紫） */
    private void applyThemeToStage() {
        if (vehicleStage == null || act == null) return;
        boolean isLight = MainActivity.isLightThemeActive(act);
        int accent = isLight ? 0xFF7C3AED : 0xFF00D4FF;
        vehicleStage.setThemeColors(accent, isLight);
        // 灯光点位主强调色同步（大灯白色不变，能量环/仪表盘环跟随主题）
        vehicleStage.setLightPoints(java.util.Arrays.asList(
                new VehicleLightPoint(VehicleLightPoint.Type.HEADLAMP_LEFT, 0.18f, 0.42f, 5f, 20f, 0xFFFFFFFF, 0.95f),
                new VehicleLightPoint(VehicleLightPoint.Type.TAILLAMP, 0.86f, 0.52f, 4f, 14f, 0xFFEF4444, 0.85f),
                new VehicleLightPoint(VehicleLightPoint.Type.DASHBOARD_RING, 0.35f, 0.28f, 8f, 18f, accent, 0.9f),
                new VehicleLightPoint(VehicleLightPoint.Type.BUCKET_AREA, 0.50f, 0.35f, 6f, 22f, 0xFFFF6B35, 0.8f)));
    }

    private void autoRefreshBattery() {
        try {
            Settings s = act.settings;
            String token = s.getBatteryToken();
            if (token == null || token.trim().isEmpty()) return;
            long lastSync = s.getBatterySyncedAt();
            if (lastSync > 0 && System.currentTimeMillis() - lastSync < 300000) return;
            new NiuBatteryClient(act).queryBattery((ok, percent, msg) -> {
                if (ok && isResumed()) {
                    act.runOnUiThread(() -> refreshUi());
                }
            });
        } catch (Exception ignored) {}
    }

    private boolean ready() {
        return !act.settings.getAccount().isEmpty()
                && !act.settings.getPassword().isEmpty()
                && !act.settings.getSn().isEmpty();
    }

    private void runCommand(String type, String label, int labelId, String original, Boolean powerState) {
        if (commandSending) return;
        setCommandSending(true);

        // 按钮按下缩放动画
        View btn = null;
        if (powerState != null) btn = powerState ? btnPowerOn : btnPowerOff;
        else btn = btnSeat;
        if (btn != null) animateButtonClick(btn);

        act.sendCommand(type, label,
                () -> {
                    flashCheck(labelId, original);
                    // v1.1 组件：指令成功 → 播放对应状态动画
                    if (powerState != null) {
                        if (powerState) {
                            vehicleAnimation.playPowerOnAnimation();
                        } else {
                            vehicleAnimation.playPowerOffAnimation();
                        }
                    } else {
                        vehicleAnimation.playBucketOpenAnimation();
                        new Handler(Looper.getMainLooper()).postDelayed(() -> vehicleAnimation.playBucketCloseAnimation(), 3000);
                    }
                },
                (ok, msg) -> {
                    setCommandSending(false);
                    if (!ok) vehicleAnimation.rollback();
                    refreshUi();
                });
    }

    /** 按钮点击缩放反馈动画 */
    private void animateButtonClick(View v) {
        ScaleAnimation scale = new ScaleAnimation(1f, 0.9f, 1f, 0.9f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        scale.setDuration(80);
        scale.setRepeatCount(1);
        scale.setRepeatMode(Animation.REVERSE);
        v.startAnimation(scale);
    }

    /** 根据车辆实际状态同步视觉（无过渡动画，用于页面刷新/切页） */
    private void syncVehicleVisual(boolean isOn) {
        if (vehicleStage == null || vehicleStateMachine == null || vehicleAnimation == null) return;
        VehicleState cur = vehicleStateMachine.getCurrent();
        if (isOn) {
            if (cur != VehicleState.PoweredOn.INSTANCE) {
                vehicleStateMachine.reset(VehicleState.PoweredOn.INSTANCE);
                vehicleStage.setAllLightsAlpha(1f);
                vehicleStage.setBodySaturation(1f);
                vehicleStage.setBodyBrightness(1f);
                vehicleStage.setGlowAlpha(1f);
                // v2.75 开机状态：大灯光束点亮，坐桶闭合
                vehicleStage.setBeamAlpha(0.9f);
                vehicleStage.setBucketVisible(0f);
                vehicleStage.setBucketLiftDp(0f);
                vehicleStage.setBucketRotationDeg(0f);
            }
        } else {
            if (cur != VehicleState.Standby.INSTANCE && cur != VehicleState.Offline.INSTANCE) {
                vehicleStateMachine.reset(VehicleState.Standby.INSTANCE);
                vehicleStage.setAllLightsAlpha(0f);
                vehicleStage.setBodySaturation(0.2f);
                vehicleStage.setBodyBrightness(0.6f);
                vehicleStage.setGlowAlpha(0.3f);
                vehicleStage.setRingAlpha(0f);
                vehicleStage.setRingScale(0f);
                vehicleStage.setFloatOffsetDp(0f);
                // v2.75 关机状态：光束熄灭，坐桶闭合
                vehicleStage.setBeamAlpha(0f);
                vehicleStage.setBucketVisible(0f);
                vehicleStage.setBucketLiftDp(0f);
                vehicleStage.setBucketRotationDeg(0f);
            }
        }
    }

    private void setCommandSending(boolean sending) {        commandSending = sending;
        if (sending) {
            setButtonEnabled(btnPowerOn, false);
            setButtonEnabled(btnPowerOff, false);
            setButtonEnabled(btnSeat, false);
            setButtonEnabled(btnCheck, false);
            tvPowerOnLabel.setText("发送中…");
            tvPowerOffLabel.setText("发送中…");
            tvSeatLabel.setText("发送中…");
            tvCheckLabel.setText("发送中…");
        } else {
            tvPowerOnLabel.setText("开机");
            tvPowerOffLabel.setText("关机");
            tvSeatLabel.setText("坐桶");
            tvCheckLabel.setText("查状态");
            refreshUi();
        }
    }

    private void flashCheck(int labelId, String original) {
        TextView tv = getView() != null ? getView().findViewById(labelId) : null;
        if (tv == null) return;
        tv.setText("✓ 成功");
        // 文字闪烁动画
        AlphaAnimation blink = new AlphaAnimation(0.3f, 1.0f);
        blink.setDuration(200);
        blink.setRepeatCount(2);
        tv.startAnimation(blink);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!commandSending) tv.setText(original);
        }, 1500);
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

        boolean ok = ready();

        if (!commandSending) {
            setButtonEnabled(btnPowerOn, ok);
            setButtonEnabled(btnPowerOff, ok);
            setButtonEnabled(btnSeat, ok);
            setButtonEnabled(btnCheck, ok);
        }

        boolean isOn = "已开机".equals(act.vehicleState);
        tvPowerState.setText(act.vehicleState);
        // 状态文字颜色：开机=能量绿，关机=半透明白（深浅主题自适应）
        int stateColor = isOn ? 0xFF10B981 : (MainActivity.isLightThemeActive(act) ? 0x8064748B : 0x80F1F5F9);
        tvPowerState.setTextColor(stateColor);

        // v1.1 组件：刷新时按车辆实际状态同步视觉（不触发过渡动画，避免打断用户操作）
        syncVehicleVisual(isOn);
        // v2.2 主题感知：刷新时同步舞台主题色（切主题重建后也生效）
        applyThemeToStage();

        // 圆形按钮不需要切换背景色，保持渐变色
        // 只需调整图标tint表示激活状态
        setPowerButtonVisual(ivPowerOnIcon, tvPowerOnLabel, isOn);
        setPowerButtonVisual(ivPowerOffIcon, tvPowerOffLabel, !isOn && !"未知".equals(act.vehicleState));

        updateLastCmd();

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

        updateSignalRow();
        updateBattery();

        tvAutoState.setText(act.autoModeRunning ? "自动模式: 运行中" : "自动模式: 未启动");
        tvAutoState.setTextColor(act.autoModeRunning ? 0xFF43A047 : 0xFF9E9E9E);

        updatePulse();

        swAutoOn.setChecked(act.settings.isAutoOn());
        swAutoOff.setChecked(act.settings.isAutoOff());

        if (act.autoModeRunning) {
            tvAutoRunning.setText("● 自动模式运行中");
            tvAutoRunning.setVisibility(View.VISIBLE);
            btnAuto.setText("停止自动模式");
            btnAuto.setBackgroundResource(R.drawable.bg_btn_auto_stop);
        } else {
            tvAutoRunning.setVisibility(View.GONE);
            btnAuto.setText("启动自动模式");
            btnAuto.setBackgroundResource(R.drawable.bg_btn_auto_start);
        }

        onLogUpdated();
        refreshing = false;
    }

    private void updateLastCmd() {
        long t = act.settings.getLastTriggerTime();
        String action = act.settings.getLastTriggerAction();
        if (t <= 0 || action == null || action.isEmpty()) {
            tvLastCmd.setText("上次操作: --");
        } else {
            long min = (System.currentTimeMillis() - t) / 60000;
            String ago = min < 1 ? "刚刚" : (min < 60 ? min + " 分钟前" : (min / 60) + " 小时前");
            tvLastCmd.setText("上次操作: " + ago + " · " + action);
            String calib = act.settings.getLastCalibSummary();
            tvLastCalib.setText(calib.isEmpty() ? "上次校准: --" : ("上次校准: " + calib));
        }
    }

    private void updateSignalRow() {
        int near = act.settings.getRssiThreshold();
        int far = act.settings.getRssiLeaveThreshold();
        rssiScale.setThresholds(near, far);
        if (!act.rssiValid) {
            tvSignalBars.setText("▁▁▁▁");
            tvSignalBars.setTextColor(0xFF9E9E9E);
            tvRssiVal.setText("信号: -- dBm");
            tvSignalDist.setText("距离: --");
            rssiScale.setCurrentRssi(null);
            if (pbDistance != null) pbDistance.setProgress(0);
            return;
        }
        int rssi = act.currentRssi;
        String bars;
        int barColor;
        if (rssi >= near) { bars = "▁▃▅█"; barColor = 0xFF43A047; }
        else if (rssi >= (near + far) / 2) { bars = "▁▃▅▅"; barColor = 0xFF7CB342; }
        else if (rssi >= far) { bars = "▁▃▃▃"; barColor = 0xFFFB8C00; }
        else { bars = "▁▁▁▁"; barColor = 0xFF9E9E9E; }
        tvSignalBars.setText(bars);
        tvSignalBars.setTextColor(barColor);
        tvRssiVal.setText("信号: " + rssi + " dBm");
        tvSignalDist.setText("距离: " + MainActivity.rssiToMeter(rssi));
        rssiScale.setCurrentRssi(rssi);
        // v2.50: 距离可视化进度条（RSSI -100→0%, -40→100%）
        if (pbDistance != null) {
            int p = (rssi + 100) * 100 / 60;
            if (p < 0) p = 0; if (p > 100) p = 100;
            pbDistance.setProgress(p);
        }
    }

    private void startDiagnostic() {
        AppLogger logger = AppLogger.get(getContext());
        if (logger.isDiagnosticActive()) {
            Toast.makeText(getContext(), "诊断记录进行中，剩余 " + logger.getDiagnosticRemaining() + " 秒", Toast.LENGTH_SHORT).show();
            return;
        }
        logger.startDiagnostic(60);
        tvDiagnosticLabel.setText("诊断记录中... 60秒");
        Toast.makeText(getContext(), "开始诊断记录，请走到车旁模拟开机场景", Toast.LENGTH_LONG).show();
        diagHandler.post(new Runnable() {
            @Override public void run() {
                int remain = logger.getDiagnosticRemaining();
                if (remain > 0) {
                    tvDiagnosticLabel.setText("诊断记录中... " + remain + "秒");
                    diagHandler.postDelayed(this, 1000);
                } else {
                    tvDiagnosticLabel.setText("一键诊断（60秒）");
                    Toast.makeText(getContext(), "诊断记录完成，今晚23:00自动上传", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void updateBattery() {
        NiuBatteryClient battery = new NiuBatteryClient(act);
        int p = battery.getPercent();
        int level = battery.level();
        if (p < 0) {
            tvBattery.setText("未同步");
            tvBattery.setTextColor(0xFF9E9E9E);
            if (pbBattery != null) pbBattery.setProgress(0);
        } else {
            tvBattery.setText(p + "%  ·  " + battery.syncedText());
            tvBattery.setTextColor(NiuBatteryClient.colorFor(level));
            if (pbBattery != null) {
                pbBattery.setProgress(p);
                int barColor = NiuBatteryClient.colorFor(level);
                android.graphics.drawable.Drawable d = pbBattery.getProgressDrawable();
                if (d instanceof android.graphics.drawable.LayerDrawable) {
                    android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) d;
                    android.graphics.drawable.Drawable progress = ld.findDrawableByLayerId(android.R.id.progress);
                    if (progress != null) progress.setTint(barColor);
                }
            }
        }
    }

    private void updatePulse() {
        if (act.autoModeRunning) {
            scanPulse.setVisibility(View.VISIBLE);
            scanPulse.startPulse();
            scanPulse.setConnected(act.rssiValid);
        } else {
            scanPulse.stopPulse();
            scanPulse.setVisibility(View.GONE);
        }
    }

    private void setButtonEnabled(View btn, boolean enabled) {
        btn.setEnabled(enabled);
        btn.setAlpha(enabled ? 1.0f : 0.4f);
    }

    private void setPowerButtonVisual(ImageView icon, TextView label, boolean active) {
        boolean light = MainActivity.isLightThemeActive(act);
        int activeColor = light ? 0xFF7C3AED : 0xFF00D4FF;
        int inactiveColor = light ? 0xFF94A3B8 : 0xFF94A3B8;
        if (active) {
            icon.setAlpha(1.0f);
            label.setTextColor(activeColor);
        } else {
            icon.setAlpha(0.5f);
            label.setTextColor(inactiveColor);
        }
    }
}
