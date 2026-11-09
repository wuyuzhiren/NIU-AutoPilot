package com.niu.autopilot;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/**
 * BLE 扫描器（v2.72）：只用来扫描车辆蓝牙、获取 RSSI 判定距离。
 * 开关机/开坐桶等命令全部走 HTTP（NiuHttpClient），不再通过蓝牙发送。
 * v2.72: startScan(filterMac) 支持按目标 MAC 构建 ScanFilter——
 * Android 8+ 后台无过滤扫描会被系统限流到极低频率，绑定 MAC 后恢复正常频率。
 */
public class NiuBleManager {
    private static final String TAG = "NiuBle";
    private final Context ctx;
    private final BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean scanning = false;
    private String targetMac = null;
    private int scanRetryCount = 0;
    private final Handler retryHandler = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onScanResult(BluetoothDevice device, String name, int rssi);
        void onScanState(boolean scanning);
        void onLog(String line);
    }
    private Callback cb;

    public NiuBleManager(Context ctx, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.cb = cb;
        BluetoothManager bm = (BluetoothManager) this.ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm != null ? bm.getAdapter() : null;
        if (adapter != null) this.scanner = adapter.getBluetoothLeScanner();
    }

    public boolean isBluetoothOn() { return adapter != null && adapter.isEnabled(); }
    public boolean isScanning() { return scanning; }

    @SuppressLint("MissingPermission")
    private void log(String s) { if (cb != null) cb.onLog(s); }

    /** v2.72: 全量扫描（无过滤），用于校准等需要看到所有设备的场景 */
    public void startScan() {
        startScan(null);
    }

    /** v2.72: 按目标 MAC 构建 ScanFilter 扫描，绕过后台扫描限流 */
    public void startScan(String filterMac) {
        startScan(filterMac, true);
    }

    /**
     * v3.4: 可指定扫描模式。
     *
     * @param filterMac 目标 MAC；null/空 = 全量扫描
     * @param lowLatency true = SCAN_MODE_LOW_LATENCY（最快，耗电高）；
     *                   false = SCAN_MODE_LOW_POWER（省电）
     *
     * v3.5: 一律传 true。LOW_POWER 的扫描占空比只有约 10%（0.5s 窗口 / 5s 周期），
     * 实测把「一次靠近」的命中率从 84% 打到 10% 左右，与真机「十次只开两三次」吻合。
     * 不要再因为「省电」「怕被限流」而降级——产品核心价值就是走近就开。
     */
    @SuppressLint("MissingPermission")
    public void startScan(String filterMac, boolean lowLatency) {
        if (scanner == null || scanning) return;
        targetMac = (filterMac != null && !filterMac.isEmpty()) ? filterMac : null;
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(lowLatency
                        ? ScanSettings.SCAN_MODE_LOW_LATENCY
                        : ScanSettings.SCAN_MODE_LOW_POWER)
                .build();
        List<ScanFilter> filters = null;
        if (targetMac != null) {
            try {
                filters = Collections.singletonList(
                        new ScanFilter.Builder().setDeviceAddress(targetMac).build());
            } catch (Exception e) {
                log("MAC过滤构建失败，回退全量: " + e.getMessage());
                filters = null;
            }
        }
        try {
            scanner.startScan(filters, settings, scanCallback);
            scanning = true;
            scanRetryCount = 0;
            cb.onScanState(true);
        } catch (SecurityException e) {
            log("无扫描权限: " + e.getMessage());
        } catch (Exception e) {
            log("扫描异常: " + e.getMessage());
            scheduleScanRetry();
        }
    }

    /**
     * v3.4: 重启扫描并切换模式/过滤条件。
     * 用于「MAC 漂移」恢复：怀疑车辆 BLE 地址变了时，退化为全量扫描重新识别。
     */
    public void restartScan(String filterMac, boolean lowLatency) {
        try {
            if (scanning) stopScan();
        } catch (Exception ignored) {}
        startScan(filterMac, lowLatency);
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (scanner == null || !scanning) return;
        try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        scanning = false;
        retryHandler.removeCallbacksAndMessages(null);
        cb.onScanState(false);
    }

    /** v2.72: 扫描异常/失败后自动恢复——3s/6s/12s 指数退避重试，不再等 Watchdog */
    private void scheduleScanRetry() {
        long delay = 3000L * (1L << Math.min(scanRetryCount, 2));
        scanRetryCount++;
        retryHandler.removeCallbacksAndMessages(null);
        retryHandler.postDelayed(() -> {
            if (!scanning) startScan(targetMac);
        }, delay);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice dev = result.getDevice();
            String name = dev.getName() != null ? dev.getName() : "";
            cb.onScanResult(dev, name, result.getRssi());
        }
        @Override
        public void onScanFailed(int errorCode) {
            log("扫描失败 code=" + errorCode + "，自动重试恢复");
            scanning = false;
            cb.onScanState(false);
            scheduleScanRetry();
        }
    };
}
