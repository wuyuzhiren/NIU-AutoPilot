package com.niu.autopilot;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.util.Log;

/**
 * BLE 扫描器（v2）：只用来扫描车辆蓝牙、获取 RSSI 判定距离。
 * 开关机/开坐桶等命令全部走 HTTP（NiuHttpClient），不再通过蓝牙发送。
 */
public class NiuBleManager {
    private static final String TAG = "NiuBle";
    private final Context ctx;
    private final BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean scanning = false;

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

    @SuppressLint("MissingPermission")
    public void startScan() {
        if (scanner == null || scanning) return;
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(null, settings, scanCallback);
            scanning = true;
            cb.onScanState(true);
        } catch (SecurityException e) {
            log("无扫描权限: " + e.getMessage());
        } catch (Exception e) {
            log("扫描异常: " + e.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (scanner == null || !scanning) return;
        try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        scanning = false;
        cb.onScanState(false);
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
        public void onScanFailed(int errorCode) { log("扫描失败 code=" + errorCode); }
    };
}
