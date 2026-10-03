package com.niu.autopilot;

import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;

import com.niu.autopilot.calibration.CalibrationActivity;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Toast;
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
 * 设置页 v2.13：车辆蓝牙配对 + 检测参数滑块 + 滤波窗口 + 场景模式 + 电量同步 + 显示设置 + 日志/关于
 */
public class SettingsFragment extends Fragment {
    private MainActivity act;
    private NiuBleManager ble;
    private TextView tvDeviceName, tvDeviceMac;
    private TextView tvIntervalVal, tvRssiInVal, tvHysteresisVal, tvCooldownVal, tvFilterVal;
    private SeekBar sbInterval, sbRssiIn, sbHysteresis, sbCooldown, sbFilter;
    private ImageView ivThemeSystemCheck, ivThemeLightCheck, ivThemeDarkCheck;
    private android.widget.Button btnPresetAggressive, btnPresetBalanced, btnPresetStable;
    private TextView tvPresetDesc;
    private android.widget.Switch swVibration, swSound;
    private TextView tvBatterySet;
    private EditText etBatteryManual;
    private android.widget.Button btnBatteryManual, btnBatterySync, btnSaveToken, btnScanToken;
    private android.widget.Button btnPlatformJichi, btnPlatformHello, btnPlatformXinneng, btnPlatformCustom;
    private EditText etBatteryToken, etBatteryUrl, etBatteryDeviceId, etBatteryAppId, etBatterySign;
    private View vLogDot;
    private boolean scanning = false;
    private final Map<String, String> foundDevices = new LinkedHashMap<>();
    private final Map<String, Integer> foundRssi = new LinkedHashMap<>();

    // v2.22 软件更新
    private EditText etUpdateUrl;
    private android.widget.Switch swAutoUpdate, swQuickCalib, swAutoApply;
    private android.widget.Button btnCheckUpdate;

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
        tvHysteresisVal = view.findViewById(R.id.tv_hysteresis_val);
        tvCooldownVal = view.findViewById(R.id.tv_cooldown_val);
        tvFilterVal = view.findViewById(R.id.tv_filter_val);
        sbInterval = view.findViewById(R.id.sb_interval);
        sbRssiIn = view.findViewById(R.id.sb_rssi_in);
        sbHysteresis = view.findViewById(R.id.sb_hysteresis);
        sbCooldown = view.findViewById(R.id.sb_cooldown);
        sbFilter = view.findViewById(R.id.sb_filter);
        ivThemeSystemCheck = view.findViewById(R.id.iv_theme_system_check);
        ivThemeLightCheck = view.findViewById(R.id.iv_theme_light_check);
        ivThemeDarkCheck = view.findViewById(R.id.iv_theme_dark_check);
        vLogDot = view.findViewById(R.id.v_log_dot);
        btnPresetAggressive = view.findViewById(R.id.btn_preset_aggressive);
        btnPresetBalanced = view.findViewById(R.id.btn_preset_balanced);
        btnPresetStable = view.findViewById(R.id.btn_preset_stable);
        tvPresetDesc = view.findViewById(R.id.tv_preset_desc);
        swVibration = view.findViewById(R.id.sw_vibration);
        swSound = view.findViewById(R.id.sw_sound);
        tvBatterySet = view.findViewById(R.id.tv_battery_set);
        etBatteryManual = view.findViewById(R.id.et_battery_manual);
        btnBatteryManual = view.findViewById(R.id.btn_battery_manual);
        btnBatterySync = view.findViewById(R.id.btn_battery_sync);
        etBatteryToken = view.findViewById(R.id.et_battery_token);
        btnSaveToken = view.findViewById(R.id.btn_save_token);
        btnScanToken = view.findViewById(R.id.btn_scan_token);
        btnPlatformJichi = view.findViewById(R.id.btn_platform_jichi);
        btnPlatformHello = view.findViewById(R.id.btn_platform_hello);
        btnPlatformXinneng = view.findViewById(R.id.btn_platform_xinneng);
        btnPlatformCustom = view.findViewById(R.id.btn_platform_custom);
        etBatteryUrl = view.findViewById(R.id.et_battery_url);
        etBatteryDeviceId = view.findViewById(R.id.et_battery_device_id);
        etBatteryDeviceId.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) act.settings.setBatteryDeviceId(etBatteryDeviceId.getText().toString().trim());
        });
        // v2.77 鑫能 appId / sign 可配置（接口变更时可改，无需改代码）
        etBatteryAppId = view.findViewById(R.id.et_battery_appid);
        etBatterySign = view.findViewById(R.id.et_battery_sign);
        etBatteryAppId.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) act.settings.setBatteryAppId(etBatteryAppId.getText().toString().trim());
        });
        etBatterySign.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) act.settings.setBatterySign(etBatterySign.getText().toString().trim());
        });

        // v2.22 软件更新
        etUpdateUrl = view.findViewById(R.id.et_update_url);
        swAutoUpdate = view.findViewById(R.id.sw_auto_update);
        swQuickCalib = view.findViewById(R.id.sw_quick_calib);
        swAutoApply = view.findViewById(R.id.sw_auto_apply);
        btnCheckUpdate = view.findViewById(R.id.btn_check_update);
        btnCheckUpdate.setOnClickListener(v -> doCheckUpdate());
        swAutoUpdate.setOnCheckedChangeListener((b, checked) -> act.settings.setAutoCheckUpdate(checked));
        etUpdateUrl.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) act.settings.setUpdateUrl(etUpdateUrl.getText().toString().trim());
        });

        // 扫描时长：100~5000毫秒（最短300ms），扫描结束立即开始下一次 (max=49, progress=(value/100)-1)
        sbInterval.setMax(49);
        sbRssiIn.setMax(50);
        sbHysteresis.setMax(45); // 滞回区间5~50dBm
        sbCooldown.setMax(57);
        sbFilter.setMax(9); // 滤波窗口 1~10

        sbInterval.setOnSeekBarChangeListener(simpleSeek(() -> {
            int v = (sbInterval.getProgress() + 1) * 100;
            act.settings.setScanIntervalMs(v);
            tvIntervalVal.setText(v + " 毫秒");
        }));
        sbRssiIn.setOnSeekBarChangeListener(simpleSeek(() -> {
            int v = sbRssiIn.getProgress() - 90;
            act.settings.setRssiThreshold(v);
            tvRssiInVal.setText(v + " dBm (" + MainActivity.rssiToMeter(v) + ")");
        }));
        sbHysteresis.setOnSeekBarChangeListener(simpleSeek(() -> {
            int v = sbHysteresis.getProgress() + 5;
            act.settings.setHysteresisDb(v);
            updateHysteresisDisplay();
        }));
        sbCooldown.setOnSeekBarChangeListener(simpleSeek(() -> {
            int v = sbCooldown.getProgress() + 5;
            act.settings.setAutoOnCooldownSec(v);
            tvCooldownVal.setText(v + " 秒");
        }));
        sbFilter.setOnSeekBarChangeListener(simpleSeek(() -> {
            int v = sbFilter.getProgress() + 1;
            act.settings.setFilterWindow(v);
            tvFilterVal.setText(v + " 点");
        }));

        view.findViewById(R.id.btn_scan_dev).setOnClickListener(v -> scanAndPick());

        view.findViewById(R.id.btn_calibrate).setOnClickListener(v -> {
            String mac = act.settings.getDeviceMac();
            if (mac == null || mac.isEmpty()) {
                Toast.makeText(act, "请先扫描并绑定车辆", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(act, CalibrationActivity.class);
            intent.putExtra("mac_address", mac);
            startActivity(intent);
        });
        view.findViewById(R.id.btn_calib_history).setOnClickListener(v -> showCalibHistory());
        view.findViewById(R.id.btn_scene_mgr).setOnClickListener(v -> showSceneManager());

        view.findViewById(R.id.btn_reset_params).setOnClickListener(v -> {
            act.settings.setScanIntervalMs(700);
            act.settings.setRssiThreshold(-60);
            act.settings.setHysteresisDb(20);
            act.settings.setAutoOnCooldownSec(30);
            act.settings.setFilterWindow(3);
            act.settings.setConsecutiveRequired(3);
            refreshUi();
            Toast.makeText(act, "已重置为默认参数", Toast.LENGTH_SHORT).show();
        });

        // v2.13: 电量手动录入
        btnBatteryManual.setOnClickListener(v -> {
            String s = etBatteryManual.getText().toString().trim();
            if (s.isEmpty()) { Toast.makeText(act, "请输入电量 0~100", Toast.LENGTH_SHORT).show(); return; }
            int p;
            try { p = Integer.parseInt(s); } catch (Exception e) { Toast.makeText(act, "请输入数字", Toast.LENGTH_SHORT).show(); return; }
            if (p < 0 || p > 100) { Toast.makeText(act, "电量需在 0~100 之间", Toast.LENGTH_SHORT).show(); return; }
            new NiuBatteryClient(act).manualSync(p);
            etBatteryManual.setText("");
            refreshBattery();
            Toast.makeText(act, "电量已同步: " + p + "%", Toast.LENGTH_SHORT).show();
            act.appendLog("电量手动同步: " + p + "%");
        });

        // v2.63: 平台切换（极驰锐动 / 哈啰 / 鑫能出行 / 自定义API）
        btnPlatformJichi.setOnClickListener(v -> { act.settings.setBatteryPlatform("jichi"); updateBatteryPlatformUi(); });
        btnPlatformHello.setOnClickListener(v -> { act.settings.setBatteryPlatform("hello"); updateBatteryPlatformUi(); });
        btnPlatformXinneng.setOnClickListener(v -> { act.settings.setBatteryPlatform("xinneng"); updateBatteryPlatformUi(); });
        btnPlatformCustom.setOnClickListener(v -> { act.settings.setBatteryPlatform("custom"); updateBatteryPlatformUi(); });
        etBatteryUrl.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) act.settings.setBatteryApiUrl(etBatteryUrl.getText().toString().trim());
        });

        // v2.15: 保存 token（按平台提示）
        btnSaveToken.setOnClickListener(v -> {
            String t = etBatteryToken.getText().toString().trim();
            if (t.isEmpty()) { Toast.makeText(act, "请先粘贴 token", Toast.LENGTH_SHORT).show(); return; }
            act.settings.setBatteryToken(t);
            String plat = act.settings.getBatteryPlatform();
            if (!"jichi".equals(plat) && !"xinneng".equals(plat)) {
                String url = etBatteryUrl.getText().toString().trim();
                act.settings.setBatteryApiUrl(url);
                Toast.makeText(act, "token 与接口地址已保存，点「从" + NiuBatteryClient.platformName(act.settings) + "同步」即可读取电量", Toast.LENGTH_LONG).show();
                act.appendLog("电池API token 已保存");
            } else if ("xinneng".equals(plat)) {
                // v2.77 鑫能出行：token 保存时同步保存 appId/sign（支持接口变更热调整）
                String deviceId = etBatteryDeviceId.getText().toString().trim();
                act.settings.setBatteryDeviceId(deviceId);
                act.settings.setBatteryAppId(etBatteryAppId.getText().toString().trim());
                act.settings.setBatterySign(etBatterySign.getText().toString().trim());
                Toast.makeText(act, "token 已保存，点「从鑫能出行同步」读取电量（电池ID 已填则直查，未填自动识别）", Toast.LENGTH_LONG).show();
                act.appendLog("鑫能出行 token 已保存");
            } else {
                Toast.makeText(act, "token 已保存，点「从极驰锐动同步」即可读取电量", Toast.LENGTH_SHORT).show();
                act.appendLog("极驰锐动 token 已保存");
            }
        });

        // v2.16: 扫码导入 token - 显示教程弹窗
        btnScanToken.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(act)
                .setTitle("扫码导入 token")
                .setMessage("1. 在电脑上打开「极驰锐动token二维码生成器.html」\n2. 把你抓到的 Authorization token 粘贴进去，生成二维码\n3. 用手机自带相机扫二维码，点击「在牛牛靠近开机助手中打开」\n4. App 自动导入 token 并同步电量\n\n（token 是你自己的账号凭证，请勿分享给他人）")
                .setPositiveButton("知道了", null)
                .show();
        });

        // v2.15: 从电池平台同步（极驰锐动固定URL / 自定义API）
        btnBatterySync.setOnClickListener(v -> {
            btnBatterySync.setEnabled(false);
            btnBatterySync.setText("同步中...");
            String platName = NiuBatteryClient.platformName(act.settings);
            new NiuBatteryClient(act).queryBattery((ok, p, msg) -> act.runOnUiThread(() -> {
                btnBatterySync.setEnabled(true);
                btnBatterySync.setText("从" + platName + "同步");
                refreshBattery();
                if (ok) {
                    Toast.makeText(act, "电量同步成功: " + p + "%", Toast.LENGTH_SHORT).show();
                    act.appendLog("电量自动同步(" + platName + "): " + p + "%");
                } else {
                    Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
                }
            }));
        });

        view.findViewById(R.id.entry_log).setOnClickListener(v -> {
            vLogDot.setVisibility(View.GONE);
            act.showLogDialog();
        });
        view.findViewById(R.id.entry_about).setOnClickListener(v -> showAboutDialog());

        // 场景模式预设
        btnPresetAggressive.setOnClickListener(v -> applyPreset(Settings.PRESET_AGGRESSIVE));
        btnPresetBalanced.setOnClickListener(v -> applyPreset(Settings.PRESET_BALANCED));
        btnPresetStable.setOnClickListener(v -> applyPreset(Settings.PRESET_STABLE));
        swVibration.setOnCheckedChangeListener((b, checked) -> act.settings.setVibrationEnabled(checked));
        swSound.setOnCheckedChangeListener((b, checked) -> act.settings.setSoundEnabled(checked));

        // 主题切换
        view.findViewById(R.id.opt_theme_system).setOnClickListener(v -> act.changeTheme(0));
        view.findViewById(R.id.opt_theme_light).setOnClickListener(v -> act.changeTheme(1));
        view.findViewById(R.id.opt_theme_dark).setOnClickListener(v -> act.changeTheme(2));

        refreshUi();
        updateBatteryPlatformUi();
    }

    /** v2.63: 刷新电池平台UI（高亮选中项、URL框显隐、同步按钮文案） */
    private void updateBatteryPlatformUi() {
        String plat = act.settings.getBatteryPlatform();
        boolean custom = !"jichi".equals(plat) && !"xinneng".equals(plat); // 哈啰/自定义 才填 URL
        boolean xinneng = "xinneng".equals(plat);
        btnPlatformJichi.setBackgroundResource("jichi".equals(plat) ? R.drawable.bg_btn_primary : R.drawable.bg_btn_outline);
        btnPlatformHello.setBackgroundResource("hello".equals(plat) ? R.drawable.bg_btn_primary : R.drawable.bg_btn_outline);
        btnPlatformXinneng.setBackgroundResource("xinneng".equals(plat) ? R.drawable.bg_btn_primary : R.drawable.bg_btn_outline);
        btnPlatformCustom.setBackgroundResource("custom".equals(plat) ? R.drawable.bg_btn_primary : R.drawable.bg_btn_outline);
        etBatteryUrl.setVisibility(custom ? View.VISIBLE : View.GONE);
        etBatteryUrl.setText(act.settings.getBatteryApiUrl());
        // v2.76 鑫能出行：恢复显示电池ID输入框（token 自动发现接口常因路径变动失效，
        // 换电池后直接填小程序「我的设备」里的 BTA 开头ID 最稳妥）
        etBatteryDeviceId.setVisibility(xinneng ? View.VISIBLE : View.GONE);
        etBatteryDeviceId.setText(act.settings.getBatteryDeviceId());
        // v2.77 鑫能 appId / sign 可配置（默认保留抓包原值，接口变更时才需要改）
        etBatteryAppId.setVisibility(xinneng ? View.VISIBLE : View.GONE);
        etBatteryAppId.setText(act.settings.getBatteryAppId());
        etBatterySign.setVisibility(xinneng ? View.VISIBLE : View.GONE);
        etBatterySign.setText(act.settings.getBatterySign());
        String platName = NiuBatteryClient.platformName(act.settings);
        etBatteryToken.setHint(xinneng
                ? "粘贴鑫能出行 token（抓包 device/detail 请求的 access-token 值）"
                : "粘贴" + platName + " token（抓包电量接口的 Authorization 值）");
        btnBatterySync.setText("从" + platName + "同步");
    }

    /** v2.28: 从校准页/其他页面返回时自动刷新阈值等参数显示（修复校准应用后界面不变的问题） */
    @Override
    public void onResume() {
        super.onResume();
        if (getView() != null) refreshUi();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && getView() != null) refreshUi();
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
        try {
            TextView tvVer = getView().findViewById(R.id.tv_version);
            if (tvVer != null) {
                String ver = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
                tvVer.setText("v" + ver);
            }
        } catch (Exception ignored) {}
        try {
            TextView tvPs = getView().findViewById(R.id.tv_param_source);
            String src = act.settings.getParamSource();
            if (src.isEmpty()) { tvPs.setVisibility(View.GONE); }
            else { tvPs.setText("当前参数: " + src); tvPs.setVisibility(View.VISIBLE); }
        } catch (Exception ignored) {}
        String name = act.settings.getDeviceName();
        String mac = act.settings.getDeviceMac();
        if (name == null || name.isEmpty()) {
            tvDeviceName.setText("未绑定");
            tvDeviceMac.setText("MAC: --");
        } else {
            tvDeviceName.setText(name);
            tvDeviceMac.setText("MAC: " + mac);
        }
        sbInterval.setProgress(act.settings.getScanIntervalMs() / 100 - 1);
        sbRssiIn.setProgress(act.settings.getRssiThreshold() + 90);
        sbHysteresis.setProgress(act.settings.getHysteresisDb() - 5);
        sbCooldown.setProgress(act.settings.getAutoOnCooldownSec() - 5);
        sbFilter.setProgress(act.settings.getFilterWindow() - 1);
        tvIntervalVal.setText(act.settings.getScanIntervalMs() + " 毫秒");
        tvRssiInVal.setText(act.settings.getRssiThreshold() + " dBm (" + MainActivity.rssiToMeter(act.settings.getRssiThreshold()) + ")");
        updateHysteresisDisplay();
        tvCooldownVal.setText(act.settings.getAutoOnCooldownSec() + " 秒");
        tvFilterVal.setText(act.settings.getFilterWindow() + " 点");

        // 电量
        refreshBattery();

        // v2.22 软件更新配置回填
        if (etUpdateUrl.getText().toString().isEmpty()) {
            etUpdateUrl.setText(act.settings.getUpdateUrl());
        }
        swQuickCalib.setChecked(act.settings.isQuickCalibration());
        swQuickCalib.setOnCheckedChangeListener((b, c) -> act.settings.setQuickCalibration(c));
        swAutoApply.setChecked(act.settings.isAutoApplyAfterVerify());
        swAutoApply.setOnCheckedChangeListener((b, c) -> act.settings.setAutoApplyAfterVerify(c));

        swAutoUpdate.setChecked(act.settings.isAutoCheckUpdate());
        // 已保存的 token 回填到输入框（脱敏：只显示前20位）
        String savedToken = act.settings.getBatteryToken();
        if (!savedToken.isEmpty() && etBatteryToken.getText().toString().isEmpty()) {
            etBatteryToken.setHint("已保存 token（" + savedToken.length() + " 字符），可直接点同步");
        }

        // 主题选中态
        int mode = act.settings.getThemeMode();
        ivThemeSystemCheck.setVisibility(mode == 0 ? View.VISIBLE : View.GONE);
        ivThemeLightCheck.setVisibility(mode == 1 ? View.VISIBLE : View.GONE);
        ivThemeDarkCheck.setVisibility(mode == 2 ? View.VISIBLE : View.GONE);

        // 场景模式高亮
        int preset = act.settings.getPresetMode();
        btnPresetAggressive.setBackgroundTintList(android.content.res.ColorStateList.valueOf(preset == Settings.PRESET_AGGRESSIVE ? 0xFF1565C0 : 0xFFE0E0E0));
        btnPresetAggressive.setTextColor(preset == Settings.PRESET_AGGRESSIVE ? 0xFFFFFFFF : 0xFF424242);
        btnPresetBalanced.setBackgroundTintList(android.content.res.ColorStateList.valueOf(preset == Settings.PRESET_BALANCED ? 0xFF1565C0 : 0xFFE0E0E0));
        btnPresetBalanced.setTextColor(preset == Settings.PRESET_BALANCED ? 0xFFFFFFFF : 0xFF424242);
        btnPresetStable.setBackgroundTintList(android.content.res.ColorStateList.valueOf(preset == Settings.PRESET_STABLE ? 0xFF1565C0 : 0xFFE0E0E0));
        btnPresetStable.setTextColor(preset == Settings.PRESET_STABLE ? 0xFFFFFFFF : 0xFF424242);
        String[] descs = {"激进模式：更早触发(靠近-65/滞回15/连续1次/冷却10s)", "均衡模式：平衡响应与稳定(靠近-60/滞回20/连续2次/冷却30s)", "稳定模式：抗干扰最强(靠近-55/滞回25/连续3次/冷却60s)"};
        tvPresetDesc.setText(descs[preset]);
        swVibration.setChecked(act.settings.isVibrationEnabled());
        swSound.setChecked(act.settings.isSoundEnabled());
    }

    private void refreshBattery() {
        NiuBatteryClient b = new NiuBatteryClient(act);
        int p = b.getPercent();
        int level = b.level();
        if (p < 0) {
            tvBatterySet.setText("未同步");
            tvBatterySet.setTextColor(0xFF9E9E9E);
        } else {
            tvBatterySet.setText(p + "%  ·  " + b.syncedText());
            tvBatterySet.setTextColor(NiuBatteryClient.colorFor(level));
        }
    }

    private void applyPreset(int mode) {
        act.settings.applyPreset(mode);


        refreshUi();
        String[] names = {"激进", "均衡", "稳定"};
        Toast.makeText(act, "已切换为" + names[mode] + "模式", Toast.LENGTH_SHORT).show();
    }

    /** 更新滞回区间显示：区间宽度 + 实际信号范围（离开 ~ 靠近） */
    private void updateHysteresisDisplay() {
        int hyst = act.settings.getHysteresisDb();
        int near = act.settings.getRssiThreshold();
        int leave = act.settings.getRssiLeaveThreshold();
        tvHysteresisVal.setText(hyst + " dBm（" + leave + " ~ " + near + "）");
    }

    // ========== v2.66 关于页（R15 版本更新日志 / R16 耗电与隐私说明） ==========

    /** 内置更新日志（新版本在最前），与 version.json 的 changelog 保持同步 */
    private static final String[] CHANGELOG = {
            "v2.73：换电池后自动识别新电池（鑫能每次同步强制重新发现，不再显示旧电池电量）；日志导出合并诊断数据，AI调参不再提示无RSSI样本；空日志拒绝上传",
            "v2.72：后台扫描绑定车辆MAC过滤，绕开系统限流、扫描频率恢复正常；靠近需连续3次/离开连续5次达标防抖动误触发；扫描异常3/6/12秒自动恢复",
            "v2.71：信号触发优化——走进靠近区间且信号持续增强即提前开机（连续2次增强防误触）；距离显示档位保守化，楼上/穿墙不再误显示成近距离",
            "v2.70：鑫能出行只填token自动识别当前电池（换电池无需再填电池ID，内置设备查询接口自动匹配）",
            "v2.69：控制页接入全新车辆动画引擎（NX Wind幻影紫45°展示：开机能量环+大灯光束、关机双闪、坐桶弹开，指令失败自动回滚；高/低配机自适应特效）",
            "v2.68：鑫能出行电量平台正式接通（内置接口+抓包access-token+电池ID，自动解析电量soc）；解析器支持深层嵌套字段",
            "v2.67：科幻HUD控制页（深空背景+能量蓝光效）；紫色小牛NX 45°车辆动画（开机光束/关机双闪/坐桶弹开）；桌面小部件科幻发光边框",
            "v2.66：触发声音反馈（开机双响/关机长鸣，可关）；日志弹窗支持关键词检索；关于页显示完整更新日志",
            "v2.65：扫描常驻防漏广播；信号保活15秒；无效RSSI过滤；指令结果写日志",
            "v2.64：电池平台四选一（极驰锐动/哈啰/鑫能出行/自定义API）",
            "v2.63：多平台电池支持，非极驰平台走自定义URL+Token",
            "v2.62：AI智能分析附最近3次校准历史，日志选择近1/3小时/今天",
            "v2.61：校准GPS精度门槛放宽、误差容忍8米、去重0.3米",
            "v2.60：校准采样距离目标10米、可提前5米结束",
            "v2.59：校准流程GPS距离实时显示（替代信号估算）",
            "v2.58：校准步骤完善：标定-采样-拟合-验证闭环",
            "v2.57：一键诊断日志，上传GitHub",
            "v2.56：日志本地导出修复",
            "v2.55：AI智能调节接入硅基流动等主流API",
            "v2.54：校准日志独立存储并上传GitHub",
            "v2.53：通知栏快捷按钮优化",
            "v2.52：通知栏常驻4按钮（开机/关机/坐桶/自动）",
            "v2.51：桌面小部件电量百分比显示",
            "v2.50：校准结果可保存为场景，自动场景识别",
            "v2.49：场景模式，一键保存校准为场景",
            "v2.48：更新弹窗显示变更摘要",
            "v2.47：更新下载进度条与百分比显示",
            "v2.46：自动更新支持多镜像地址",
            "v2.45：内置更新，版本号与关于页同步",
            "v2.44：更新下载修复",
            "v2.43：校准快速模式/自动应用/地点标注",
            "v2.42：校准历史最近5次可回退",
            "v2.41：诊断日志RSSI采样记录",
            "v2.40：签名统一，支持覆盖安装",
            "v2.39：校准结果应用修复",
            "v2.38：AI智能调节参数分析",
            "v2.37：自动开机后电量全端刷新",
            "v2.36：车辆动画（大灯光束/双闪/坐桶弹开）",
            "v2.35：镂空车图控制页面",
            "v2.34：桌面小部件刷新按钮",
            "v2.33：小部件电量百分比显示",
            "v2.32：AI智能调参（硅基流动Key）",
            "v2.31：一键诊断日志导出",
            "v2.30：日志上传GitHub",
            "v2.29：校准向导式流程",
            "v2.28：校准后自动刷新参数",
            "v2.27：智能校准GPS标定",
            "v2.26：校准日志详情",
            "v2.25：通知栏电量大字彩色显示",
            "v2.24：桌面小部件多尺寸",
            "v2.23：自动更新下载进度",
            "v2.22：内置软件自动更新",
            "v2.21：车辆状态查询",
            "v2.20：副账号登录支持",
            "v2.13：日志导出分享、Tab转场动画",
            "v2.12：趋势检测+连续达标判定、震动反馈",
    };

    /** v2.66: 关于弹窗：动态版本号 + 更新日志 + 耗电隐私说明（R15/R16） */
    private void showAboutDialog() {
        String ver = "?";
        try {
            ver = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        final String verFinal = ver;
        StringBuilder sb = new StringBuilder();
        sb.append("小牛靠近开机助手 v").append(ver).append("\n\n");
        sb.append("蓝牙测距 + 官方远程命令\n副账号可用，无需蓝牙连接\n自动开关机：边沿触发防重复\n\n");
        sb.append("📊 更新日志（最近 8 版）\n");
        int n = Math.min(8, CHANGELOG.length);
        for (int i = 0; i < n; i++) sb.append("• ").append(CHANGELOG[i]).append("\n");
        sb.append("\n💡 耗电与隐私说明\n");
        sb.append("• 后台蓝牙扫描用于靠近检测，仅在自动模式开启时运行\n");
        sb.append("• 定位权限仅在校准时用于距离计算，不上传位置\n");
        sb.append("• 诊断日志仅在你主动上传时才会发往 GitHub（token 仅存本机）\n");
        sb.append("• 车辆电量经你授权的平台接口读取，Token 保存在本机");
        new AlertDialog.Builder(act)
                .setTitle("关于")
                .setMessage(sb.toString())
                .setPositiveButton("确定", null)
                .setNegativeButton("查看全部更新日志", (d, w) -> {
                    StringBuilder all = new StringBuilder();
                    for (String line : CHANGELOG) all.append("• ").append(line).append("\n");
                    new AlertDialog.Builder(act)
                            .setTitle("全部更新日志（v2.13~v" + verFinal + "）")
                            .setMessage(all.toString())
                            .setPositiveButton("关闭", null)
                            .show();
                })
                .show();
    }

    /** v2.22: 软件更新 ========== */

    private void doCheckUpdate() {
        String url = etUpdateUrl.getText().toString().trim();
        if (!url.isEmpty()) act.settings.setUpdateUrl(url);
        // v2.33: 空地址时 Updater 自动用内置多镜像地址
        btnCheckUpdate.setText("检查中...");
        btnCheckUpdate.setEnabled(false);

        Updater.checkUpdate(act, url, new Updater.CheckCallback() {
            @Override
            public void onResult(Updater.UpdateInfo info) {
                btnCheckUpdate.setText("检查更新");
                btnCheckUpdate.setEnabled(true);
                act.settings.setLastUpdateCheck(System.currentTimeMillis());

                if (info.hasUpdate) {
                    showUpdateDialog(info);
                } else {
                    Toast.makeText(act, "已是最新版本（v" + info.versionName + "）", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String msg) {
                btnCheckUpdate.setText("检查更新");
                btnCheckUpdate.setEnabled(true);
                Toast.makeText(act, "检查失败: " + msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showUpdateDialog(Updater.UpdateInfo info) {
        String msg = "新版本: v" + info.versionName + " (build " + info.versionCode + ")\n\n";
        if (info.releaseNotes != null && !info.releaseNotes.isEmpty()) {
            msg += "更新内容:\n" + info.releaseNotes;
        }
        View dv = LayoutInflater.from(act).inflate(R.layout.dialog_update, null);
        TextView tvMsg = dv.findViewById(R.id.tv_dialog_msg);
        ProgressBar pb = dv.findViewById(R.id.pb_dialog_download);
        TextView tvPct = dv.findViewById(R.id.tv_dialog_percent);
        tvMsg.setText(msg);
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("发现新版本")
                .setView(dv)
                .setPositiveButton("立即更新", null)
                .setNegativeButton("稍后", null)
                .setCancelable(false)
                .create();
        dialog.show();
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            v.setEnabled(false);
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setEnabled(false);
            pb.setVisibility(View.VISIBLE);
            tvPct.setVisibility(View.VISIBLE);
            tvPct.setText("正在连接下载… 0%");
            Updater.downloadApk(act, info.downloadUrl, new Updater.DownloadCallback() {
                @Override
                public void onProgress(int percent) {
                    btnCheckUpdate.setText("下载中 " + percent + "%");
                    pb.setProgress(percent);
                    tvPct.setText("下载中 " + percent + "%");
                }

                @Override
                public void onComplete(java.io.File apkFile) {
                    btnCheckUpdate.setText("检查更新");
                    btnCheckUpdate.setEnabled(true);
                    if (dialog.isShowing()) dialog.dismiss();
                    Toast.makeText(act, "下载完成，开始安装", Toast.LENGTH_SHORT).show();
                    Updater.installApk(act, apkFile);
                }

                @Override
                public void onError(String emsg) {
                    btnCheckUpdate.setText("检查更新");
                    btnCheckUpdate.setEnabled(true);
                    v.setEnabled(true);
                    dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setEnabled(true);
                    pb.setVisibility(View.GONE);
                    tvPct.setText("下载失败: " + emsg);
                }
            });
        });
    }
    /** v2.49: 场景管理弹窗：点击场景弹出应用/删除选项 */
    private void showSceneManager() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray(act.settings.getScenes());
            if (arr.length() == 0) {
                Toast.makeText(act, "暂无场景，请先完成一次智能校准并点「保存为场景」", Toast.LENGTH_LONG).show();
                return;
            }
            String active = act.settings.getActiveSceneName();
            String[] items = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                String mark = o.optString("name","").equals(active) ? "● " : "  ";
                items[i] = mark + o.optString("name","") + "  [" + o.optInt("near",-60) + "/" + o.optInt("far",-80) + "dBm]";
            }
            final org.json.JSONArray farr = arr;
            new AlertDialog.Builder(act)
                .setTitle("场景管理（点击场景操作）")
                .setItems(items, (d, which) -> {
                    final int idx = which;
                    String name = farr.optJSONObject(which).optString("name","");
                    new AlertDialog.Builder(act)
                        .setTitle("场景：" + name)
                        .setItems(new String[]{"应用此场景", "删除此场景"}, (dd, w) -> {
                            if (w == 0) {
                                if (act.settings.applyScene(idx)) {
                                    Toast.makeText(act, "已切换: " + name, Toast.LENGTH_SHORT).show();
                                    refreshUi();
                                }
                            } else if (w == 1) {
                                act.settings.deleteScene(idx);
                                Toast.makeText(act, "已删除: " + name, Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
                })
                .setNegativeButton("关闭", null)
                .show();
        } catch (Exception e) {
            Toast.makeText(act, "场景读取失败", Toast.LENGTH_SHORT).show();
        }
    }
    /** 校准历史弹窗：最近5次，支持一键回退 */
    private void showCalibHistory() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray(act.settings.getCalibrationHistory());
            if (arr.length() == 0) {
                Toast.makeText(act, "暂无校准历史", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] items = new String[arr.length()];
            final int[] nearVals = new int[arr.length()];
            final int[] farVals = new int[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                long t = o.optLong("time", 0);
                String day = calibDay(t);
                String place = o.optString("place", "");
                String err = o.optString("error", "");
                String env = o.optString("environment", "");
                int near = o.optInt("near", -60);
                int far = o.optInt("far", -80);
                nearVals[i] = near; farVals[i] = far;
                String tag = place.isEmpty() ? "标准" : place;
                String qual = err.isEmpty() ? "未验证" : ("误差" + err + "米");
                items[i] = day + " · " + tag + " · 靠" + near + "/离" + far + " · " + qual
                        + (env.isEmpty() ? "" : (" · " + env));
            }
            new androidx.appcompat.app.AlertDialog.Builder(act)
                    .setTitle("校准历史（点击回退）")
                    .setItems(items, (d, w) -> {
                        act.settings.setRssiThreshold(nearVals[w]);
                        act.settings.setRssiLeaveThreshold(farVals[w]);
                        refreshUi();
                        Toast.makeText(act, "已回退到该次校准参数", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(act, "历史读取失败", Toast.LENGTH_SHORT).show();
        }
    }

    private static String calibDay(long t) {
        if (t <= 0) return "长期";
        long days = (System.currentTimeMillis() - t) / (24L * 3600L * 1000L);
        if (days <= 0) return "今天";
        if (days == 1) return "昨天";
        if (days < 7) return days + "天前";
        return (days / 7) + "周前";
    }
}
