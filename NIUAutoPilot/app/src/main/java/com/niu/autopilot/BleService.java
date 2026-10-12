package com.niu.autopilot;

import android.app.AlarmManager;
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
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

public class BleService extends Service {
    private static final String CHANNEL_ID = "niu_ble_channel_v3";
    private static final int NOTIF_ID = 1001;
    public static final String ACTION_STOP = "com.niu.autopilot.STOP";
    private static final long MIN_SCAN_DURATION_MS = 300;
    private static final long WATCHDOG_INTERVAL_MS = 2 * 60 * 1000;
    /** v2.13: 离开后自动关机确认宽限期（毫秒），信号恢复则取消 */
    private static final long AUTO_OFF_CONFIRM_MS = 60 * 1000;
    /** v2.65: 信号保活窗口（毫秒）：锁车后设备可能 10~20 秒才广播一次，此窗口内沿用旧值判定，不打断触发 */
    private static final long KEEP_SIGNAL_MS = 15 * 1000;
    /** v2.81: 扫描停摆自愈——超过该时长完全收不到目标信号（后台限流导致扫描静默失效但 isScanning 仍为 true），强制重启扫描 */
    private static final long SCAN_STALL_RESTART_MS = 45 * 1000;

    private NiuBleManager ble;
    private NiuHttpClient http;
    private CommandExecutor commandExecutor;
    private SignalFilter signalFilter;
    private TrendDetector trendDetector = new TrendDetector();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> lastRssi = new HashMap<>();
    private final Map<String, BluetoothDevice> lastDevices = new HashMap<>();

    private long lastAutoOnTime = 0;
    private long lastAutoOffTime = 0;
    private boolean nearFired = false;
    private boolean farFired = false;
    private boolean runLoop = false;
    /** v2.65: 目标设备最后收到信号的时间与强度（用于信号保活，应对锁车后慢广播） */
    private long lastSignalTime = 0;
    private int lastBestRssi = Integer.MIN_VALUE;

    private PowerManager.WakeLock wakeLock;
    private AlarmManager alarmManager;
    private PendingIntent watchdogPi;
    private String lastStatus = "自动模式运行中";
    private int tickCount = 0;

    public interface UiListener {
        void onServiceLog(String line);
        void onServiceStatus(String status);
    }
    private static UiListener uiListener;
    private String lastDiagUploadDate = ""; // v2.38: 避免同一天重复上传
    public static void setUiListener(UiListener l) { uiListener = l; }

    private void uiLog(String s) { if (uiListener != null) uiListener.onServiceLog(s); }
    private void uiStatus(String s) {
        lastStatus = s;
        if (uiListener != null) uiListener.onServiceStatus(s);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForegroundCompat();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NIUAutoPilot:BleService");
        wakeLock.setReferenceCounted(false);
        if (!wakeLock.isHeld()) wakeLock.acquire();

        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent watchdogIntent = new Intent(this, WatchdogReceiver.class);
        watchdogIntent.setAction(WatchdogReceiver.ACTION_WATCHDOG);
        watchdogPi = PendingIntent.getBroadcast(this, 0, watchdogIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        scheduleWatchdog();

        http = new NiuHttpClient(this);
        commandExecutor = new CommandExecutor(this, http);
        commandExecutor.start();

        // v2.13: 滤波窗口从设置读取（1~10）
        signalFilter = new SignalFilter(new Settings(this).getFilterWindow());

        ble = new NiuBleManager(this, new NiuBleManager.Callback() {
            @Override public void onScanResult(BluetoothDevice device, String name, int rssi) {
                // v2.65: 过滤无效RSSI（Android BLE 的 127/255 表示无效值，正值异常）
                if (rssi == 127 || rssi == 255 || rssi > 0) return;
                lastRssi.put(device.getAddress(), rssi);
                lastDevices.put(device.getAddress(), device);
            }
            @Override public void onScanState(boolean scanning) {}
            @Override public void onLog(String line) { uiLog(line); }
        });

        // v2.17: 开机自动同步电量（如果已配置 token）
        autoSyncBattery();
        // v2.38: 启动诊断日志定时上传检查（每分钟检查一次）
        scheduleDiagUploadCheck();
    }

    // ========== v2.38: 诊断日志定时上传 ==========
    private final android.os.Handler diagUploadHandler = new android.os.Handler();
    private void scheduleDiagUploadCheck() {
        diagUploadHandler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    java.util.Calendar cal = java.util.Calendar.getInstance();
                    int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
                    String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(cal.getTime());
                    // 每天23:00上传，且同一天只上传一次
                    if (hour >= 23 && !today.equals(lastDiagUploadDate)) {
                        lastDiagUploadDate = today;
                        uploadDiagnosticLogs();
                    }
                } catch (Exception e) {
                    uiLog("诊断上传检查异常: " + e.getMessage());
                }
                diagUploadHandler.postDelayed(this, 60000); // 每分钟检查
            }
        }, 30000); // 启动30秒后开始第一次检查
    }

    private void uploadDiagnosticLogs() {
        try {
            AppLogger logger = AppLogger.get(this);
            java.util.List<File> pending = logger.getPendingDiagnosticFiles();
            if (pending.isEmpty()) {
                uiLog("无待上传的诊断日志");
                return;
            }
            Settings s = new Settings(this);
            String token = s.getGithubToken();
            if (token == null || token.isEmpty()) {
                uiLog("未配置GitHub Token，跳过诊断日志上传");
                return;
            }
            uiLog("开始上传 " + pending.size() + " 个诊断日志到 GitHub...");
            for (File logFile : pending) {
                try {
                    // 读取文件内容（兼容低版本API）
                    StringBuilder contentSb = new StringBuilder();
                    BufferedReader br = new BufferedReader(new FileReader(logFile));
                    String ln;
                    while ((ln = br.readLine()) != null) contentSb.append(ln).append("\n");
                    br.close();
                    String path = "diagnostic_logs/" + logFile.getName();
                    LogUploader.uploadToPath(this, token, path, contentSb.toString(), new LogUploader.UploadCallback() {
                        @Override public void onSuccess(String url) {
                            logger.deleteDiagnosticFile(logFile);
                            uiLog("诊断日志上传成功: " + logFile.getName());
                        }
                        @Override public void onFailure(String error) {
                            uiLog("诊断日志上传失败: " + logFile.getName() + " -> " + error);
                        }
                    });
                } catch (Exception e) {
                    uiLog("诊断日志上传异常: " + logFile.getName() + " -> " + e.getMessage());
                }
            }
        } catch (Exception e) {
            uiLog("诊断上传异常: " + e.getMessage());
        }
    }

    /** v2.21: 开机自动同步极驰锐动电量（10分钟间隔） */
    private void autoSyncBattery() {
        try {
            Settings s = new Settings(this);
            String token = s.getBatteryToken();
            if (token == null || token.trim().isEmpty()) return;
            // v2.21: 从1小时缩短到10分钟，确保每次开机基本都能刷新
            long lastSync = s.getBatterySyncedAt();
            if (lastSync > 0 && System.currentTimeMillis() - lastSync < 600000) return;
            new NiuBatteryClient(this).queryBattery((ok, percent, msg) -> {
                if (ok) {
                    uiLog("开机自动同步电量: " + percent + "%");
                    updateNotification();
                } else {
                    uiLog("电量自动同步失败: " + msg);
                }
            });
        } catch (Exception e) {
            uiLog("电量自动同步异常: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void startForegroundCompat() {
        Notification n = buildNotification(lastStatus);
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

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification(lastStatus));
        } catch (Exception e) { }
    }

    private Notification buildNotification(String text) {
        Settings s = new Settings(this);
        int battery = s.getBatteryPercent();

        Intent stopIntent = new Intent(this, BleService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent onPi = PendingIntent.getBroadcast(this, 1,
                new Intent(this, NiuWidgetProvider.class).setAction(NiuWidgetProvider.ACTION_POWER_ON),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent offPi = PendingIntent.getBroadcast(this, 2,
                new Intent(this, NiuWidgetProvider.class).setAction(NiuWidgetProvider.ACTION_POWER_OFF),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent seatPi = PendingIntent.getBroadcast(this, 3,
                new Intent(this, NiuWidgetProvider.class).setAction(NiuWidgetProvider.ACTION_SEAT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent autoPi = PendingIntent.getBroadcast(this, 4,
                new Intent(this, NiuWidgetProvider.class).setAction(NiuWidgetProvider.ACTION_AUTO),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // v2.53: 标准通知+系统原生action按钮，避免小米裁切自定义布局
        String contentText = text;
        if (battery >= 0) {
            contentText = "电量" + battery + "% · " + text;
        }

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("小牛靠近开机助手")
                .setContentText(contentText)
                .setContentIntent(openPi)
                .addAction(0, "开机", onPi)
                .addAction(0, "关机", offPi)
                .addAction(0, "坐桶", seatPi)
                .addAction(0, "自动", autoPi)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_HIGH)
                .setStyle(new Notification.BigTextStyle().bigText(contentText))
                .build();
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "靠近开关机服务",
                NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
    }

    private void scheduleWatchdog() {
        try {
            long triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, watchdogPi);
            } else {
                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, watchdogPi);
            }
        } catch (Exception e) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!runLoop) {
            runLoop = true;
            signalFilter.reset();
            trendDetector.reset();
            trendDetector.setConsecutiveRequired(new Settings(this).getConsecutiveRequired());
            handler.post(loopRunnable);
        }
        uiStatus("自动模式已启动");
        updateNotification();
        return START_STICKY;
    }

    private final Runnable loopRunnable = new Runnable() {
        @Override
        public void run() {
            if (!runLoop) return;
            try { tick(); } catch (Exception e) { uiLog("循环异常: " + e.getMessage()); }
        }
    };

    @Override
    public void onDestroy() {
        runLoop = false;
        handler.removeCallbacksAndMessages(null);
        if (ble != null) ble.stopScan();
        if (commandExecutor != null) commandExecutor.stop();
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception e) { }
        }
        if (alarmManager != null && watchdogPi != null) {
            try { alarmManager.cancel(watchdogPi); } catch (Exception e) { }
        }
        try { new Settings(this).setAutoModeRunning(false); } catch (Exception e) { }
        uiStatus("自动模式已停止");
        super.onDestroy();
    }

    /** v2.65: 扫描常驻 + 周期判定。不再每轮 stop/start，避免错过锁车后的慢广播 */
    private void tick() {
        if (!runLoop) return;
        Settings s = new Settings(this);
        if (!ble.isBluetoothOn()) { uiStatus("蓝牙未开启"); handler.postDelayed(this::tick, 1000); return; }
        if (s.getDeviceMac().isEmpty()) {
            uiStatus("未选择车辆蓝牙，请先在小牛蓝牙列表里选择");
            handler.postDelayed(this::tick, 1000);
            return;
        }
        if (!ble.isScanning()) ble.startScan(s.getDeviceMac()); // v2.72: 按目标MAC过滤扫描，绕过后台限流
        long scanMs = Math.max(MIN_SCAN_DURATION_MS, s.getScanIntervalMs());
        handler.postDelayed(this::decide, scanMs);
    }

    private void decide() {
        tickCount++;

        if (tickCount % 10 == 0) {
            updateNotification();
            scheduleWatchdog();
        }

        Settings s = new Settings(this);
        String target = s.getDeviceMac().toLowerCase();
        int best = Integer.MIN_VALUE;
        for (Map.Entry<String, Integer> e : lastRssi.entrySet()) {
            if (e.getKey().toLowerCase().equals(target) && e.getValue() > best) {
                best = e.getValue();
            }
        }
        long now = System.currentTimeMillis();
        if (best == Integer.MIN_VALUE) {
            // v2.65: 信号保活——锁车后设备可能 10~20 秒才广播一次，
            // 8 秒内收到过目标信号则沿用旧值继续判定，避免期间误判为无信号
            if (now - lastSignalTime <= KEEP_SIGNAL_MS && lastBestRssi != Integer.MIN_VALUE) {
                best = lastBestRssi;
            } else {
                uiStatus("未发现车辆蓝牙信号（目标 " + s.getDeviceName() + "）");
                try { AppLogger.get(this).logState("NO_SIGNAL", "未发现目标 " + s.getDeviceName()); } catch (Exception ignored) {}
                // v2.81: 扫描停摆自愈——45秒完全没收到目标信号时强制重启扫描，
                // 解决 Android 后台限流导致扫描静默失效（isScanning=true 但无回调）期间走近车不触发的问题
                if (now - lastSignalTime > SCAN_STALL_RESTART_MS) {
                    lastSignalTime = now; // 防连发
                    try {
                        ble.stopScan();
                        ble.startScan(s.getDeviceMac());
                        AppLogger.get(this).logState("SCAN_RESTART", "扫描静默超过" + (SCAN_STALL_RESTART_MS / 1000) + "秒，强制重启扫描");
                    } catch (Exception ignored) {}
                }
                tick();
                return;
            }
        } else {
            lastSignalTime = now;
            lastBestRssi = best;
        }

        // v2.12: 信号滤波（中值+EWMA，窗口可调）
        float smoothed = signalFilter.filter(best);
        // 实时更新RSSI到Settings供UI显示
        s.setCurrentRssi(smoothed);

        int nearThreshold = s.getRssiThreshold();
        int farThreshold = s.getRssiLeaveThreshold();
        long cooldown = s.getAutoOnCooldownSec() * 1000L;

        uiStatus(String.format("信号 %.0f dBm(原始%d) ≈ %s",
                smoothed, best, MainActivity.rssiToMeter((int) smoothed)));

        // v2.12: 趋势检测 + 连续达标判定
        boolean nearConfirmed = trendDetector.checkNear(smoothed, nearThreshold);
        boolean farConfirmed = trendDetector.checkFar(smoothed, farThreshold);
        // v2.41: 记录RSSI采样到诊断日志
        try { AppLogger.get(this).logRssi(best, smoothed, nearThreshold, farThreshold,
                trendDetector.getConsecutiveNear(), nearConfirmed, farConfirmed); } catch (Exception ignored) {}

        if (nearConfirmed) {
            farFired = false;
            if (s.isAutoOn() && !nearFired) {
                if ((now - lastAutoOnTime) > cooldown) {
                    lastAutoOnTime = now;
                    nearFired = true;
                    String reason = trendDetector.getLastNearReason();
                    String reasonCn = "STRONG".equals(reason) ? "强信号" : ("EARLY".equals(reason) ? "提前趋势" : "连续达标");
                    String log = String.format("已靠近(滤波%.0f/原始%d, 连续%d次, %s)，发送开机...",
                            smoothed, best, trendDetector.getConsecutiveNear(), reasonCn);
                    uiLog(log);
                    try { AppLogger.get(this).logTrigger("自动开机", log); } catch (Exception ignored) {}
                    s.setLastTriggerTime(now);
                    s.setLastTriggerAction("自动开机");
                    vibrateFeedback(true);
                    // v2.12: 异步命令发送（不阻塞扫描循环）
                    commandExecutor.execute(Settings.CMD_POWER_ON, new CommandExecutor.CommandCallback() {
                        @Override public void onSuccess(String command) {
                            uiLog("[OK] 开机命令已发送");
                            // v2.65: 指令结果写日志，便于诊断"触发了但车没开"
                            try { AppLogger.get(BleService.this).logCommand("自动开机", true, "命令已发送"); } catch (Exception ignored) {}
                            s.setVehicleState("已开机");
                            // v2.37: 自动开机后自动刷新电量和状态（小部件/状态栏/APP）
                            NiuWidgetProvider.syncStateAndBattery(BleService.this, "自动开机");
                            updateNotification();
                        }
                        @Override public void onFailure(String command, String error) {
                            uiLog("[失败] 开机 -> " + error);
                            try { AppLogger.get(BleService.this).logCommand("自动开机", false, error); } catch (Exception ignored) {}
                        }
                    });
                }
            }
        } else if (farConfirmed) {
            nearFired = false;
            if (s.isAutoOff() && !farFired) {
                if ((now - lastAutoOffTime) > cooldown) {
                    lastAutoOffTime = now;
                    farFired = true;
                    // v2.13: 延迟关机确认（60秒宽限期，信号恢复则取消）
                    String log = String.format("检测到离开(滤波%.0f/原始%d)，%d 秒后确认关机，信号恢复则自动取消",
                            smoothed, best, AUTO_OFF_CONFIRM_MS / 1000);
                    uiLog(log);
                    try { AppLogger.get(this).logTrigger("离开检测", log); } catch (Exception ignored) {}
                    s.setLastTriggerTime(now);
                    s.setLastTriggerAction("离开检测");
                    handler.postDelayed(this::confirmAutoOff, AUTO_OFF_CONFIRM_MS);
                }
            }
        } else {
            // 滞回区间或未达标，不触发
        }
        tick();
    }

    /** v2.13: 离开确认宽限期结束后的回调：信号恢复则取消，否则发送关机 */
    private void confirmAutoOff() {
        if (!runLoop) return;
        Settings s = new Settings(this);
        if (!s.isAutoOff()) { farFired = false; tick(); return; }
        float cur = signalFilter.getCurrent();
        if (cur >= s.getRssiLeaveThreshold()) {
            uiLog(String.format("确认期内信号恢复(%.0f dBm)，取消自动关机", cur));
            farFired = false;
            tick();
            return;
        }
        // 确认已远离，发送关机
        uiLog(String.format("确认已远离(%.0f dBm)，发送关机...", cur));
        s.setLastTriggerTime(System.currentTimeMillis());
        s.setLastTriggerAction("自动关机");
        vibrateFeedback(false);
        commandExecutor.execute(Settings.CMD_POWER_OFF, new CommandExecutor.CommandCallback() {
            @Override public void onSuccess(String command) {
                uiLog("[OK] 关机命令已发送");
                new Settings(BleService.this).setVehicleState("已关机");
            }
            @Override public void onFailure(String command, String error) {
                uiLog("[失败] 关机 -> " + error);
            }
        });
        // 保持 farFired=true，防止宽限期结束后反复触发；下次靠近后再离开才会再次触发
        tick();
    }

    /**
     * v2.12: 触发时震动反馈，让用户感知动作已执行
     * v2.66: 增加声音反馈通道（R3 多通道触发反馈），需传入动作类型区分音调
     */
    private void vibrateFeedback() {
        vibrateFeedback(true);
    }

    private void vibrateFeedback(boolean powerOn) {
        try {
            Settings s = new Settings(this);
            if (s.isVibrationEnabled()) {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createWaveform(
                                new long[]{0, 100, 80, 100}, -1));
                    } else {
                        v.vibrate(new long[]{0, 100, 80, 100}, -1);
                    }
                }
            }
            // v2.66: 声音反馈（可开关，开机/关机用不同音调）
            if (s.isSoundEnabled()) {
                try {
                    android.media.ToneGenerator tg = new android.media.ToneGenerator(
                            android.media.AudioManager.STREAM_NOTIFICATION,
                            powerOn ? 80 : 60);
                    // 开机：双短音；关机：单长音
                    if (powerOn) {
                        tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120);
                        try { Thread.sleep(160); } catch (InterruptedException ignored) {}
                        tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120);
                    } else {
                        tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 260);
                    }
                    tg.release();
                } catch (Exception ignored) {}
            }
        } catch (Exception e) { }
    }
}
