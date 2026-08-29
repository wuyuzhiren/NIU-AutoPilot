package com.niu.autopilot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;

/**
 * 前台服务（v2.6）：
 * 后台定时扫描车辆蓝牙获取 RSSI → 边沿触发判定靠近/离开 → 通过 HTTP 发送开机/关机。
 * 串行循环：扫描1.5秒 → 判定 → 等待间隔 → 再扫描，避免重叠丢数据。
 */
public class BleService extends Service {
    private static final String CHANNEL_ID = "niu_ble_channel";
    private static final int NOTIF_ID = 1001;
    public static final String ACTION_STOP = "com.niu.autopilot.STOP";
    private static final long SCAN_DURATION_MS = 1500; // 单次扫描1.5秒

    private NiuBleManager ble;
    private NiuHttpClient http;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> lastRssi = new HashMap<>();
    private final Map<String, BluetoothDevice> lastDevices = new HashMap<>();
    private long lastAutoOnTime = 0;
    private long lastAutoOffTime = 0;
    private boolean nearFired = false;
    private boolean farFired = false;
    private boolean runLoop = false;
    private boolean httpBusy = false;

    public interface UiListener {
        void onServiceLog(String line);
        void onServiceStatus(String status);
    }
    private static UiListener uiListener;
    public static void setUiListener(UiListener l) { uiListener = l; }

    private void uiLog(String s) { if (uiListener != null) uiListener.onServiceLog(s); }
    private void uiStatus(String s) { if (uiListener != null) uiListener.onServiceStatus(s); }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForegroundCompat();
        http = new NiuHttpClient(this);
        ble = new NiuBleManager(this, new NiuBleManager.Callback() {
            @Override public void onScanResult(BluetoothDevice device, String name, int rssi) {
                lastRssi.put(device.getAddress(), rssi);
                lastDevices.put(device.getAddress(), device);
            }
            @Override public void onScanState(boolean scanning) {}
            @Override public void onLog(String line) { uiLog(line); }
        });
    }

    @SuppressWarnings("deprecation")
    private void startForegroundCompat() {
        Notification n = buildNotification("靠近自动开关机服务运行中");
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } catch (Exception e) {
                startForeground(NOTIF_ID, n);
            }
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, BleService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("小牛靠近开机助手")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(openPi)
                .addAction(0, "停止", stopPi)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "靠近开关机服务",
                NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!runLoop) {
            runLoop = true;
            handler.post(loopRunnable);
        }
        uiStatus("自动模式已启动");
        return START_STICKY;
    }

    private final Runnable loopRunnable = new Runnable() {
        @Override
        public void run() {
            if (!runLoop) return;
            try {
                tick();
            } catch (Exception e) {
                uiLog("循环异常: " + e.getMessage());
                scheduleNext();
            }
        }
    };

    private void scheduleNext() {
        long interval = Math.max(1, new Settings(this).getScanIntervalSec()) * 1000L;
        handler.postDelayed(loopRunnable, interval);
    }

    @Override
    public void onDestroy() {
        runLoop = false;
        handler.removeCallbacksAndMessages(null);
        if (ble != null) ble.stopScan();
        uiStatus("自动模式已停止");
        super.onDestroy();
    }

    private void tick() {
        Settings s = new Settings(this);
        if (!ble.isBluetoothOn()) { uiStatus("蓝牙未开启"); scheduleNext(); return; }
        if (s.getDeviceMac().isEmpty()) {
            uiStatus("未选择车辆蓝牙，请先在小牛蓝牙列表里选择");
            scheduleNext();
            return;
        }
        lastRssi.clear();
        lastDevices.clear();
        ble.startScan();
        handler.postDelayed(this::decide, SCAN_DURATION_MS);
    }

    private void decide() {
        ble.stopScan();
        Settings s = new Settings(this);
        String target = s.getDeviceMac().toLowerCase();
        int best = Integer.MIN_VALUE;
        for (Map.Entry<String, Integer> e : lastRssi.entrySet()) {
            if (e.getKey().toLowerCase().equals(target) && e.getValue() > best) {
                best = e.getValue();
            }
        }
        if (best == Integer.MIN_VALUE) {
            uiStatus("未发现车辆蓝牙信号（目标 " + s.getDeviceName() + "）");
            scheduleNext();
            return;
        }
        uiStatus("车辆信号 RSSI=" + best + " ≈ " + MainActivity.rssiToMeter(best));

        long now = System.currentTimeMillis();
        int nearThreshold = s.getRssiThreshold();
        int farThreshold = s.getRssiLeaveThreshold();
        long cooldown = s.getAutoOnCooldownSec() * 1000L;

        if (best >= nearThreshold) {
            farFired = false;
            if (s.isAutoOn() && !nearFired) {
                if ((now - lastAutoOnTime) > cooldown) {
                    lastAutoOnTime = now;
                    nearFired = true;
                    uiLog("已靠近(RSSI=" + best + ")，发送开机...");
                    sendHttp(s, Settings.CMD_POWER_ON, "开机");
                }
            } else if (nearFired) {
                uiLog("已在范围内(RSSI=" + best + ")，不重复开机");
            }
        } else if (best < farThreshold) {
            nearFired = false;
            if (s.isAutoOff() && !farFired) {
                if ((now - lastAutoOffTime) > cooldown) {
                    lastAutoOffTime = now;
                    farFired = true;
                    uiLog("已远离(RSSI=" + best + ")，发送关机...");
                    sendHttp(s, Settings.CMD_POWER_OFF, "关机");
                }
            }
        } else {
            uiLog("滞回区间(RSSI=" + best + ")，保持当前状态");
        }
        scheduleNext();
    }

    private void sendHttp(Settings s, String type, String label) {
        if (httpBusy) { uiLog(label + "命令发送中，跳过本次"); return; }
        httpBusy = true;
        http.sendCommand(s.getAccount(), s.getPassword(), s.getSn(), type, (ok, msg) -> {
            httpBusy = false;
            uiLog((ok ? "[OK] " : "[失败] ") + label + " -> " + msg);
        });
    }
}
