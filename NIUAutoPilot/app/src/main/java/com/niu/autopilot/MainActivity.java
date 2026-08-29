package com.niu.autopilot;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 小牛靠近开机助手 v2.1 — 三Tab主界面
 */
public class MainActivity extends AppCompatActivity {
    private static final int REQ_PERM = 1001;

    public Settings settings;
    public NiuHttpClient http;
    public String vehicleState = "未知";
    public boolean autoModeRunning = false;
    public int currentRssi = 0;
    public boolean rssiValid = false;
    public final StringBuilder logText = new StringBuilder();

    private ControlFragment controlFragment;
    private SettingsFragment settingsFragment;
    private MyFragment myFragment;
    private Fragment activeFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        settings = new Settings(this);
        applyTheme(settings.getThemeMode());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        http = new NiuHttpClient(this);

        controlFragment = new ControlFragment();
        settingsFragment = new SettingsFragment();
        myFragment = new MyFragment();

        getSupportFragmentManager().beginTransaction()
                .add(R.id.fragment_container, controlFragment, "control")
                .add(R.id.fragment_container, settingsFragment, "settings").hide(settingsFragment)
                .add(R.id.fragment_container, myFragment, "my").hide(myFragment)
                .commit();
        activeFragment = controlFragment;

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            Fragment target;
            if (item.getItemId() == R.id.nav_control) target = controlFragment;
            else if (item.getItemId() == R.id.nav_settings) target = settingsFragment;
            else target = myFragment;
            if (target != activeFragment) {
                getSupportFragmentManager().beginTransaction()
                        .hide(activeFragment).show(target).commit();
                activeFragment = target;
                if (target instanceof ControlFragment) ((ControlFragment) target).refreshUi();
                if (target instanceof SettingsFragment) ((SettingsFragment) target).refreshUi();
                if (target instanceof MyFragment) ((MyFragment) target).refreshUi();
            }
            return true;
        });

        BleService.setUiListener(uiListener);
        checkPermissions();
        showFirstTimeXiaomiTip();
    }

    private final BleService.UiListener uiListener = new BleService.UiListener() {
        @Override public void onServiceLog(String line) {
            runOnUiThread(() -> appendLog(line));
        }
        @Override public void onServiceStatus(String status) {
            runOnUiThread(() -> {
                // status 格式如 "车辆信号 RSSI=-53 ≈1米" 或 "已靠近..."
                if (status.contains("RSSI=")) {
                    try {
                        int idx = status.indexOf("RSSI=");
                        int end = status.indexOf(" ", idx);
                        if (end < 0) end = status.length();
                        currentRssi = Integer.parseInt(status.substring(idx + 5, end).trim());
                        rssiValid = true;
                    } catch (Exception ignored) {}
                }
                if (activeFragment instanceof ControlFragment) ((ControlFragment) activeFragment).refreshUi();
            });
        }
    };

    public void appendLog(String line) {
        logText.append("\n").append(line);
        if (logText.length() > 12000) logText.delete(0, logText.length() - 8000);
        if (activeFragment instanceof ControlFragment) ((ControlFragment) activeFragment).onLogUpdated();
    }

    public void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    /** RSSI转大概距离（BleService也调用此静态方法） */
    public static String rssiToMeter(int rssi) {
        if (rssi >= -45) return "约0.5米";
        if (rssi >= -52) return "约1米";
        if (rssi >= -58) return "约2米";
        if (rssi >= -64) return "约3米";
        if (rssi >= -70) return "约5米";
        if (rssi >= -76) return "约8米";
        if (rssi >= -82) return "约12米";
        return "15米以上";
    }

    /** 应用主题模式：0=跟随系统，1=浅色，2=深色 */
    public static void applyTheme(int mode) {
        switch (mode) {
            case 1: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO); break;
            case 2: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES); break;
            default: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    /** 切换主题并重启Activity */
    public void changeTheme(int mode) {
        settings.setThemeMode(mode);
        applyTheme(mode);
        recreate();
    }

    /** 发送远程命令（控制页用） */
    public void sendCommand(String type, String label, Runnable onSuccess) {
        String account = settings.getAccount(), password = settings.getPassword(), sn = settings.getSn();
        if (account.isEmpty() || password.isEmpty() || sn.isEmpty()) {
            toast("请先在「我的」页填写账号/密码/SN");
            return;
        }
        appendLog(">>> 发送" + label + " ...");
        http.sendCommand(account, password, sn, type, (ok, msg) -> runOnUiThread(() -> {
            appendLog((ok ? "[OK] " : "[失败] ") + label + " -> " + msg);
            if (ok) {
                toast(label + "成功");
                if ("acc_on".equals(type)) { vehicleState = "已开机"; settings.setVehicleState("已开机"); }
                if ("acc_off".equals(type)) { vehicleState = "已关机"; settings.setVehicleState("已关机"); }
                if (onSuccess != null) onSuccess.run();
                if (activeFragment instanceof ControlFragment) ((ControlFragment) activeFragment).refreshUi();
            } else {
                toast(label + "失败: " + msg);
            }
        }));
    }

    /** 查询车辆状态 */
    public void queryState() {
        String account = settings.getAccount(), password = settings.getPassword(), sn = settings.getSn();
        if (account.isEmpty() || password.isEmpty() || sn.isEmpty()) {
            toast("请先在「我的」页填写账号/密码/SN");
            return;
        }
        appendLog(">>> 查询车辆状态 ...");
        http.queryAccState(account, password, sn, (ok, msg) -> runOnUiThread(() -> {
            if (ok) {
                vehicleState = "0".equals(msg) ? "已关机" : ("1".equals(msg) ? "已开机" : msg);
                settings.setVehicleState(vehicleState);
                appendLog("车辆当前状态: " + vehicleState);
            } else {
                appendLog("状态查询失败: " + msg);
            }
            if (activeFragment instanceof ControlFragment) ((ControlFragment) activeFragment).refreshUi();
        }));
    }

    /** 启动/停止自动模式 */
    public void toggleAutoMode() {
        if (autoModeRunning) {
            stopService(new Intent(this, BleService.class));
            autoModeRunning = false;
            settings.setAutoModeRunning(false);
            rssiValid = false;
            appendLog("自动模式已停止");
        } else {
            String account = settings.getAccount(), password = settings.getPassword(), sn = settings.getSn();
            if (account.isEmpty() || password.isEmpty() || sn.isEmpty()) {
                toast("请先在「我的」页填写账号/密码/SN");
                return;
            }
            if (settings.getDeviceMac().isEmpty()) {
                toast("请先在「设置」页绑定车辆蓝牙");
                return;
            }
            if (!settings.isAutoOn() && !settings.isAutoOff()) {
                toast("请至少勾选 靠近自动开机 或 离开自动关机");
                return;
            }
            startForegroundServiceCompat();
            autoModeRunning = true;
            settings.setAutoModeRunning(true);
            appendLog("自动模式已启动，监听: " + settings.getDeviceName());
        }
        if (activeFragment instanceof ControlFragment) ((ControlFragment) activeFragment).refreshUi();
    }

    @SuppressWarnings("deprecation")
    private void startForegroundServiceCompat() {
        Intent i = new Intent(this, BleService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    /** 首次安装弹窗：小米保活提示 */
    private void showFirstTimeXiaomiTip() {
        if (settings.getSp().getBoolean("xiaomi_tip_shown", false)) return;
        settings.getSp().edit().putBoolean("xiaomi_tip_shown", true).apply();

        View v = getLayoutInflater().inflate(R.layout.dialog_log, null);
        // 复用dialog_log布局不太合适，直接用消息弹窗
        new AlertDialog.Builder(this)
                .setTitle("小米/红米后台保活设置（必做）")
                .setMessage("为了让自动模式在后台一直运行，请完成以下3项设置：\n\n"
                        + "① 省电策略 → 设为「无限制」\n"
                        + "② 自启动 → 允许本App自启动\n"
                        + "③ 锁后台 → 最近任务里下拉本App点锁\n\n"
                        + "点击下方按钮可直接跳转到对应设置页。")
                .setPositiveButton("① 省电策略", (d, w) -> openBatteryOptimization())
                .setNeutralButton("② 自启动", (d, w) -> openAutoStart())
                .setNegativeButton("稍后再说", null)
                .show();
    }

    private void openBatteryOptimization() {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"));
            intent.putExtra("package_name", getPackageName());
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                startActivity(intent);
            } catch (Exception e2) {
                toast("请手动：设置→应用→本App→省电策略→无限制");
            }
        }
    }

    private void openAutoStart() {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e2) {
                toast("请手动：手机管家→应用管理→权限→自启动");
            }
        }
    }

    /** 显示日志弹窗 */
    public void showLogDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_log, null);
        TextView tv = v.findViewById(R.id.tv_log_content);
        tv.setText(logText.length() == 0 ? "(暂无日志)" : logText.toString());
        AlertDialog dlg = new AlertDialog.Builder(this).setView(v).create();
        v.findViewById(R.id.btn_close_log).setOnClickListener(x -> dlg.dismiss());
        dlg.show();
    }

    private void checkPermissions() {
        String[] need;
        if (Build.VERSION.SDK_INT >= 31) {
            need = new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS};
        } else {
            need = new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
        }
        boolean all = true;
        for (String p : need) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) { all = false; break; }
        }
        if (!all) ActivityCompat.requestPermissions(this, need, REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) toast("权限已处理，请确认蓝牙与定位权限已允许");
    }
}
