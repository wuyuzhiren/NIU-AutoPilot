package com.niu.autopilot;

import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置页：车辆蓝牙配对 + 检测参数滑块 + 显示设置 + 日志/关于
 */
public class SettingsFragment extends Fragment {
    private MainActivity act;
    private NiuBleManager ble;
    private TextView tvDeviceName, tvDeviceMac;
    private TextView tvIntervalVal, tvRssiInVal, tvRssiOutVal, tvCooldownVal;
    private SeekBar sbInterval, sbRssiIn, sbRssiOut, sbCooldown;
    private ImageView ivThemeSystemCheck, ivThemeLightCheck, ivThemeDarkCheck;
    private View vLogDot;
    private boolean scanning = false;
    private final Map<String, String> foundDevices = new LinkedHashMap<>();
    private final Map<String, Integer> foundRssi = new LinkedHashMap<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        act = (MainActivity) getActivity();
        ble = new NiuBleManager(act, bleCallback);

        tvDeviceName = view.findViewById(R.id.tv_device_name);
        tvDeviceMac = view.findViewById(R.id.tv_device_mac);
        tvIntervalVal = view.findViewById(R.id.tv_interval_val);
        tvRssiInVal = view.findViewById(R.id.tv_rssi_in_val);
        tvRssiOutVal = view.findViewById(R.id.tv_rssi_out_val);
        tvCooldownVal = view.findViewById(R.id.tv_cooldown_val);
        sbInterval = view.findViewById(R.id.sb_interval);
        sbRssiIn = view.findViewById(R.id.sb_rssi_in);
        sbRssiOut = view.findViewById(R.id.sb_rssi_out);
        sbCooldown = view.findViewById(R.id.sb_cooldown);
        ivThemeSystemCheck = view.findViewById(R.id.iv_theme_system_check);
        ivThemeLightCheck = view.findViewById(R.id.iv_theme_light_check);
        ivThemeDarkCheck = view.findViewById(R.id.iv_theme_dark_check);
        vLogDot = view.findViewById(R.id.v_log_dot);

        // 扫描间隔：1~30秒 (max=29, progress=value-1)
        sbInterval.setMax(29);
        sbRssiIn.setMax(50);
        sbRssiOut.setMax(50);
        sbCooldown.setMax(57);

        sbInterval.setOnSeekBarChangeListener(simpleSeek(() -> {
            int v = sbInterval.getProgress() + 1;
            act.settings.setScanIntervalSec(v);
            tvIntervalVal.setText(v + " 秒");
        }));
        sbRssiIn.setOnSeekBarChangeListener(simpleSeek(() -> {
            int v = sbRssiIn.getProgress() - 90;
            act.settings.setRssiThreshold(v);
            tvRssiInVal.setText(v + " dBm (" + MainActivity.rssiToMeter(v) + ")");
        }));
        sbRssiOut.setOnSeekBarChangeListener(simpleSeek(() -> {
            int v = sbRssiOut.getProgress() - 95;
            act.settings.setRssiLeaveThreshold(v);
            tvRssiOutVal.setText(v + " dBm (" + MainActivity.rssiToMeter(v) + ")");
        }));
        sbCooldown.setOnSeekBarChangeListener(simpleSeek(() -> {
            int v = sbCooldown.getProgress() + 5;
            act.settings.setAutoOnCooldownSec(v);
            tvCooldownVal.setText(v + " 秒");
        }));

        view.findViewById(R.id.btn_scan_dev).setOnClickListener(v -> scanAndPick());
        view.findViewById(R.id.entry_log).setOnClickListener(v -> {
            vLogDot.setVisibility(View.GONE);
            act.showLogDialog();
        });
        view.findViewById(R.id.entry_about).setOnClickListener(v -> new AlertDialog.Builder(act)
                .setTitle("关于")
                .setMessage("小牛靠近开机助手 v2.7\n\n蓝牙测距 + 官方远程命令\n副账号可用，无需蓝牙连接\n桌面小部件：4×1快捷栏 + 2×2状态件\n自动开关机：边沿触发防重复\n\n手动：开机/关机/开坐桶/查状态\n自动：靠近开机/离开关机")
                .setPositiveButton("确定", null)
                .show());

        // 主题切换
        view.findViewById(R.id.opt_theme_system).setOnClickListener(v -> act.changeTheme(0));
        view.findViewById(R.id.opt_theme_light).setOnClickListener(v -> act.changeTheme(1));
        view.findViewById(R.id.opt_theme_dark).setOnClickListener(v -> act.changeTheme(2));

        refreshUi();
    }

    private SeekBar.OnSeekBarChangeListener simpleSeek(Runnable onChange) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) onChange.run();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private final NiuBleManager.Callback bleCallback = new NiuBleManager.Callback() {
        @Override public void onScanResult(BluetoothDevice device, String name, int rssi) {
            if (device.getAddress() == null) return;
            String mac = device.getAddress();
            String label = (name == null || name.isEmpty()) ? "(无名称)" : name;
            if (!foundDevices.containsKey(mac) || rssi > foundRssi.getOrDefault(mac, Integer.MIN_VALUE)) {
                foundDevices.put(mac, label);
                foundRssi.put(mac, rssi);
            }
        }
        @Override public void onScanState(boolean s) {}
        @Override public void onLog(String line) {}
    };

    private void scanAndPick() {
        if (scanning) return;
        if (act.autoModeRunning) {
            act.toast("自动模式运行中，请先停止再更换车辆");
            return;
        }
        scanning = true;
        foundDevices.clear();
        foundRssi.clear();
        act.toast("扫描中(5秒)... 请靠近车辆");
        ble.startScan();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            ble.stopScan();
            scanning = false;
            showPicker();
        }, 5000);
    }

    private void showPicker() {
        if (foundDevices.isEmpty()) {
            act.toast("没扫到设备，请靠近车辆再试");
            return;
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(foundDevices.entrySet());
        entries.sort((a, b) -> foundRssi.getOrDefault(b.getKey(), -200) - foundRssi.getOrDefault(a.getKey(), -200));

        final List<String> macs = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        for (Map.Entry<String, String> e : entries) {
            macs.add(e.getKey());
            int rssi = foundRssi.getOrDefault(e.getKey(), -200);
            labels.add(e.getValue() + "\n" + e.getKey() + "  " + rssi + "dBm " + MainActivity.rssiToMeter(rssi));
        }
        new AlertDialog.Builder(act)
                .setTitle("选择你的车辆蓝牙（按信号排序）")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    String mac = macs.get(which);
                    act.settings.setDeviceMac(mac);
                    act.settings.setDeviceName(foundDevices.get(mac));
                    act.appendLog("已选择车辆蓝牙: " + foundDevices.get(mac) + "  " + mac);
                    act.toast("已绑定: " + foundDevices.get(mac));
                    refreshUi();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public void refreshUi() {
        if (act == null || getView() == null) return;
        String name = act.settings.getDeviceName();
        String mac = act.settings.getDeviceMac();
        if (name == null || name.isEmpty()) {
            tvDeviceName.setText("未绑定");
            tvDeviceMac.setText("MAC: --");
        } else {
            tvDeviceName.setText(name);
            tvDeviceMac.setText("MAC: " + mac);
        }
        sbInterval.setProgress(act.settings.getScanIntervalSec() - 1);
        sbRssiIn.setProgress(act.settings.getRssiThreshold() + 90);
        sbRssiOut.setProgress(act.settings.getRssiLeaveThreshold() + 95);
        sbCooldown.setProgress(act.settings.getAutoOnCooldownSec() - 5);
        tvIntervalVal.setText(act.settings.getScanIntervalSec() + " 秒");
        tvRssiInVal.setText(act.settings.getRssiThreshold() + " dBm (" + MainActivity.rssiToMeter(act.settings.getRssiThreshold()) + ")");
        tvRssiOutVal.setText(act.settings.getRssiLeaveThreshold() + " dBm (" + MainActivity.rssiToMeter(act.settings.getRssiLeaveThreshold()) + ")");
        tvCooldownVal.setText(act.settings.getAutoOnCooldownSec() + " 秒");

        // 主题选中态
        int mode = act.settings.getThemeMode();
        ivThemeSystemCheck.setVisibility(mode == 0 ? View.VISIBLE : View.GONE);
        ivThemeLightCheck.setVisibility(mode == 1 ? View.VISIBLE : View.GONE);
        ivThemeDarkCheck.setVisibility(mode == 2 ? View.VISIBLE : View.GONE);

        // 日志红点：有日志且未查看时显示
        if (act.logText.length() > 20 && vLogDot.getVisibility() != View.VISIBLE) {
            // 简单逻辑：有日志就提示一次，用户点过后消失
        }
    }
}

