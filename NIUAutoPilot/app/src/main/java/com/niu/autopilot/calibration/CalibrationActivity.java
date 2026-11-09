package com.niu.autopilot.calibration;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.niu.autopilot.NiuBleManager;
import com.niu.autopilot.R;
import com.niu.autopilot.Settings;
import com.niu.autopilot.AppLogger;
import com.niu.autopilot.LogUploader;
import java.io.File;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能校准向导 v2.43（四步闭环：标定→采样→拟合→验证）：
 * 第一步：车辆基准点标定（GPS 5点去极值取均值 + RSSI 10组去极值取均值）
 * 第二步：向外行走采样（每秒1组「距离+RSSI」配对，自动过滤异常）
 * 第三步：模型自动拟合（最小二乘，1.5秒过渡）
 * 第四步：触发效果验证（走回车辆，用新参数判定触发点，记录真实触发距离）
 * 结果：验证成功(误差≤1米)/偏差较大(>1米)/验证失败(未触发) 三种分支
 * 支持：快速模式、验证后自动应用、保存常用地点、差异化震动、返回确认、2分钟无操作退出
 */
public class CalibrationActivity extends AppCompatActivity {
    private static final int REQ_PERM = 3001;
    private static final long BASE_TIMEOUT_MS = 15000;      // 标定超时
    private static final long WALK_TIMEOUT_MS = 120000;     // 行走超时
    private static final long VERIFY_TIMEOUT_MS = 90000;    // 验证超时
    private static final long NO_OP_TIMEOUT_MS = 120000;    // 无操作自动退出
    private static final int MIN_GPS_POINTS = 5;            // 基准 GPS 点数
    private static final int MIN_BASE_RSSI = 3;            // 基准 RSSI 组数
    private static final int MAX_HISTORY = 5;               // 历史条数

    // 采样判定参数（随快速模式变化）
    private int targetSample = 20;        // 自动完成样本数
    private int earlySample = 10;         // 提前完成样本数
    private double targetDistance = 10.0; // 自动完成距离
    private double earlyDistance = 5.0;   // 提前完成距离
    private static final double NEAR_TRIGGER_M = 5.0;   // 靠近触发距离
    private static final double FAR_TRIGGER_M = 12.0;   // 离开触发距离

    private View cardStep1, cardStep2, cardStep3, cardStep4;
    private View cardResultOk, cardResultDev, cardResultFail;
    private TextView tvStep1Hint, tvStep1Quality, tvStep1Points, tvStep1Status;
    private TextView tvStep2Guide, tvCurDistance, tvCurRssi, tvSignalQuality;
    private TextView tvSampleProgress, tvStep2Status;
    private TextView tvFitStatus, tvVerifyDistance, tvVerifyRssi, tvVerifyHint, tvVerifyStatus;
    private TextView tvOkEnv, tvOkNearDist, tvOkNear, tvOkActualDist, tvOkActualRssi, tvOkError, tvOkHyster, tvOkConf;
    private TextView tvDevDesc, tvFailDesc;
    private TextView stepDot1, stepDot2, stepDot3, stepDot4;
    private TextView stepText1, stepText2, stepText3, stepText4;
    private Button btnBegin, btnFinishSample, btnApply;
    private ProgressBar progressStep1, progressStep2, progressVerify;

    private Settings settings;
    private NiuBleManager ble;
    private LocationManager locationManager;
    private Vibrator vibrator;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String targetMac = "";
    private volatile int currentRssi = -100;
    private volatile boolean rssiValid = false;

    private int stage = 0; // 0=idle 1=标定 2=采样 3=拟合 4=验证 5=结果
    private boolean calibrating = false;

    // 基准标定
    private final List<Location> basePoints = new ArrayList<>();
    private final List<Integer> baseRssiList = new ArrayList<>();
    private double baseLat = 0, baseLng = 0;
    private double walkStartLat = 0, walkStartLng = 0;
    private boolean baseReady = false;

    // 行走采样
    private final List<CalibrationHelper.CalibSample> samples = new ArrayList<>();
    private long walkStart = 0;
    private long lastRssiSampleAt = 0;
    private long lastBaseRssiAt = 0;
    private double lastVibrateDist = 0;
    private double maxWalkDist = 0;
    private int invalidCount = 0;

    // 验证
    private CalibrationResult lastResult;
    private boolean verified = false;
    private double actualTriggerDist = -1;
    private int actualTriggerRssi = -100;
    private int consecutive = 0;
    private long noOpTimerStart = 0;

    // ================== 生命周期 ==================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calibration);

        settings = new Settings(this);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        ble = new NiuBleManager(this, bleCallback);

        if (settings.isQuickCalibration()) {
            targetSample = 15; earlySample = 10;
            targetDistance = 8.0; earlyDistance = 5.0;
        }

        bindViews();
        String mac = getIntent().getStringExtra("mac_address");
        if (mac != null && !mac.isEmpty()) targetMac = mac.toUpperCase();

        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        btnBegin.setOnClickListener(v -> beginCalibration());
        btnFinishSample.setOnClickListener(v -> finishSampling());
        btnApply.setOnClickListener(v -> applyResult(false));
        findViewById(R.id.btn_redo).setOnClickListener(v -> restartCalibration());
        findViewById(R.id.btn_skip_verify).setOnClickListener(v -> {
            lastResult.verifyErrorM = -1;
            showResult(false);
        });
        // 偏差分支
        findViewById(R.id.btn_dev_redo).setOnClickListener(v -> restartCalibration());
        findViewById(R.id.btn_dev_manual).setOnClickListener(v -> {
            Toast.makeText(this, "请到设置页手动调整靠近/离开阈值", Toast.LENGTH_SHORT).show();
            finish();
        });
        findViewById(R.id.btn_dev_apply).setOnClickListener(v -> applyResult(true));
        // 失败分支
        findViewById(R.id.btn_fail_redo).setOnClickListener(v -> restartCalibration());
        findViewById(R.id.btn_fail_back).setOnClickListener(v -> finish());
        // 保存地点
        findViewById(R.id.btn_place_home).setOnClickListener(v -> savePlace("家"));
        findViewById(R.id.btn_place_work).setOnClickListener(v -> savePlace("公司"));
        findViewById(R.id.btn_place_garage).setOnClickListener(v -> savePlace("地库"));
        findViewById(R.id.btn_save_scene).setOnClickListener(v -> saveAsScene());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        stopScanAndLocation();
    }

    @Override
    public void onBackPressed() {
        if (stage >= 1 && stage <= 4) {
            new AlertDialog.Builder(this)
                    .setTitle("退出校准")
                    .setMessage("确定退出校准吗？当前进度将不保存，原有参数不变。")
                    .setPositiveButton("确定退出", (d, w) -> {
                        stopScanAndLocation();
                        finish();
                    })
                    .setNegativeButton("继续校准", null)
                    .show();
            return;
        }
        super.onBackPressed();
    }

    // ================== UI 绑定 ==================

    private void bindViews() {
        cardStep1 = findViewById(R.id.card_step1);
        cardStep2 = findViewById(R.id.card_step2);
        cardStep3 = findViewById(R.id.card_step3);
        cardStep4 = findViewById(R.id.card_step4);
        cardResultOk = findViewById(R.id.card_result_ok);
        cardResultDev = findViewById(R.id.card_result_dev);
        cardResultFail = findViewById(R.id.card_result_fail);
        tvStep1Hint = findViewById(R.id.tv_step1_hint);
        tvStep1Status = findViewById(R.id.tv_step1_status);        tvStep1Quality = findViewById(R.id.tv_step1_quality);
        tvStep1Points = findViewById(R.id.tv_step1_points);
        tvStep2Guide = findViewById(R.id.tv_step2_guide);
        tvCurDistance = findViewById(R.id.tv_cur_distance);
        tvCurRssi = findViewById(R.id.tv_cur_rssi);
        tvSignalQuality = findViewById(R.id.tv_signal_quality);
        tvSampleProgress = findViewById(R.id.tv_sample_progress);
        tvStep2Status = findViewById(R.id.tv_step2_status);
        tvFitStatus = findViewById(R.id.tv_fit_status);
        tvVerifyDistance = findViewById(R.id.tv_verify_distance);
        tvVerifyRssi = findViewById(R.id.tv_verify_rssi);
        tvVerifyHint = findViewById(R.id.tv_verify_hint);
        tvVerifyStatus = findViewById(R.id.tv_verify_status);
        tvOkEnv = findViewById(R.id.tv_ok_env);
        tvOkNearDist = findViewById(R.id.tv_ok_near_dist);
        tvOkNear = findViewById(R.id.tv_ok_near);
        tvOkActualDist = findViewById(R.id.tv_ok_actual_dist);
        tvOkActualRssi = findViewById(R.id.tv_ok_actual_rssi);
        tvOkError = findViewById(R.id.tv_ok_error);
        tvOkHyster = findViewById(R.id.tv_ok_hyster);
        tvOkConf = findViewById(R.id.tv_ok_conf);
        tvDevDesc = findViewById(R.id.tv_dev_desc);
        tvFailDesc = findViewById(R.id.tv_fail_desc);
        stepDot1 = findViewById(R.id.step_dot_1);
        stepDot2 = findViewById(R.id.step_dot_2);
        stepDot3 = findViewById(R.id.step_dot_3);
        stepDot4 = findViewById(R.id.step_dot_4);
        stepText1 = findViewById(R.id.step_text_1);
        stepText2 = findViewById(R.id.step_text_2);
        stepText3 = findViewById(R.id.step_text_3);
        stepText4 = findViewById(R.id.step_text_4);
        btnBegin = findViewById(R.id.btn_begin_calibrate);
        btnFinishSample = findViewById(R.id.btn_finish_sample);
        btnApply = findViewById(R.id.btn_apply);
        progressStep1 = findViewById(R.id.progress_step1);
        progressStep2 = findViewById(R.id.progress_step2);
        progressVerify = findViewById(R.id.progress_verify);
    }

    // ================== 前置检查 ==================

    private void beginCalibration() {
        if (settings.isAutoModeRunning()) {
            showError("自动模式正在运行，会占用蓝牙扫描。请先停止自动模式再校准。");
            return;
        }
        if (targetMac.isEmpty()) {
            showError("未传入车辆 MAC，请先到设置页扫描绑定车辆");
            return;
        }
        if (!hasAllPermissions()) {
            requestPermissions();
            return;
        }
        if (!ble.isBluetoothOn()) {
            Toast.makeText(this, "请先连接车辆蓝牙再校准", Toast.LENGTH_LONG).show();
            showError("请先开启蓝牙再校准");
            return;
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                && !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            new AlertDialog.Builder(this)
                    .setTitle("需要定位")
                    .setMessage("校准需要定位权限，用于计算真实距离。请去开启定位服务。")
                    .setPositiveButton("去开启", (d, w) -> {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                        } catch (Exception ignored) {}
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        startBaseCalibration();
    }

    private boolean hasAllPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        List<String> need = new ArrayList<>();
        need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) {
            need.add(Manifest.permission.BLUETOOTH_SCAN);
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            boolean ok = grantResults.length > 0;
            for (int g : grantResults) if (g != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) {
                tvStep1Status = findViewById(R.id.tv_step1_status);
                Toast.makeText(this, "权限已授予，请再次点击「开始标定」", Toast.LENGTH_SHORT).show();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("需要定位权限")
                        .setMessage("校准需要精确定位权限，用于计算真实距离。点击「去开启」前往系统设置。")
                        .setPositiveButton("去开启", (d, w) -> {
                            try {
                                Intent it = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                it.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(it);
                            } catch (Exception ignored) {}
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        }
    }

    // ================== 第一步：基准标定 ==================

    private void startBaseCalibration() {
        stage = 1;
        calibrating = true;
        basePoints.clear();
        baseRssiList.clear();
        samples.clear();
        baseReady = false;
        currentRssi = -100;
        rssiValid = false;
        lastVibrateDist = 0;
        maxWalkDist = 0;
        invalidCount = 0;
        consecutive = 0;

        tvStep1Points.setText("已采集 0/" + MIN_GPS_POINTS + " 点");
        progressStep1.setProgress(0);
        btnBegin.setEnabled(false);
        btnBegin.setText("标定中…");
        noOpTimerStart = System.currentTimeMillis();
        resetNoOpTimer();
        showStep(1);

        ble.startScan();
        startLocationUpdates();
        handler.removeCallbacks(baseTimeoutRunnable);
        handler.postDelayed(baseTimeoutRunnable, BASE_TIMEOUT_MS);
    }

    private final Runnable baseTimeoutRunnable = () -> {
        if (stage != 1) return;
        stopScanAndLocation();
        stage = 0;
        calibrating = false;
        btnBegin.setEnabled(true);
        btnBegin.setText("开始标定");
        tvStep1Status.setText("未收到车辆蓝牙信号，请确认在车旁且自动模式已停，再点开始标定");
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            if (stage == 1 && !baseReady) {
                if (!location.hasAccuracy() || location.getAccuracy() > 50.0f) return;
                if (!basePoints.isEmpty()) {
                    double d = haversine(location.getLatitude(), location.getLongitude(),
                            basePoints.get(basePoints.size() - 1).getLatitude(),
                            basePoints.get(basePoints.size() - 1).getLongitude());
                    if (d < 0.3) return;
                }
                basePoints.add(location);
                tvStep1Points.setText("已采集 " + basePoints.size() + "/" + MIN_GPS_POINTS + " 点");
                progressStep1.setProgress((int) (basePoints.size() * 100.0 / MIN_GPS_POINTS));
                maybeFinishBase();
            }
        }

        @Override public void onProviderEnabled(@NonNull String provider) {}
        @Override public void onProviderDisabled(@NonNull String provider) {}
        @Override public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}
    };

    private final NiuBleManager.Callback bleCallback = new NiuBleManager.Callback() {
        @Override
        public void onScanResult(BluetoothDevice device, String name, int rssi) {
            if (!calibrating) return;
            String addr = device.getAddress();
            if (addr == null || !addr.equalsIgnoreCase(targetMac)) return;

            currentRssi = rssi;
            rssiValid = true;

            if (stage == 1 && !baseReady) {
                long now = System.currentTimeMillis();
                if (baseRssiList.isEmpty() || now - lastBaseRssiAt >= 400) {
                    baseRssiList.add(rssi);
                    lastBaseRssiAt = now;
                    updateStep1Quality();
                    maybeFinishBase();
                }
            }
            if (stage == 2) {
                runOnUiThread(() -> updateWalkUi());
            }
            if (stage == 4) {
                runOnUiThread(() -> updateVerifyUi());
                checkTrigger(rssi);
            }
        }

        @Override
        public void onScanState(boolean s) {}

        @Override
        public void onLog(String line) {}
    };

    private void updateStep1Quality() {
        runOnUiThread(() -> {
            if (baseRssiList.isEmpty()) return;
            int last = baseRssiList.get(baseRssiList.size() - 1);
            if (last >= -50) { tvStep1Quality.setText("优秀"); tvStep1Quality.setTextColor(0xFF43A047); }
            else if (last >= -65) { tvStep1Quality.setText("良好"); tvStep1Quality.setTextColor(0xFF7CB342); }
            else if (last >= -80) { tvStep1Quality.setText("一般"); tvStep1Quality.setTextColor(0xFFFB8C00); }
            else { tvStep1Quality.setText("较差"); tvStep1Quality.setTextColor(0xFFE53935); }
        });
    }

    // RSSI->距离(double)，和主界面档位对齐
    private static double rssiToMeterDouble(int rssi) {
        if (rssi >= -45) return 0.5;
        if (rssi >= -52) return 1.0;
        if (rssi >= -58) return 2.0;
        if (rssi >= -64) return 3.0;
        if (rssi >= -70) return 5.0;
        if (rssi >= -76) return 8.0;
        if (rssi >= -82) return 12.0;
        return 15.0;
    }

    private void maybeFinishBase() {
        if (baseReady) return;
        if (basePoints.size() >= MIN_GPS_POINTS) {
            baseReady = true;
            handler.removeCallbacks(baseTimeoutRunnable);
            computeBase();
        }
    }

    private void computeBase() {
        double latSum = 0, lngSum = 0;
        for (Location l : basePoints) { latSum += l.getLatitude(); lngSum += l.getLongitude(); }
        double avgLat = latSum / basePoints.size();
        double avgLng = lngSum / basePoints.size();

        int dropIdx = 0;
        double maxDist = -1;
        for (int i = 0; i < basePoints.size(); i++) {
            double d = haversine(basePoints.get(i).getLatitude(), basePoints.get(i).getLongitude(), avgLat, avgLng);
            if (d > maxDist) { maxDist = d; dropIdx = i; }
        }
        latSum = 0; lngSum = 0;
        for (int i = 0; i < basePoints.size(); i++) {
            if (i == dropIdx) continue;
            latSum += basePoints.get(i).getLatitude();
            lngSum += basePoints.get(i).getLongitude();
        }
        baseLat = latSum / (basePoints.size() - 1);
        baseLng = lngSum / (basePoints.size() - 1);

        // 标定完成：短震动，进入采样
        vibrateShort();
        stage = 2;
        walkStart = System.currentTimeMillis();
        samples.clear();
        lastVibrateDist = 0;
        maxWalkDist = 0;
        invalidCount = 0;
        lastRssiSampleAt = 0;

        // 用当前GPS位置作为行走起点(0米)，避免标定阶段GPS漂移导致车旁就显示10米
        Location cur = getLastKnownGoodLocation();
        if (cur != null) { walkStartLat = cur.getLatitude(); walkStartLng = cur.getLongitude(); }
        maxWalkDist = 0;
        showStep(2);
        btnFinishSample.setEnabled(false);
        progressStep2.setProgress(0);
        tvStep2Status.setText("基准标定完成！请直线匀速远离车辆");
        tvSampleProgress.setText("已采集有效样本 0 组");
        updateWalkUi();

        handler.removeCallbacks(walkTimeoutRunnable);
        handler.postDelayed(walkTimeoutRunnable, WALK_TIMEOUT_MS);
        handler.removeCallbacks(sampleRunnable);
        handler.postDelayed(sampleRunnable, 1000);
        noOpTimerStart = System.currentTimeMillis();
    }

    // ================== 第二步：行走采样 ==================

    private final Runnable sampleRunnable = new Runnable() {
        @Override
        public void run() {
            if (stage != 2) return;
            long now = System.currentTimeMillis();
            Location loc = getLastKnownGoodLocation();
            if (loc == null) {
                invalidCount++;
                if (invalidCount >= 15) {
                    stopScanAndLocation();
                    stage = 0;
                    calibrating = false;
                    btnFinishSample.setEnabled(false);
                    tvStep2Status.setText("定位信号中断，校准失败，请换个位置重试");
                    return;
                }
                tvStep2Status.setText("定位信号弱… 第 " + invalidCount + " 秒");
                updateWalkUi();
            } else {
                double rawDist = haversine(walkStartLat, walkStartLng, loc.getLatitude(), loc.getLongitude());
                // GPS防跳变：距离只增不减（往外走不会突然变近），新距离比最大记录小30%以上视为GPS漂移，忽略
                if (rawDist > maxWalkDist) maxWalkDist = rawDist;
                double dist = (rawDist >= maxWalkDist * 0.7) ? rawDist : maxWalkDist;
                collectSample(now, dist);
            }
            handler.postDelayed(this, 1000);
        }
    };

    private void collectSample(long now, double dist) {
        if (!rssiValid || currentRssi <= -100) {
            invalidCount++;
            tvStep2Status.setText("未收到车辆信号，请确认车辆蓝牙在线");
            return;
        }
        if (!samples.isEmpty()) {
            int last = samples.get(samples.size() - 1).rssi;
            if (Math.abs(currentRssi - last) > 8) {
                invalidCount++;
                tvStep2Status.setText("信号波动过大，请尽量走直线避开遮挡");
                updateWalkUi();
                return;
            }
        }
        samples.add(new CalibrationHelper.CalibSample(dist, currentRssi));

        if (dist - lastVibrateDist >= 5.0) {
            lastVibrateDist = dist;
            vibrateShort();
        }

        updateWalkUi();

        if (dist >= targetDistance && samples.size() >= targetSample) {
            finishSampling();
            return;
        }
        if (dist >= earlyDistance && samples.size() >= earlySample) {
            btnFinishSample.setEnabled(true);
            vibrateShort();
        }
    }

    private void updateWalkUi() {
        double dist = maxWalkDist;
        Location loc = getLastKnownGoodLocation();
        if (loc != null) {
            double rawDist = haversine(walkStartLat, walkStartLng, loc.getLatitude(), loc.getLongitude());
            if (rawDist > maxWalkDist) maxWalkDist = rawDist;
            dist = (rawDist >= maxWalkDist * 0.7) ? rawDist : maxWalkDist;
        }
        tvCurDistance.setText(String.format("%.1f", dist));
        if (rssiValid) {
            tvCurRssi.setText(String.valueOf(currentRssi));
            int q = signalQuality(currentRssi);
            if (q >= 3) { tvSignalQuality.setText("优秀"); tvSignalQuality.setTextColor(0xFF43A047); }
            else if (q == 2) { tvSignalQuality.setText("良好"); tvSignalQuality.setTextColor(0xFF7CB342); }
            else if (q == 1) { tvSignalQuality.setText("一般"); tvSignalQuality.setTextColor(0xFFFB8C00); }
            else { tvSignalQuality.setText("较差"); tvSignalQuality.setTextColor(0xFFE53935); }
        } else {
            tvCurRssi.setText("--");
            tvSignalQuality.setText("--");
            tvSignalQuality.setTextColor(0xFF999999);
        }
        String guide = (settings.isQuickCalibration() ? "快速模式：走到 5 米即可" : "建议走到 10 米外")
                + "，走得越远校准越精准";
        if (invalidCount > 0 && !samples.isEmpty()
                && Math.abs(currentRssi - samples.get(samples.size() - 1).rssi) > 8) {
            guide = "信号波动较大，校准精度可能不佳，请走直线避开遮挡";
            tvStep2Guide.setTextColor(0xFFFB8C00);
        } else {
            tvStep2Guide.setTextColor(0xFF666666);
        }
        tvStep2Guide.setText(guide);
        tvSampleProgress.setText("已采集有效样本 " + samples.size() + " 组"
                + (btnFinishSample.isEnabled() ? "（可完成采样）" : ""));
        double pct = Math.max(dist / targetDistance, samples.size() * 1.0 / targetSample);
        progressStep2.setProgress((int) Math.min(100, pct * 100));
    }

    // ================== 第三步：拟合 ==================

    private void finishSampling() {
        if (stage != 2) return;
        stopScanAndLocation();
        stage = 3;
        calibrating = false;
        handler.removeCallbacks(sampleRunnable);
        handler.removeCallbacks(walkTimeoutRunnable);

        if (samples.size() < 6) {
            restartCalibration();
            Toast.makeText(this, "采样数据不足（仅 " + samples.size() + " 组），请走更远一点重试", Toast.LENGTH_LONG).show();
            return;
        }

        showStep(3);
        tvFitStatus.setText("正在根据采样数据拟合信号模型…");

        // 拟合 1.5 秒过渡后进入验证
        handler.postDelayed(() -> {
            CalibrationHelper.FitResult fit = CalibrationHelper.fitPathLoss(samples);
            if (fit == null) {
                restartCalibration();
                Toast.makeText(this, "数据质量不佳，无法拟合，请走直线并避开遮挡后重试", Toast.LENGTH_LONG).show();
                return;
            }
            lastResult = CalibrationHelper.buildResult(fit);
            startVerify();
        }, 1500);
    }

    // ================== 第四步：验证 ==================

    private void startVerify() {
        stage = 4;
        calibrating = true;
        verified = false;
        actualTriggerDist = -1;
        actualTriggerRssi = -100;
        consecutive = 0;
        invalidCount = 0;

        tvVerifyHint.setText("预计触发点: " + String.format("%.1f", lastResult.nearDistanceM)
                + " 米（信号 ≥ " + lastResult.nearThreshold + " dBm）");
        tvVerifyStatus.setText("请走向车辆，系统将自动检测触发点…");
        progressVerify.setProgress(0);
        tvVerifyDistance.setText("--.-");
        tvVerifyRssi.setText("--");
        showStep(4);

        ble.startScan();
        startLocationUpdates();

        handler.removeCallbacks(verifyTimeoutRunnable);
        handler.postDelayed(verifyTimeoutRunnable, VERIFY_TIMEOUT_MS);
        handler.removeCallbacks(verifyTicker);
        handler.postDelayed(verifyTicker, 500);
    }

    private final Runnable verifyTicker = new Runnable() {
        @Override
        public void run() {
            if (stage != 4) return;
            updateVerifyUi();
            handler.postDelayed(this, 500);
        }
    };

    private void updateVerifyUi() {
        Location loc = getLastKnownGoodLocation();
        if (loc == null) return;
        double dist = haversine(baseLat, baseLng, loc.getLatitude(), loc.getLongitude());
        tvVerifyDistance.setText(String.format("%.1f", dist));
        if (rssiValid) tvVerifyRssi.setText(String.valueOf(currentRssi));
        double pct = dist / lastResult.nearDistanceM;
        progressVerify.setProgress((int) Math.min(100, pct * 100));
        tvVerifyStatus.setText("距离车辆 " + String.format("%.1f", dist) + " 米 | 信号 "
                + (rssiValid ? currentRssi : "--") + " dBm");
    }

    /** 用新拟合参数判定触发：信号达到靠近阈值且连续达标 */
    private void checkTrigger(int rssi) {
        if (stage != 4 || verified) return;
        if (rssi >= lastResult.nearThreshold) {
            consecutive++;
            if (consecutive >= 1) {
                verified = true;
                Location loc = getLastKnownGoodLocation();
                if (loc != null) {
                    actualTriggerDist = haversine(walkStartLat, walkStartLng, loc.getLatitude(), loc.getLongitude());
                } else {
                    actualTriggerDist = -1;
                }
                actualTriggerRssi = rssi;
                handler.removeCallbacks(verifyTimeoutRunnable);
                handler.removeCallbacks(verifyTicker);
                handler.postDelayed(this::finishVerify, 400);
            }
        } else {
            consecutive = 0;
        }
    }

    private void finishVerify() {
        stopScanAndLocation();
        stage = 5;
        calibrating = false;

        double err = Math.abs(actualTriggerDist - lastResult.nearDistanceM);
        lastResult.verifyErrorM = err;
        vibrateLong();

        showResult(err <= 8.0);
    }

    private final Runnable verifyTimeoutRunnable = () -> {
        if (stage != 4) return;
        stopScanAndLocation();
        stage = 5;
        calibrating = false;
        lastResult.verifyErrorM = -1;
        showFail();
    };

    // ================== 结果分支 ==================

    /** 验证成功(误差≤1米)或跳过验证时的结果页 */
    private void showResult(boolean ok) {
        if (ok) {
            showOkResult();
        } else {
            showDeviation();
        }
    }

    private void showOkResult() {
        stage = 5;
        tvOkEnv.setText("环境判定: " + lastResult.environment + "（衰减指数 n="
                + String.format("%.1f", lastResult.modelN) + "）");
        tvOkNearDist.setText(String.format("%.1f", lastResult.nearDistanceM));
        tvOkNear.setText(lastResult.nearThreshold + " dBm");
        if (actualTriggerDist > 0) {
            tvOkActualDist.setText(String.format("%.1f", actualTriggerDist));
            tvOkActualRssi.setText(actualTriggerRssi + " dBm");
            tvOkError.setText(String.format("%.1f", Math.abs(actualTriggerDist - lastResult.nearDistanceM)));
        } else {
            tvOkActualDist.setText("跳过");
            tvOkActualRssi.setText("--");
            tvOkError.setText("--");
        }
        tvOkHyster.setText("滞回区间: " + lastResult.hysteresis + " dB");
        tvOkConf.setText("置信度: " + (int) (lastResult.confidence * 100) + "%");
        showStep(5);
        cardResultOk.setVisibility(View.VISIBLE);
        cardResultDev.setVisibility(View.GONE);
        cardResultFail.setVisibility(View.GONE);

        if (settings.isAutoApplyAfterVerify()) {
            handler.postDelayed(() -> applyResult(false), 800);
        }
    }

    private void showDeviation() {
        stage = 5;
        tvDevDesc.setText("实际触发距离与目标差距较大（预设 "
                + String.format("%.1f", lastResult.nearDistanceM) + " 米，实测 "
                + (actualTriggerDist > 0 ? String.format("%.1f", actualTriggerDist) : "未记录")
                + " 米）");
        showStep(5);
        cardResultOk.setVisibility(View.GONE);
        cardResultDev.setVisibility(View.VISIBLE);
        cardResultFail.setVisibility(View.GONE);
    }

    private void showFail() {
        stage = 5;
        tvFailDesc.setText("走到车辆附近（2 米内）仍未触发，建议检查遮挡后重新校准");
        showStep(5);
        cardResultOk.setVisibility(View.GONE);
        cardResultDev.setVisibility(View.GONE);
        cardResultFail.setVisibility(View.VISIBLE);
    }

    // ================== 应用 / 重校 / 保存地点 ==================

    private void applyResult(boolean force) {
        if (lastResult == null) return;
        settings.setRssiThreshold(lastResult.nearThreshold);
        settings.setRssiLeaveThreshold(lastResult.farThreshold);
        settings.saveCalibrationHistory(lastResult);
        // v2.48: 写校准详情日志（供AI分析）
        try {
            AppLogger.get(this).logState("CALIB", "校准完成: A=" + String.format("%.1f", lastResult.modelA)
                + " n=" + String.format("%.2f", lastResult.modelN)
                + " env=" + lastResult.environment
                + " near=" + lastResult.nearThreshold + "dBm(" + String.format("%.1f", lastResult.nearDistanceM) + "m)"
                + " far=" + lastResult.farThreshold + "dBm(" + String.format("%.1f", lastResult.farDistanceM) + "m)"
                + " hyst=" + lastResult.hysteresis + "dB"
                + " samples=" + samples.size()
                + " verifyErr=" + String.format("%.1f", lastResult.verifyErrorM) + "m" );
        } catch (Exception ignored) {}
        Toast.makeText(this, "已应用：靠近 " + lastResult.nearThreshold + " dBm / 离开 "
                + lastResult.farThreshold + " dBm（滞回 " + lastResult.hysteresis + " dB）", Toast.LENGTH_LONG).show();
        finish();
    }

    private void restartCalibration() {
        stopScanAndLocation();
        stage = 0;
        calibrating = false;
        basePoints.clear();
        baseRssiList.clear();
        samples.clear();
        baseReady = false;
        lastResult = null;
        verified = false;
        invalidCount = 0;
        consecutive = 0;
        currentRssi = -100;
        rssiValid = false;

        showStep(1);
        btnBegin.setEnabled(true);
        btnBegin.setText("开始标定");
        tvStep1Points.setText("已采集 0/" + MIN_GPS_POINTS + " 点");
        tvStep1Quality.setText("--");
        tvStep1Quality.setTextColor(0xFF999999);
        progressStep1.setProgress(0);
    }

    /** 保存常用地点：写入历史第一条的 place 字段 */
    private void savePlace(String place) {
        if (lastResult == null) return;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(settings.getCalibrationHistory());
            if (arr.length() > 0) {
                arr.getJSONObject(0).put("place", place);
            }
            // 直接写入 SharedPreferences
            settings.getSp().edit().putString("calib_history_json", arr.toString()).apply();
            Toast.makeText(this, "已保存为「" + place + "」地点", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** v2.49: 保存为场景 + 写校准日志 + 询问上传GitHub */
    private void saveAsScene() {
        if (lastResult == null) return;
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("如：家地库 / 公司停车场");
        String cur = settings.getActiveSceneName();
        if (cur != null && !cur.isEmpty()) et.setText(cur);
        new AlertDialog.Builder(this)
            .setTitle("保存为场景")
            .setView(et)
            .setPositiveButton("保存并应用", (d,w) -> {
                String name = et.getText().toString().trim();
                if (name.isEmpty()) name = "新场景";
                settings.setRssiThreshold(lastResult.nearThreshold);
                settings.setRssiLeaveThreshold(lastResult.farThreshold);
                settings.saveCalibrationHistory(lastResult);
                settings.saveScene(name, lastResult, baseLat, baseLng);
                writeCalibLog(name);
                Toast.makeText(this, "已保存场景：" + name, Toast.LENGTH_SHORT).show();
                askUploadCalibLog(name);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** v2.49: 写校准详情日志到 calibration_logs/，7天自动清理 */
    private File writeCalibLog(String sceneName) {
        try {
            File dir = new File(getExternalFilesDir(null), "calibration_logs");
            if (!dir.exists()) dir.mkdirs();
            cleanOldCalibLogs(dir);
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.CHINA);
            String fname = "calib_" + sdf.format(new java.util.Date()) + ".txt";
            File f = new File(dir, fname);
            StringBuilder sb = new StringBuilder();
            sb.append("===== 校准日志 =====\n");
            sb.append("时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(new java.util.Date())).append("\n");
            sb.append("场景: ").append(sceneName).append("\n");
            sb.append("基准坐标: (").append(String.format(java.util.Locale.US, "%.6f", baseLat)).append(", ").append(String.format(java.util.Locale.US, "%.6f", baseLng)).append(")\n");
            sb.append("模型A: ").append(String.format(java.util.Locale.US, "%.2f", lastResult.modelA)).append(" dBm\n");
            sb.append("模型n: ").append(String.format(java.util.Locale.US, "%.2f", lastResult.modelN)).append("\n");
            sb.append("环境: ").append(lastResult.environment).append("\n");
            sb.append("靠近阈值: ").append(lastResult.nearThreshold).append(" dBm (").append(String.format(java.util.Locale.US, "%.1f", lastResult.nearDistanceM)).append("m)\n");
            sb.append("离开阈值: ").append(lastResult.farThreshold).append(" dBm (").append(String.format(java.util.Locale.US, "%.1f", lastResult.farDistanceM)).append("m)\n");
            sb.append("滞回: ").append(lastResult.hysteresis).append(" dB\n");
            sb.append("置信度: ").append((int)(lastResult.confidence*100)).append("%\n");
            sb.append("验证误差: ").append(String.format(java.util.Locale.US, "%.1f", lastResult.verifyErrorM)).append(" m\n");
            sb.append("样本数: ").append(samples.size()).append("\n");
            sb.append("\n===== 采样样本（距离m, RSSI dBm）=====\n");
            for (CalibrationHelper.CalibSample s : samples) {
                sb.append(String.format(java.util.Locale.US, "%.2f", s.distanceM)).append(", ").append(s.rssi).append("\n");
            }
            java.io.FileWriter fw = new java.io.FileWriter(f, false);
            fw.write(sb.toString());
            fw.close();
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    /** 清理7天前的校准日志 */
    private void cleanOldCalibLogs(File dir) {
        long cutoff = System.currentTimeMillis() - 7L*24*3600*1000;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File x : fs) { if (x.isFile() && x.lastModified() < cutoff) x.delete(); }
    }

    /** 询问是否上传本次校准日志到GitHub calibration_logs/ */
    private void askUploadCalibLog(String sceneName) {
        String token = settings.getGithubToken();
        if (token == null || token.isEmpty()) { Toast.makeText(this, "未配置GitHub Token，日志仅存本地", Toast.LENGTH_SHORT).show(); return; }
        File dir = new File(getExternalFilesDir(null), "calibration_logs");
        File[] fs = dir.listFiles();
        if (fs == null || fs.length == 0) return;
        File latest = fs[0];
        for (File x : fs) if (x.lastModified() > latest.lastModified()) latest = x;
        final File toUpload = latest;
        new AlertDialog.Builder(this)
            .setTitle("上传校准日志？")
            .setMessage("本次校准日志已保存本地，是否上传到 GitHub calibration_logs/ 文件夹供AI分析？")
            .setPositiveButton("上传", (d,w) -> {
                try {
                    StringBuilder rc = new StringBuilder();
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(toUpload), "UTF-8"));
                    String rline; while ((rline = br.readLine()) != null) rc.append(rline).append("\n");
                    br.close();
                    String content = rc.toString();
                    String path = "calibration_logs/" + toUpload.getName();
                    LogUploader.uploadToPath(this, token, path, content, new LogUploader.UploadCallback() {
                        public void onSuccess(String url) { runOnUiThread(() -> Toast.makeText(CalibrationActivity.this, "已上传: " + path, Toast.LENGTH_LONG).show()); }
                        public void onFailure(String err) { runOnUiThread(() -> Toast.makeText(CalibrationActivity.this, "上传失败: " + err, Toast.LENGTH_LONG).show()); }
                    });
                } catch (Exception e) { Toast.makeText(this, "读取日志失败", Toast.LENGTH_SHORT).show(); }
            })
            .setNegativeButton("仅本地", null)
            .show();
    }

    // ================== 工具方法 ==================

    private void showStep(int s) {
        cardStep1.setVisibility(s == 1 ? View.VISIBLE : View.GONE);
        cardStep2.setVisibility(s == 2 ? View.VISIBLE : View.GONE);
        cardStep3.setVisibility(s == 3 ? View.VISIBLE : View.GONE);
        cardStep4.setVisibility(s == 4 ? View.VISIBLE : View.GONE);
        cardResultOk.setVisibility(View.GONE);
        cardResultDev.setVisibility(View.GONE);
        cardResultFail.setVisibility(View.GONE);

        stepDot1.setBackgroundResource(s >= 1 ? R.drawable.bg_step_dot_active : R.drawable.bg_step_dot_inactive);
        stepDot2.setBackgroundResource(s >= 2 ? R.drawable.bg_step_dot_active : R.drawable.bg_step_dot_inactive);
        stepDot3.setBackgroundResource(s >= 3 ? R.drawable.bg_step_dot_active : R.drawable.bg_step_dot_inactive);
        stepDot4.setBackgroundResource(s >= 4 ? R.drawable.bg_step_dot_active : R.drawable.bg_step_dot_inactive);
        stepText1.setTextColor(s >= 1 ? 0xFF1A1A1A : 0xFF999999);
        stepText2.setTextColor(s >= 2 ? 0xFF1A1A1A : 0xFF999999);
        stepText3.setTextColor(s >= 3 ? 0xFF1A1A1A : 0xFF999999);
        stepText4.setTextColor(s >= 4 ? 0xFF1A1A1A : 0xFF999999);
    }

    private void showError(String msg) {
        TextView tvError = findViewById(R.id.tv_error);
        tvError.setVisibility(View.VISIBLE);
        tvError.setText(msg);
    }

    private int signalQuality(int rssi) {
        if (rssi >= -50) return 3;
        if (rssi >= -62) return 2;
        if (rssi >= -75) return 1;
        return 0;
    }

    private void vibrateShort() {
        if (!settings.isVibrationEnabled()) return;
        try {
            if (vibrator == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(80);
            }
        } catch (Exception ignored) {}
    }

    private void vibrateLong() {
        if (!settings.isVibrationEnabled()) return;
        try {
            if (vibrator == null) return;
            long[] pattern = {0, 120, 60, 120};
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(pattern, -1);
            }
        } catch (Exception ignored) {}
    }

    /** Haversine 距离（米） */
    private static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * R * Math.asin(Math.sqrt(a));
    }

    private void startLocationUpdates() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) return;
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListener);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0, locationListener);
            }
        } catch (Exception ignored) {}
    }

    private Location getLastKnownGoodLocation() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) return null;
            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location net = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (gps != null && (!gps.hasAccuracy() || gps.getAccuracy() <= 8.0f)) return gps;
            if (net != null && (!net.hasAccuracy() || net.getAccuracy() <= 8.0f)) return net;
            return gps != null ? gps : net;
        } catch (Exception e) {
            return null;
        }
    }

    private void stopScanAndLocation() {
        try { ble.stopScan(); } catch (Exception ignored) {}
        try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
        handler.removeCallbacks(baseTimeoutRunnable);
        handler.removeCallbacks(walkTimeoutRunnable);
        handler.removeCallbacks(sampleRunnable);
        handler.removeCallbacks(verifyTimeoutRunnable);
        handler.removeCallbacks(verifyTicker);
        handler.removeCallbacks(noOpRunnable);
    }

    private final Runnable walkTimeoutRunnable = () -> {
        if (stage != 2) return;
        stopScanAndLocation();
        stage = 0;
        calibrating = false;
        btnFinishSample.setEnabled(false);
        tvStep2Status.setText("采样超时（2分钟），校准失败，请重试");
    };

    /** 无操作自动退出：任何交互重置计时 */
    private void resetNoOpTimer() {
        handler.removeCallbacks(noOpRunnable);
        handler.postDelayed(noOpRunnable, NO_OP_TIMEOUT_MS);
    }

    private final Runnable noOpRunnable = () -> {
        if (stage >= 1 && stage <= 4) {
            stopScanAndLocation();
            stage = 0;
            calibrating = false;
            Toast.makeText(this, "较长时间无操作，校准已自动退出，参数未改变", Toast.LENGTH_SHORT).show();
            finish();
        }
    };
}
