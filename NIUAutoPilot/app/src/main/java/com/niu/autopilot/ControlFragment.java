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
    /** v2.77 上次同步的开机状态，用于检测"自动模式"状态翻转并补播动画 */
    private boolean lastSyncedPowerOn = false;
    /** v2.77 手动按钮刚播过动画（避免 refreshUi 翻转检测重复播放） */
    private boolean manualAnimJustPlayed = false;

    // v3.3 里程数据卡片
    private NiuMileageClient mileageClient;
    private TextView tvTotalMileage, tvTodayMileage, tvAvgSpeed, tvMaxSpeed, tvRideTime, tvLastRide;
    private TextView tvBindDays;
    private android.widget.LinearLayout llRecentRides;
    private TextView tvMileageRefresh;
    private boolean mileageLoading = false;

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

        // v3.3 里程数据卡片
        initMileageCard(view);

        swAutoOn.setOnCheckedChangeListener((b, c) -> {
            if (!refreshing) { act.settings.setAutoOn(c); if (c) swAutoOff.setChecked(false); }
        });
        swAutoOff.setOnCheckedChangeListener((b, c) -> {
            if (!refreshing) { act.settings.setAutoOff(c); if (c) swAutoOn.setChecked(false); }
        });

        // v3.3：真实灯效替代原紫红色扫描线动画。
        // 大灯照亮→光束推出→尾灯自检闪 2 下→地面光斑（开机）
        // 双闪左右同相 2 次→光束收缩→全灭（关机）
        btnPowerOn.setOnClickListener(v -> {
            animateButtonClick(v);
            if (vehicleStage != null) vehicleStage.playLightPowerOn(null);
            runCommand(Settings.CMD_POWER_ON, "开机", R.id.tv_power_on_label, "开机", true);
        });
        btnPowerOff.setOnClickListener(v -> {
            animateButtonClick(v);
            if (vehicleStage != null) vehicleStage.playLightPowerOff(null);
            runCommand(Settings.CMD_POWER_OFF, "关机", R.id.tv_power_off_label, "关机", false);
        });
        btnSeat.setOnClickListener(v -> {
            animateButtonClick(v);
            if (vehicleStage != null) vehicleStage.playLightBucketOpen(null);
            runCommand(Settings.CMD_SEAT, "开坐桶", R.id.tv_seat_label, "开坐桶", null);
        });
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
        // v3.4: ScifiPowerAnimator 已退出控制页（设计规范 §5.1 单一动画所有权）。
        // 它基于 overlay addView，会和 LightFxLayer 争 vehicle_stage_container。
        autoCheckStateIfReady();
    }

    @Override
    public void onDestroyView() {
        // v3.4: 只清理仍在使用中的动画资源
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
                    // v3.4: 单一动画所有权（设计规范 §5.1）
                    // 灯效已由按钮点击时的 vehicleStage.playLightXxx() 唯一驱动，
                    // 这里【不再】调 vehicleAnimation.playXxxAnimation()，避免两套时间轴互相掐断
                    // —— 这是"动画生硬"的头号根因。
                    manualAnimJustPlayed = true;
                },
                (ok, msg) -> {
                    setCommandSending(false);
                    if (!ok && vehicleStage != null) {
                        // 命令失败才回滚视觉（灯效直接复位，不做补偿动画）
                        vehicleStage.resetLights();
                    }
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

    private void setCommandSending(boolean sending) {
        commandSending = sending;
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
        // v2.77 增强：若状态相对上次发生了"翻转"（且不是手动按钮刚播过动画），补播过渡动画，
        //       让自动模式（靠近开机/离开关机）也有完整灯光效果
        // v3.4: 改为调用【唯一入口】playLightPowerOn/Off（设计规范 §5.1）
        boolean flipped = (isOn != lastSyncedPowerOn);
        syncVehicleVisual(isOn);
        lastSyncedPowerOn = isOn;
        if (flipped && !commandSending && !manualAnimJustPlayed && vehicleStage != null) {
            if (isOn) {
                vehicleStage.playLightPowerOn(null);
            } else if ("已关机".equals(act.vehicleState)) {
                vehicleStage.playLightPowerOff(null);
            }
        }
        manualAnimJustPlayed = false;
        // v2.2 主题感知：刷新时同步舞台主题色（切主题重建后也生效）
        applyThemeToStage();

        // 圆形按钮不需要切换背景色，保持渐变色
        // 只需调整图标tint表示激活状态
        setPowerButtonVisual(ivPowerOnIcon, tvPowerOnLabel, isOn);
        setPowerButtonVisual(ivPowerOffIcon, tvPowerOffLabel, !isOn && !"未知".equals(act.vehicleState));

        updateLastCmd();

        String devName = act.settings.getDeviceName();
        boolean connected = act.autoModeRunning && act.rssiValid;
        // v3.4: 保留设计规范 §3.3 的描边胶囊（bg_sci_badge），只换文字与着色。
        // 旧实现 setBackgroundResource(bg_badge_*) 会把描边胶囊换成实心色块，
        // 吃掉新视觉（设计规范交付说明第 1 条）。
        int badgeColor;
        String badgeText;
        if (devName == null || devName.isEmpty()) {
            badgeText = "未绑定";
            badgeColor = 0xFF94A3B8;
        } else if (connected) {
            badgeText = "已连接";
            badgeColor = 0xFF22C55E;
        } else {
            badgeText = "未连接";
            badgeColor = 0xFF94A3B8;
        }
        tvStatusBadge.setText(badgeText);
        tvStatusBadge.setTextColor(badgeColor);
        // 只对背景做着色（保留 drawable 形状），不替换 drawable
        tvStatusBadge.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        (badgeColor & 0x00FFFFFF) | 0x1F000000));

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

    // ==================== v3.3 里程数据卡片 ====================

    private void initMileageCard(View view) {
        if (act == null) return;
        mileageClient = new NiuMileageClient(act);
        tvTotalMileage = view.findViewById(R.id.tv_total_mileage);
        tvTodayMileage = view.findViewById(R.id.tv_today_mileage);
        tvAvgSpeed = view.findViewById(R.id.tv_avg_speed);
        tvMaxSpeed = view.findViewById(R.id.tv_max_speed);
        tvRideTime = view.findViewById(R.id.tv_ride_time);
        tvLastRide = view.findViewById(R.id.tv_last_ride);
        tvBindDays = view.findViewById(R.id.tv_bind_days);
        llRecentRides = view.findViewById(R.id.ll_recent_rides);
        tvMileageRefresh = view.findViewById(R.id.tv_mileage_refresh);

        if (tvMileageRefresh != null) {
            tvMileageRefresh.setOnClickListener(v -> loadMileage(true));
        }

        // v3.4: 新增的 3 个折叠交互 ID（设计规范 §3.3，布局注释里列出的 Java 侧配合点）
        // ① 最近骑行 展开/收起
        TextView tvRecentToggle = view.findViewById(R.id.tv_recent_rides_toggle);
        if (tvRecentToggle != null && llRecentRides != null) {
            tvRecentToggle.setOnClickListener(v -> {
                boolean expand = llRecentRides.getVisibility() != View.VISIBLE;
                llRecentRides.setVisibility(expand ? View.VISIBLE : View.GONE);
                tvRecentToggle.setText(expand ? "▾ 最近骑行" : "▸ 最近骑行");
            });
        }
        // ② 自动模式详细参数 展开/收起
        TextView tvAutoExpand = view.findViewById(R.id.tv_auto_expand);
        View llAutoDetail = view.findViewById(R.id.ll_auto_detail);
        if (tvAutoExpand != null && llAutoDetail != null) {
            tvAutoExpand.setOnClickListener(v -> {
                boolean expand = llAutoDetail.getVisibility() != View.VISIBLE;
                llAutoDetail.setVisibility(expand ? View.VISIBLE : View.GONE);
                tvAutoExpand.setText(expand ? "▾ 详细参数" : "▸ 详细参数");
            });
        }

        // 有账号/SN 才自动加载，避免未配置时报错
        String account = act.settings.getAccount();
        String sn = act.settings.getSn();
        if (account != null && !account.isEmpty() && sn != null && !sn.isEmpty()) {
            loadMileage(false);
        } else {
            if (tvBindDays != null) { setBindDaysCapsule(false); tvBindDays.setText("请先在「我的」页登录并绑定车辆"); }
        }
    }

    /** 拉取并渲染里程数据 */
    private void loadMileage(boolean manual) {
        if (act == null || mileageClient == null) return;
        if (mileageLoading) return;

        String account = act.settings.getAccount();
        String password = act.settings.getPassword();
        String sn = act.settings.getSn();
        if (account == null || account.isEmpty() || sn == null || sn.isEmpty()) {
            if (manual) Toast.makeText(act, "请先登录并绑定车辆", Toast.LENGTH_SHORT).show();
            return;
        }

        mileageLoading = true;
        if (tvBindDays != null) { setBindDaysCapsule(false); tvBindDays.setText("正在获取里程数据…"); }
        if (tvMileageRefresh != null) tvMileageRefresh.setText("…");

        mileageClient.loadSummary(account, password, sn, (ok, s, msg) -> {
            mileageLoading = false;
            if (getActivity() == null || !isAdded()) return;
            if (tvMileageRefresh != null) tvMileageRefresh.setText("刷新");

            if (!ok) {
                if (tvBindDays != null) { setBindDaysCapsule(false); tvBindDays.setText("获取失败：" + msg); }
                if (manual) Toast.makeText(act, "获取失败：" + msg, Toast.LENGTH_LONG).show();
                return;
            }
            renderMileage(s);
        });
    }

    private void renderMileage(NiuMileageClient.MileageSummary s) {
        if (tvTotalMileage != null) {
            tvTotalMileage.setText(s.totalMileageKm >= 0
                    ? String.format(java.util.Locale.US, "%.1f", s.totalMileageKm) : "—");
        }
        if (tvTodayMileage != null) {
            tvTodayMileage.setText(s.todayKm >= 0
                    ? String.format(java.util.Locale.US, "%.1f", s.todayKm) : "—");
        }
        // 上次骑行：大数字，与总里程/今日里程同级
        if (tvLastRide != null) {
            tvLastRide.setText(s.lastRideKm >= 0
                    ? String.format(java.util.Locale.US, "%.1f", s.lastRideKm) : "—");
        }
        // v3.7: 三格指标全部取【最近一次骑行】的同一份数据，
        // 不再用今天的 todayAvgSpeed / todayMaxSpeed / todayRidingTimeSec。
        // 用户原话（m08917）：「平均速度以及最高车速和时间应该都是最近最新的一次骑行数据才对」
        if (tvAvgSpeed != null) {
            tvAvgSpeed.setText(s.lastRideAvgSpeed >= 0
                    ? String.format(java.util.Locale.US, "%.0f", s.lastRideAvgSpeed) : "—");
        }
        if (tvMaxSpeed != null) {
            tvMaxSpeed.setText(s.lastRideMaxSpeed >= 0
                    ? String.format(java.util.Locale.US, "%.0f", s.lastRideMaxSpeed) : "—");
        }
        if (tvRideTime != null) {
            tvRideTime.setText(s.lastRideSec >= 0
                    ? formatDuration(s.lastRideSec) : "—");
        }

        // 陪伴天数：顶部居中胶囊
        // v3.4: 长文本/错误态时去掉胶囊背景，否则 12sp 单行省略会把提示截成半句话
        // （设计规范交付说明第 2 条）
        if (tvBindDays != null) {
            if (s.bindDays > 0) {
                setBindDaysCapsule(true);
                tvBindDays.setText("已陪伴你 " + s.bindDays + " 天");
            } else {
                setBindDaysCapsule(false);
                tvBindDays.setText("");
            }
        }

        // 最近骑行列表：默认折叠，填充数据后保持 gone 由用户手动展开
        if (llRecentRides != null) {
            llRecentRides.removeAllViews();
            if (s.recentRides != null && !s.recentRides.isEmpty()) {
                int shown = 0;
                for (NiuMileageClient.Ride r : s.recentRides) {
                    if (shown >= 5) break;
                    if (r.distanceKm < 0) continue;
                    llRecentRides.addView(buildRideRow(r));
                    shown++;
                }
            }
        }
    }

    /**
     * v3.4: 陪伴天数胶囊开关。
     * 正常态保留布局里的 bg_sci_badge 胶囊（accent 描边）；
     * 提示/错误态去掉背景并放开行数，否则 12sp 单行省略会把提示截成半句话。
     */
    private void setBindDaysCapsule(boolean capsule) {
        if (tvBindDays == null) return;
        if (capsule) {
            tvBindDays.setBackgroundResource(R.drawable.bg_sci_badge);
            tvBindDays.setMaxLines(1);
            tvBindDays.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tvBindDays.setTextColor(0xFF00D4FF);
        } else {
            tvBindDays.setBackground(null);
            tvBindDays.setMaxLines(3);
            tvBindDays.setEllipsize(null);
            tvBindDays.setTextColor(0xFF94A3B8);
        }
    }

    /** 构建单条骑行记录行 */
    private View buildRideRow(NiuMileageClient.Ride r) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(act);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, dp(5));

        TextView left = new TextView(act);
        left.setText(dateLabel(r.startMs, r.date));
        left.setTextSize(11f);
        left.setTextColor(0xFF94A3B8);
        left.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView mid = new TextView(act);
        mid.setText(String.format(java.util.Locale.US, "%.1f km", r.distanceKm));
        mid.setTextSize(11f);
        mid.setTextColor(0xFFCBD5E1);
        mid.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView right = new TextView(act);
        right.setText(r.avgSpeed >= 0
                ? String.format(java.util.Locale.US, "均速 %.0f", r.avgSpeed) : "");
        right.setTextSize(11f);
        right.setTextColor(0xFF94A3B8);
        right.setGravity(android.view.Gravity.END);
        right.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(left);
        row.addView(mid);
        row.addView(right);
        return row;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 秒 → "1小时23分" / "23分" / "45秒" */
    private static String formatDuration(int sec) {
        if (sec < 0) return "—";
        int h = sec / 3600;
        int m = (sec % 3600) / 60;
        if (h > 0) return h + "小时" + m + "分";
        if (m > 0) return m + "分";
        return sec + "秒";
    }

    /** 时间戳 → "刚刚" / "3小时前" / "2天前" */
    private static String relativeTime(long ms) {
        long diff = System.currentTimeMillis() - ms;
        if (diff < 0) return "";
        long min = diff / 60000;
        if (min < 1) return "刚刚";
        if (min < 60) return min + "分钟前";
        long h = min / 60;
        if (h < 24) return h + "小时前";
        return (h / 24) + "天前";
    }

    /** 日期标签：优先用 date(yyyyMMdd)，否则由时间戳格式化 */
    private static String dateLabel(long ms, String date) {
        if (date != null && date.length() == 8) {
            return date.substring(4, 6) + "/" + date.substring(6, 8);
        }
        if (ms > 0) {
            return new java.text.SimpleDateFormat("MM/dd", java.util.Locale.US)
                    .format(new java.util.Date(ms));
        }
        return "";
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
