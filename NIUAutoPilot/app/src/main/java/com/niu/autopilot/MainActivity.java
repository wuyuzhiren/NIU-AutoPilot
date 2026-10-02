package com.niu.autopilot;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
 * 小牛靠近开机助手 v2.13 — 三Tab主界面
 * v2.13 新增：命令 done 回调 + 发送后状态回读确认 + 日志导出分享 + Tab 转场动画
 */
public class MainActivity extends AppCompatActivity {
    private static final int REQ_PERM = 1001;

    /** 命令完成回调（主线程） */
    public interface CommandDone {
        void onDone(boolean ok, String msg);
    }

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
                // v2.13: Tab 切换淡入淡出
                getSupportFragmentManager().beginTransaction()
                        .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
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

        // 看门狗拉起服务后，同步内存中的自动模式状态
        autoModeRunning = settings.isAutoModeRunning();

        // v2.16: 扫码导入 token（niubattery://token?value=xxx）
        handleTokenIntent(getIntent());

        // v2.22: 启动时自动检查更新（6小时间隔）
        autoCheckUpdateOnStart();
    }

    /** v2.22: 启动时自动检查更新 */
    private void autoCheckUpdateOnStart() {
        try {
            if (!settings.isAutoCheckUpdate()) return;
            String url = settings.getUpdateUrl();
            if (url == null || url.trim().isEmpty()) return;
            long lastCheck = settings.getLastUpdateCheck();
            if (lastCheck > 0 && System.currentTimeMillis() - lastCheck < 21600000) return; // 6小时

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Updater.checkUpdate(this, url, new Updater.CheckCallback() {
                    @Override
                    public void onResult(Updater.UpdateInfo info) {
                        settings.setLastUpdateCheck(System.currentTimeMillis());
                        if (info.hasUpdate) {
                            showUpdatePrompt(info);
                        }
                    }
                    @Override
                    public void onError(String msg) { /* 静默失败，不打扰用户 */ }
                });
            }, 3000); // 延迟3秒，不影响启动速度
        } catch (Exception ignored) {}
    }

    /** v2.22: 显示更新提示对话框 */
    private void showUpdatePrompt(Updater.UpdateInfo info) {
        String msg = "新版本: v" + info.versionName + "\n\n";
        if (info.releaseNotes != null && !info.releaseNotes.isEmpty()) {
            msg += "更新内容:\n" + info.releaseNotes;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("发现新版本")
                .setMessage(msg)
                .setPositiveButton("立即更新", (d, w) -> {
                    android.widget.Toast.makeText(this, "开始下载更新...", android.widget.Toast.LENGTH_SHORT).show();
                    Updater.downloadApk(this, info.downloadUrl, new Updater.DownloadCallback() {
                        @Override public void onProgress(int percent) {}
                        @Override public void onComplete(java.io.File apkFile) {
                            android.widget.Toast.makeText(MainActivity.this, "下载完成，开始安装", android.widget.Toast.LENGTH_SHORT).show();
                            Updater.installApk(MainActivity.this, apkFile);
                        }
                        @Override public void onError(String msg) {
                            android.widget.Toast.makeText(MainActivity.this, "下载失败: " + msg, android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setNegativeButton("稍后", null)
                .setCancelable(false)
                .show();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleTokenIntent(intent);
    }

    /** v2.16: 处理扫码导入 token 的 URL Scheme */
    private void handleTokenIntent(android.content.Intent intent) {
        if (intent == null || intent.getData() == null) return;
        android.net.Uri uri = intent.getData();
        if (!"niubattery".equals(uri.getScheme()) || !"token".equals(uri.getHost())) return;
        String token = uri.getQueryParameter("value");
        if (token == null || token.trim().isEmpty()) {
            android.widget.Toast.makeText(this, "二维码中未找到 token", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        token = token.trim();
        settings.setBatteryToken(token);
        android.widget.Toast.makeText(this, "Token 已导入，正在同步电量…", android.widget.Toast.LENGTH_LONG).show();
        appendLog("扫码导入极驰锐动 token（" + token.length() + " 字符）");
        // 自动同步电量
        new NiuBatteryClient(this).queryBattery((ok, percent, msg) -> runOnUiThread(() -> {
            android.widget.Toast.makeText(this, ok ? ("电量同步成功：" + percent + "%") : ("同步失败：" + msg), android.widget.Toast.LENGTH_LONG).show();
            if (activeFragment instanceof ControlFragment) ((ControlFragment) activeFragment).refreshUi();
            if (activeFragment instanceof SettingsFragment) ((SettingsFragment) activeFragment).refreshUi();
        }));
        // 切到设置页让用户看到结果
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        if (nav != null) nav.setSelectedItemId(R.id.nav_settings);
    }

    private final BleService.UiListener uiListener = new BleService.UiListener() {
        @Override public void onServiceLog(String line) {
            runOnUiThread(() -> appendLog(line));
        }
        @Override public void onServiceStatus(String status) {
            runOnUiThread(() -> {
                // v2.21: 修复信号解析——只有"信号 X dBm"格式才解析，其他状态重置rssiValid
                if (status.contains("信号") && status.contains("dBm")) {
                    try {
                        int idx = status.indexOf("信号") + 2;
                        String sub = status.substring(idx).trim();
                        StringBuilder num = new StringBuilder();
                        for (char ch : sub.toCharArray()) {
                            if ((ch >= '0' && ch <= '9') || ch == '-' || ch == '.') num.append(ch);
                            else if (num.length() > 0) break;
                        }
                        currentRssi = (int) Float.parseFloat(num.toString());
                        rssiValid = true;
                    } catch (Exception ignored) {
                        rssiValid = false;
                    }
                } else {
                    rssiValid = false;
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

    /** RSSI转大概距离（BleService/通知栏/小部件也调用此静态方法）
     *  v2.71: 档位保守化——BLE穿墙/楼上场景衰减大，避免把远距离误显示成近距 */
    public static String rssiToMeter(int rssi) {
        if (rssi >= -45) return "约1米";
        if (rssi >= -52) return "约2米";
        if (rssi >= -58) return "约3米";
        if (rssi >= -64) return "约5米";
        if (rssi >= -70) return "约8米";
        if (rssi >= -76) return "约12米";
        if (rssi >= -82) return "约15米";
        return "20米以上";
    }

    /** 应用主题模式：0=跟随系统，1=浅色，2=深色 */
    public static void applyTheme(int mode) {
        switch (mode) {
            case 1: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO); break;
            case 2: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES); break;
            default: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    /** v2.2 主题感知：判断当前是否浅色模式（供 VehicleStageView/硬编码色使用） */
    public static boolean isLightThemeActive(Context ctx) {
        int mode = new Settings(ctx).getThemeMode();
        if (mode == 1) return true;
        if (mode == 2) return false;
        int uiMode = ctx.getResources().getConfiguration().uiMode;
        return (uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_NO;
    }

    /** 切换主题并重启Activity */
    public void changeTheme(int mode) {
        settings.setThemeMode(mode);
        applyTheme(mode);
        recreate();
    }

    /** 发送远程命令（控制页用，兼容旧调用） */
    public void sendCommand(String type, String label, Runnable onSuccess) {
        sendCommand(type, label, onSuccess, null);
    }

    /**
     * 发送远程命令（v2.13：带完成回调）
     * onDone 在命令最终结束（成功或失败）后主线程回调，用于恢复 loading。
     * 成功后延迟 4 秒回读车辆状态确认（3.2）。
     */
    public void sendCommand(String type, String label, Runnable onSuccess, CommandDone onDone) {
        String account = settings.getAccount(), password = settings.getPassword(), sn = settings.getSn();
        if (account.isEmpty() || password.isEmpty() || sn.isEmpty()) {
            toast("请先在「我的」页填写账号/密码/SN");
            if (onDone != null) onDone.onDone(false, "账号未配置");
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
                // v2.13: 发送后回读状态确认
                scheduleStateReadback(type);
            } else {
                toast(label + "失败: " + msg);
            }
            if (activeFragment instanceof ControlFragment) ((ControlFragment) activeFragment).refreshUi();
            if (onDone != null) onDone.onDone(ok, msg);
        }));
    }

    /** 命令发送成功后延迟 4 秒回读车辆状态，不一致则提示"状态未知" */
    private void scheduleStateReadback(String type) {
        String expected = "acc_on".equals(type) ? "1" : ("acc_off".equals(type) ? "0" : null);
        if (expected == null) return;
        final String exp = expected;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            String account = settings.getAccount(), password = settings.getPassword(), sn = settings.getSn();
            if (account.isEmpty() || password.isEmpty() || sn.isEmpty()) return;
            http.queryAccState(account, password, sn, (ok, msg) -> runOnUiThread(() -> {
                if (!ok) { appendLog("[回读] 状态查询失败: " + msg); return; }
                if (exp.equals(msg)) {
                    appendLog("[回读] 状态确认: " + ("1".equals(exp) ? "已开机" : "已关机"));
                } else {
                    appendLog("[回读] ⚠ 命令已发送但状态未确认（期望" +
                            ("1".equals(exp) ? "开机" : "关机") + "，实际" +
                            ("1".equals(msg) ? "开机" : "关机") + "）");
                    vehicleState = "状态未知";
                    settings.setVehicleState("状态未知");
                }
                if (activeFragment instanceof ControlFragment) ((ControlFragment) activeFragment).refreshUi();
            }));
        }, 4000);
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

    /** 显示日志弹窗（v2.13 增加导出分享；v2.66 增加关键词检索 R6） */
    public void showLogDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_log, null);
        TextView tv = v.findViewById(R.id.tv_log_content);
        android.widget.EditText etFilter = v.findViewById(R.id.et_log_filter);
        tv.setText(logText.length() == 0 ? "(暂无日志)" : logText.toString());
        // v2.66: 实时关键词过滤（不区分大小写，命中即显示整行）
        etFilter.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                String kw = s.toString().trim().toLowerCase();
                if (kw.isEmpty()) {
                    tv.setText(logText.length() == 0 ? "(暂无日志)" : logText.toString());
                    return;
                }
                StringBuilder sb = new StringBuilder();
                String full = logText.toString();
                for (String line : full.split("\n")) {
                    if (line.toLowerCase().contains(kw)) sb.append(line).append("\n");
                }
                tv.setText(sb.length() == 0 ? "未找到包含「" + kw + "」的日志" : sb.toString());
            }
        });
        AlertDialog dlg = new AlertDialog.Builder(this).setView(v).create();
        v.findViewById(R.id.btn_close_log).setOnClickListener(x -> dlg.dismiss());
        v.findViewById(R.id.btn_export_log).setOnClickListener(x -> exportLog());
        dlg.show();
    }

    /** 日志导出：以文本形式分享给任意应用（微信/邮箱/文件管理器均可保存） */
    private void exportLog() {
        if (logText.length() == 0) { toast("暂无日志可导出"); return; }
        try {
            String content = "小牛靠近开机助手 运行日志\n导出时间: " +
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                            .format(new java.util.Date()) + "\n\n" + logText;
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, "小牛靠近开机助手日志");
            share.putExtra(Intent.EXTRA_TEXT, content);
            startActivity(Intent.createChooser(share, "分享日志"));
        } catch (Exception e) {
            toast("导出失败: " + e.getMessage());
        }
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
