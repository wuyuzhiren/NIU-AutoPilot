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
    /**
     * v3.4: 判定保活窗口 —— 取代 KEEP_SIGNAL_MS 用于判定链路。
     * 依据 PM 对 4 份真实日志（129,943 行）的实测：车机广播的簇间空窗
     * p90 = 25~29s、最长 90 分钟。旧的 15s 比 p90 还短，等于每个簇之间
     * 都要经历一次"假远离"，把簇内投票清空 —— 这是漏触发的首要原因。
     * 45s = p90 × 1.6，覆盖绝大多数空窗。
     */
    private static final long HOLD_SIGNAL_MS = 45 * 1000;
    /** v3.4: 极强单包快通道门槛（dB）——中位数明显落后时，单包高出阈值这么多也立即信任 */
    private static final int PEAK_TRUST_DB = 9;
    /**
     * v3.4: median 判定的阈值补偿（dB）。
     *
     * 依据 PM 离线回放实测（tools/LogReplay.py + docs/REPLAY-REPORT.md）：
     * 单纯换 median 后命中率 +0.0pp，但弱证据触发占比从 20% 恶化到 49%、
     * 触发余量中位从 +6.9dB 掉到 +3.0dB —— 等于在"刚够阈值"的弱信号上就开机。
     * 加 +3dB 补偿后余量回到 +5.5dB、弱触发回落到 32%，而命中率不受影响。
     */
    private static final int NEAR_MARGIN_DB = 3;
    /**
     * v3.4: 重新武装（re-arm）沉默阈值。
     *
     * 这是回放实验里【唯一被数据支持的大杠杆】：
     *   线上 nearFired 只在「低于离开阈值且连续 5 tick」时才重置，
     *   而广播稀疏时该条件经常永远不满足 → 一次开机后长期无法再触发。
     *   对 77 个 Episode 归因：75%（27 个）属于「强信号明明出现在窗口里却没触发」，
     *   根因就是状态机卡死。
     *   加「沉默 ≥60s 视为人已离开，重置 nearFired」后：
     *     E2E 53.2% → 84.4%（+31.2pp），触发/事件 1.40（可接受）
     *   实测对比：30s→触发 1.91 偏多；120s→79.2%；600s→54.5%（等同不启用）；
     *   「信号相对回落 ≥10dB」型 re-arm → 触发暴涨 4.48 倍，**不可用**。
     */
    private static final long REARM_SILENCE_MS = 60 * 1000;
    /** v2.81: 扫描停摆自愈——超过该时长完全收不到目标信号（后台限流导致扫描静默失效但 isScanning 仍为 true），强制重启扫描 */
    private static final long SCAN_STALL_RESTART_MS = 45 * 1000;

    private NiuBleManager ble;
    private NiuHttpClient http;
    private CommandExecutor commandExecutor;
    private SignalFilter signalFilter;
    private TrendDetector trendDetector = new TrendDetector();

    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * v3.4: RSSI 采样改为「窗口内多采样」。
     *
     * 商场/车多环境下，目标车广播会被其他设备挤占、被行人遮挡造成深度衰落。
     * 旧实现只保留【最后一次】采样值 —— 如果恰好那次是 -85（有人挡了一下），
     * 即使同一秒内另外 9 次广告都是 -55，也会被判成"远"，导致不触发开机。
     *
     * ⚠️ 窗口长度必须匹配真实广播间隔（依据 `日志分析` 129,943 行实测）：
     *   车机广播是"成簇稀疏"的 —— 包间隔 p50 = 0.6~1.2s，但 p90 = 25~29s，
     *   最长空窗可达 90 分钟。因此窗口必须 ≥ 30s，否则大部分时间窗口内空无一物。
     *   旧实现的"4s 新鲜 + 15s 保活"远短于 30s 空窗，这正是漏触发的物理根因。
     */
    private final Map<String, java.util.ArrayDeque<long[]>> rssiSamples = new HashMap<>();
    private final Map<String, BluetoothDevice> lastDevices = new HashMap<>();

    /** v3.4: 采样窗口（毫秒）——覆盖 p90 广播间隔，30s 内出现过就算"车在附近" */
    private static final long RSSI_WINDOW_MS = 30 * 1000;
    /** v3.4: 每设备最多保留的采样数（防止内存无限增长） */
    private static final int RSSI_MAX_SAMPLES = 64;
    /** v3.4: 连续多少次判定"无目标信号"后，触发全量扫描重新识别车辆（防 MAC 漂移） */
    private static final int NO_SIGNAL_STREAK_FOR_REDISCOVER = 3;

    private long lastAutoOnTime = 0;
    private long lastAutoOffTime = 0;
    private boolean nearFired = false;
    private boolean farFired = false;
    private boolean runLoop = false;
    /** v2.65: 目标设备最后收到信号的时间与强度（用于信号保活，应对锁车后慢广播） */
    private long lastSignalTime = 0;
    private int lastBestRssi = Integer.MIN_VALUE;
    /** v3.4: 连续"无目标信号"计数——用于触发全量扫描重新识别车辆（防 MAC 漂移） */
    private int noSignalStreak = 0;

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
                addRssiSample(device.getAddress(), rssi);
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

    /** v2.21: 开机自动同步换电电量（10分钟间隔；每次自动开机事件另由 syncStateAndBattery 强制刷新） */
    private void autoSyncBattery() {
        try {
            Settings s = new Settings(this);
            // v3.9: 云貂用 Cookie，其余平台用 token
            if (!NiuBatteryClient.isConfigured(s)) return;
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

    /** v3.9.3: 靠近车辆时刷新换电电量（含重新取当前电池编号），换电池后自动跟随，3 分钟节流 */
    private long lastNearBatterySync = 0;
    private void maybeRefreshBatteryNear() {
        try {
            Settings s = new Settings(this);
            if (!NiuBatteryClient.isConfigured(s)) return;
            long now = System.currentTimeMillis();
            if (now - lastNearBatterySync < 180000) return;
            lastNearBatterySync = now;
            new NiuBatteryClient(this).queryBattery((ok, percent, msg) -> {
                if (ok) {
                    uiLog("靠近刷新电量: " + percent + "%");
                    updateNotification();
                    sendBroadcast(new Intent(NiuWidgetProvider.ACTION_STATE_SYNC));
                } else {
                    uiLog("靠近刷新电量失败: " + msg);
                }
            });
        } catch (Exception e) {
            uiLog("靠近刷新电量异常: " + e.getMessage());
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

    // ========== v3.4: RSSI 多采样窗口 ==========

    /** 记录一次 RSSI 采样（带时间戳），并裁掉过期/超量的样本 */
    private void addRssiSample(String mac, int rssi) {
        long now = System.currentTimeMillis();
        java.util.ArrayDeque<long[]> q = rssiSamples.get(mac);
        if (q == null) {
            q = new java.util.ArrayDeque<>();
            rssiSamples.put(mac, q);
        }
        q.addLast(new long[]{now, rssi});
        // 裁掉过期样本
        while (!q.isEmpty() && now - q.peekFirst()[0] > RSSI_WINDOW_MS) {
            q.removeFirst();
        }
        // 裁掉超量样本
        while (q.size() > RSSI_MAX_SAMPLES) {
            q.removeFirst();
        }
    }

    /**
     * v3.4: 取窗口内的【中位数】采样值 —— 判定用统计量。
     *
     * 为什么是 median 而不是 max（PM 用 4 份真实日志实测得出的结论）：
     *   30s 窗口约含 50 个样本，单包噪声 σ = 5~11 dB（商场日最高）。
     *   E[max of n] ≈ μ + σ·Φ⁻¹(1−1/(n+1))：
     *     干净日(σ=5.2)  → max 正偏 +10.7 dB
     *     商场日(σ=11.1) → max 正偏 +22.8 dB
     *   这意味着同一个 −67 阈值在干净日实际等于 −55、在商场日实际等于 −44，
     *   阈值语义随环境漂 10~23dB，绝对校准彻底失效 —— 商场里隔几十米、
     *   甚至在楼上都能凑出一个"强包"来误触发。
     *   median 对对称噪声零偏置，才能让"−67 dBm"这个数字始终指同一个距离。
     *
     * 返回 Integer.MIN_VALUE 表示窗口内没有任何采样。
     */
    private int medianFreshRssi(String targetMac) {
        long now = System.currentTimeMillis();
        java.util.List<Integer> vals = new java.util.ArrayList<>();
        for (Map.Entry<String, java.util.ArrayDeque<long[]>> e : rssiSamples.entrySet()) {
            if (!e.getKey().equalsIgnoreCase(targetMac)) continue;
            java.util.ArrayDeque<long[]> q = e.getValue();
            synchronized (q) {
                for (long[] s : q) {
                    if (now - s[0] > RSSI_WINDOW_MS) continue;
                    vals.add((int) s[1]);
                }
            }
        }
        if (vals.isEmpty()) return Integer.MIN_VALUE;
        java.util.Collections.sort(vals);
        int n = vals.size();
        return (n % 2 == 1) ? vals.get(n / 2) : (vals.get(n / 2 - 1) + vals.get(n / 2)) / 2;
    }

    /**
     * v3.4: 取窗口内的【最强】单包值 —— 只用于"极强单包快通道"。
     *
     * max 唯一可信的用法就在于此：多径/遮挡只会让信号【变弱】、不会凭空变强，
     * 所以一个明显高于阈值的单包，物理上确实说明"车就在很近处"。
     * 不能用 max 做常规判定（那会让阈值随环境漂移），只能作为快通道的旁证。
     */
    private int peakFreshRssi(String targetMac) {
        long now = System.currentTimeMillis();
        int best = Integer.MIN_VALUE;
        for (Map.Entry<String, java.util.ArrayDeque<long[]>> e : rssiSamples.entrySet()) {
            if (!e.getKey().equalsIgnoreCase(targetMac)) continue;
            java.util.ArrayDeque<long[]> q = e.getValue();
            synchronized (q) {
                for (long[] s : q) {
                    if (now - s[0] > RSSI_WINDOW_MS) continue;
                    if ((int) s[1] > best) best = (int) s[1];
                }
            }
        }
        return best;
    }


    /** v3.4: 窗口内目标设备的采样条数（用于日志与诊断） */
    private int freshSampleCount(String targetMac) {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Map.Entry<String, java.util.ArrayDeque<long[]>> e : rssiSamples.entrySet()) {
            if (!e.getKey().equalsIgnoreCase(targetMac)) continue;
            java.util.ArrayDeque<long[]> q = e.getValue();
            synchronized (q) {
                for (long[] s : q) {
                    if (now - s[0] <= RSSI_WINDOW_MS) n++;
                }
            }
        }
        return n;
    }

    /**
     * v3.4: MAC 漂移恢复——从近期扫描到的设备里，找出「名字像车」且信号最强的那个。
     * 返回其 MAC；找不到返回 null。
     *
     * 判断依据：小牛车辆蓝牙名通常含 NIU / NX / 车型名。只看最近 10 秒内有采样的设备。
     */
    private String rediscoverCarMac() {
        long now = System.currentTimeMillis();
        String bestMac = null;
        int bestRssi = Integer.MIN_VALUE;
        for (Map.Entry<String, BluetoothDevice> e : lastDevices.entrySet()) {
            String mac = e.getKey();
            // 必须有近期采样
            int rssi = medianFreshRssi(mac);
            if (rssi == Integer.MIN_VALUE) continue;
            // 名字必须像车（含 NIU / NX，或不含典型耳机/手环关键字）
            String name = "";
            try {
                BluetoothDevice d = e.getValue();
                name = d.getName() != null ? d.getName() : "";
            } catch (Exception ignored) {}
            String up = name.toUpperCase();
            boolean looksLikeCar = up.contains("NIU") || up.contains("NX")
                    || up.contains("SCOOTER") || up.contains("MOTOR");
            if (!looksLikeCar) continue;
            if (rssi > bestRssi) {
                bestRssi = rssi;
                bestMac = mac;
            }
        }
        return bestMac;
    }

    /**
     * v3.5: 【回归修复】扫描一律用 LOW_LATENCY，不再做「狩猎区才激进」的自适应。
     *
     * v3.4 的写法有两个致命缺陷，直接造成真机「十次只开两三次」：
     *
     * 1. **模式被定死**：tick() 只在 !ble.isScanning() 时才 startScan，而 v2.65 起
     *    扫描是常驻的 → 模式在服务启动那一刻定死、之后永不重估。启动瞬间
     *    signalFilter.getCurrent()==0f，本方法返回 false → 永久 LOW_POWER。
     *
     * 2. **LOW_POWER 占空比只有约 10%**（Android 规范：0.5s 扫描窗 / 5s 周期），
     *    包量掉一个数量级。用 tools/ScanDutySim.py 对 4 份真实日志做 10% 下采样回放：
     *      LEGACY      E2E 53.2% →  7.8%
     *      NEW_MEDIAN  E2E 53.2% → 10.4%
     *      NEW_REARM   E2E 84.4% → 46.8%
     *    与用户真机「十次两三次」吻合 —— 命中率暴跌的主因是**采样密度**，
     *    不是判定规则。离线回放报告之所以显示 84.4%，是因为那批日志本身是
     *    v3.3 用 LOW_LATENCY 采出来的，回放器看不见「包变少」这件事。
     *
     * 结论：产品核心价值就是「走近就开」，宁可多耗电也不能少收包。
     * 省电只能靠「无信号时降低 tick 频率」，不能靠降低扫描占空比。
     */
    private boolean isAggressiveScanning() {
        return true;
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
        if (!ble.isScanning()) {
            // v3.5: 固定 LOW_LATENCY。v3.4 曾在这里按「狩猎区」动态选 LOW_POWER，
            // 但模式只在启动那一刻定一次（扫描常驻），实际结果是永久 LOW_POWER，
            // 命中率从 ~84% 掉到 ~10%（见 isAggressiveScanning() 注释）。
            ble.startScan(s.getDeviceMac(), isAggressiveScanning());
        }

        // v3.4: 扫描间隔自适应——在「狩猎区」加快采样，远离时放慢省电。
        // 商场里走近车的过程中信号抖动剧烈，必须提高采样密度才能稳定命中。
        long scanMs = Math.max(MIN_SCAN_DURATION_MS, s.getScanIntervalMs());
        float cur = signalFilter.getCurrent();
        int nearTh = s.getRssiThreshold();
        int farTh = s.getRssiLeaveThreshold();
        // 狩猎区：信号介于「远离阈值 - 12dB」与「靠近阈值」之间，说明人可能正在走近
        if (cur != 0f && cur >= (farTh - 12) && cur <= nearTh) {
            scanMs = Math.max(MIN_SCAN_DURATION_MS, scanMs / 2); // 采样密度×2
        } else if (cur != 0f && cur < (farTh - 25)) {
            scanMs = Math.min(scanMs * 2, 3000); // 明显很远，放慢省电
        }
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
        long now = System.currentTimeMillis();

        // ==================== v3.4: 状态机重新武装（re-arm）====================
        // 回放实测的【最大杠杆】：75% 的漏检是「强信号在窗口里却没触发」，
        // 根因是 nearFired 只在「连续 5 tick 低于离开阈值」时才重置，
        // 而广播稀疏(p90 空窗 25~29s)时该条件常常永远不满足 → 一次开机后卡死。
        // 规则：沉默 ≥60s = 人已经走了 = 重新武装。E2E 53.2% → 84.4%。
        if ((nearFired || farFired) && (now - lastSignalTime) >= REARM_SILENCE_MS) {
            String msg = String.format("沉默%.0fs≥%ds，视为已离开，重新武装(nearFired %s→false)",
                    (now - lastSignalTime) / 1000.0, REARM_SILENCE_MS / 1000, nearFired);
            nearFired = false;
            farFired = false;
            try { AppLogger.get(this).logState("REARM", msg); } catch (Exception ignored) {}
            uiLog(msg);
        }

        // v3.4: 判定统计量用【中位数】（对噪声零偏置），不用 max。
        // max 会有 +10~23dB 环境相关正偏，导致阈值语义随环境漂移（PM 实测结论）。
        int best = medianFreshRssi(target);
        // 峰值单包：只用于"极强单包快通道"（多径只会衰减，不会凭空增强）
        int peak = peakFreshRssi(target);
        int sampleCount = freshSampleCount(target);

        if (best == Integer.MIN_VALUE) {
            noSignalStreak++;
            // v3.4: 保活窗口 15s → 45s（PM 实测 p90 空窗 27~29s，
            // 15s 比空窗短，等于每个簇之间都要经历一次"假远离"，票被清空）。
            if (now - lastSignalTime <= HOLD_SIGNAL_MS && lastBestRssi != Integer.MIN_VALUE) {
                best = lastBestRssi;
            } else {
                uiStatus("未发现车辆蓝牙信号（目标 " + s.getDeviceName() + "）");
                try { AppLogger.get(this).logState("NO_SIGNAL", "未发现目标 " + s.getDeviceName()); } catch (Exception ignored) {}
                // v2.81: 扫描停摆自愈——45秒完全没收到目标信号时强制重启扫描，
                // 解决 Android 后台限流导致扫描静默失效（isScanning=true 但无回调）期间走近车不触发的问题
                if (now - lastSignalTime > SCAN_STALL_RESTART_MS) {
                    lastSignalTime = now; // 防连发
                    try {
                        // v3.5: 必须传 true。这里曾经传 false（LOW_POWER），而本分支在
                        // 「45 秒收不到任何信号」时必然触发——也就是用户离车稍远、或熄屏时，
                        // 每 45 秒就会把扫描模式从 LOW_LATENCY 降级回 LOW_POWER，
                        // 于是 tick() 里那次修复被这条自愈路径反复抵消。
                        ble.restartScan(s.getDeviceMac(), true);
                        AppLogger.get(this).logState("SCAN_RESTART", "扫描静默超过" + (SCAN_STALL_RESTART_MS / 1000) + "秒，强制重启扫描");
                    } catch (Exception ignored) {}
                }
                // v3.4: MAC 漂移恢复——连续多次判定无信号，怀疑车辆 BLE 地址变了
                // （部分 BLE 设备使用可解析私有地址，会周期性轮换 MAC）。
                // 退化为【全量扫描】重新发现车辆：只要名字含 NIU 且信号最强，就采用它。
                if (noSignalStreak >= NO_SIGNAL_STREAK_FOR_REDISCOVER && noSignalStreak % NO_SIGNAL_STREAK_FOR_REDISCOVER == 0) {
                    try {
                        String recovered = rediscoverCarMac();
                        if (recovered != null && !recovered.equalsIgnoreCase(target)) {
                            AppLogger.get(this).logState("MAC_DRIFT",
                                    "车辆地址可能已变化: " + target + " -> " + recovered + "，已更新");
                            s.setDeviceMac(recovered);
                            target = recovered.toLowerCase();
                            noSignalStreak = 0;
                            ble.restartScan(recovered, true);
                        } else if (recovered == null) {
                            // 找不到就做一次全量扫描，下一次判定可能就命中了
                            ble.restartScan(null, true);
                        }
                    } catch (Exception ignored) {}
                }
                tick();
                return;
            }
        } else {
            lastSignalTime = now;
            lastBestRssi = best;
        }

        // v3.4: 收到了目标信号，清零无信号计数
        noSignalStreak = 0;

        // v3.4: EWMA 滤波【退出判定链路】（PM 实测结论）——
        // EWMA(α=0.8) 是跨簇平滑：簇内 -58/-70 会平滑成 -60.4，
        // 恰好把新簇第一个强包拉向旧值，抵消快通道的敏捷性。
        // 现在：判定直接用 median 原值；EWMA 只用于 UI 显示。
        float smoothed = signalFilter.filter(best);
        s.setCurrentRssi(smoothed);

        int nearThreshold = s.getRssiThreshold();
        int farThreshold = s.getRssiLeaveThreshold();
        long cooldown = s.getAutoOnCooldownSec() * 1000L;

        // v3.4: 状态栏显示 中位数/峰值/采样数，便于判断信号质量
        uiStatus(String.format("信号 %d dBm(峰%d, %d采样) ≈ %s",
                best, peak, sampleCount, MainActivity.rssiToMeter(best)));

        // v3.4: 判定用 median 原值（不经过 EWMA）
        // 极强单包快通道：中位数还没到阈值，但出现过明显高于阈值的单包
        // —— 多径只会衰减不会凭空增强，所以这类单包物理上可信。
        // v3.4: +3dB 补偿（回放实测：不加补偿弱证据触发占比 20%→49%）
        int nearWithMargin = nearThreshold + NEAR_MARGIN_DB;
        boolean peakTrust = peak != Integer.MIN_VALUE && peak >= (nearWithMargin + PEAK_TRUST_DB);
        boolean nearConfirmed = trendDetector.checkNear(best, nearWithMargin) || peakTrust;
        boolean farConfirmed = trendDetector.checkFar(best, farThreshold);
        // v2.41: 记录RSSI采样到诊断日志
        try { AppLogger.get(this).logRssi(best, smoothed, nearThreshold, farThreshold,
                trendDetector.getConsecutiveNear(), nearConfirmed, farConfirmed); } catch (Exception ignored) {}

        if (nearConfirmed) {
            farFired = false;
            // v3.9.3: 手机靠近/连上车就刷新换电电量（换电池后无需等下次开机，体验同极驰）
            maybeRefreshBatteryNear();
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
