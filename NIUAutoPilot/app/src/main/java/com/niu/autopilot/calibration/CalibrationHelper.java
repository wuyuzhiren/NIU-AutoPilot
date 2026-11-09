package com.niu.autopilot.calibration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 纯数学算法：平滑、拐点、推荐值、路径损耗拟合。零 Android 依赖。
 * v2.42 新增：基于 GPS 距离 + RSSI 配对样本的最小二乘拟合，
 * 模型：RSSI(dBm) = A - 10·n·log10(d)，d 为米。
 */
public class CalibrationHelper {

    /** 一对「距离 + 信号」样本 */
    public static class CalibSample {
        public double distanceM;
        public int rssi;
        public CalibSample(double distanceM, int rssi) {
            this.distanceM = distanceM;
            this.rssi = rssi;
        }
    }

    /** 拟合结果 */
    public static class FitResult {
        public double a;          // 截距 = 1米处信号强度 dBm
        public double n;          // 衰减指数
        public double r2;         // 决定系数（拟合质量，0~1）
        public int sampleCount;
        public FitResult(double a, double n, double r2, int count) {
            this.a = a; this.n = n; this.r2 = r2; this.sampleCount = count;
        }
    }

    /** 1. 中值滤波平滑（窗口=3，输出长度 n-2） */
    public static List<Float> smoothData(List<Integer> raw) {
        List<Float> smoothed = new ArrayList<>();
        if (raw == null || raw.size() < 3) {
            if (raw != null) {
                for (Integer v : raw) smoothed.add(v.floatValue());
            }
            return smoothed;
        }
        for (int i = 1; i < raw.size() - 1; i++) {
            float[] temp = {raw.get(i - 1), raw.get(i), raw.get(i + 1)};
            Arrays.sort(temp);
            smoothed.add(temp[1]); // 取中位数
        }
        return smoothed;
    }

    /** 2. 计算标准差（用于评估波动） */
    public static float calculateStdDev(List<Float> data) {
        if (data == null || data.isEmpty()) return 0;
        float sum = 0;
        for (float v : data) sum += v;
        float mean = sum / data.size();
        float sqDiff = 0;
        for (float v : data) sqDiff += (v - mean) * (v - mean);
        return (float) Math.sqrt(sqDiff / data.size());
    }

    /** 3. 寻找拐点（信号急剧变强的第一个位置） */
    private static int findInflectionPoint(List<Float> smoothed) {
        if (smoothed.size() < 4) return smoothed.size() / 2;
        float maxSlope = -999;
        int index = 0;
        for (int i = 3; i < smoothed.size() - 3; i++) {
            float before = (smoothed.get(i - 3) + smoothed.get(i - 2) + smoothed.get(i - 1)) / 3f;
            float after = (smoothed.get(i + 1) + smoothed.get(i + 2) + smoothed.get(i + 3)) / 3f;
            float slope = after - before;
            if (slope > maxSlope) {
                maxSlope = slope;
                index = i;
            }
        }
        return index;
    }

    /** 4. 主推荐逻辑（公开调用，纯 RSSI 时序兜底方案，无 GPS 时用） */
    public static CalibrationResult recommendParams(List<Integer> rawRSSI) {
        List<Integer> valid = new ArrayList<>();
        if (rawRSSI != null) {
            for (int v : rawRSSI) {
                if (v > -100) valid.add(v);
            }
        }
        if (valid.size() < 10) {
            return new CalibrationResult(-60, -80, 20, 0.2f);
        }

        List<Float> smooth = smoothData(valid);
        if (smooth.isEmpty()) return new CalibrationResult(-60, -80, 20, 0.3f);

        int inflection = findInflectionPoint(smooth);

        // A. 靠近阈值 (Near)
        int startNear = (int) (inflection + (smooth.size() - inflection) * 0.3);
        if (startNear >= smooth.size()) startNear = smooth.size() - 1;
        float sumNear = 0;
        for (int i = startNear; i < smooth.size(); i++) sumNear += smooth.get(i);
        float avgNear = sumNear / (smooth.size() - startNear);
        int recommendedNear = Math.min((int) (avgNear + 3), -40);

        // B. 离开阈值 (Far)
        int endFar = Math.min(3, smooth.size());
        float sumFar = 0;
        for (int i = 0; i < endFar; i++) sumFar += smooth.get(i);
        float avgFar = sumFar / endFar;
        int recommendedFar = Math.max((int) (avgFar - 3), -95);

        // C. 滞回区间
        float std = calculateStdDev(smooth);
        int recommendedHysteresis = Math.max((int) (std * 1.8f), 10);
        if (recommendedHysteresis > (recommendedNear - recommendedFar - 5)) {
            recommendedHysteresis = recommendedNear - recommendedFar - 5;
        }
        if (recommendedHysteresis < 5) recommendedHysteresis = 5;

        // D. 置信度
        float confidence = 0.8f;
        if (std > 8) confidence = 0.5f;
        if (std > 12) confidence = 0.3f;

        return new CalibrationResult(recommendedNear, recommendedFar, recommendedHysteresis, confidence);
    }

    // ================= v2.42: GPS 距离 + RSSI 路径损耗拟合 =================

    /** 5. 最小二乘拟合：RSSI = a + b·log10(d)，返回 FitResult(a, n=-b/10, r2) */
    public static FitResult fitPathLoss(List<CalibSample> samples) {
        if (samples == null || samples.size() < 6) return null;

        List<double[]> pts = new ArrayList<>();
        for (CalibSample s : samples) {
            if (s.distanceM < 0.8 || s.distanceM > 50) continue; // 排除过近/过远
            if (s.rssi > -30 || s.rssi < -105) continue;          // 排除明显异常信号
            pts.add(new double[]{Math.log10(s.distanceM), s.rssi});
        }
        if (pts.size() < 6) return null;

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        int n = pts.size();
        for (double[] p : pts) {
            sumX += p[0];
            sumY += p[1];
            sumXY += p[0] * p[1];
            sumX2 += p[0] * p[0];
        }
        double xBar = sumX / n, yBar = sumY / n;
        double denom = sumX2 - n * xBar * xBar;
        if (Math.abs(denom) < 1e-9) return null;
        double b = (sumXY - n * xBar * yBar) / denom;
        double a = yBar - b * xBar;

        // 决定系数 R²
        double ssTot = 0, ssRes = 0;
        for (double[] p : pts) {
            double pred = a + b * p[0];
            ssTot += (p[1] - yBar) * (p[1] - yBar);
            ssRes += (p[1] - pred) * (p[1] - pred);
        }
        double r2 = ssTot < 1e-9 ? 0 : Math.max(0, 1 - ssRes / ssTot);

        // 物理约束 v2.48：衰减指数 n 正常 2.0~4.0，A 正常 -55~-40（1米处信号）
        double nVal = -b / 10.0;
        if (nVal < 2.0) nVal = 2.0;
        if (nVal > 4.0) nVal = 4.0;
        if (a < -55) a = -55;
        if (a > -40) a = -40;

        return new FitResult(a, nVal, r2, pts.size());
    }

    /** 6. 由模型反算指定距离处的 RSSI 阈值 */
    public static int rssiAtDistance(FitResult fit, double distanceM) {
        if (fit == null || distanceM < 0.5) return -60;
        double rssi = fit.a - 10 * fit.n * Math.log10(distanceM);
        return (int) Math.round(rssi);
    }

    /** 7. 环境判定：根据衰减指数 n 归类 */
    public static String judgeEnvironment(double n) {
        if (n <= 2.2) return "开阔地";
        if (n <= 3.2) return "半遮挡区域";
        return "室内地库";
    }

    /** 8. 由拟合结果生成推荐参数（靠近5米 / 离开12米，带余量） */
    public static CalibrationResult buildResult(FitResult fit) {
        if (fit == null) return new CalibrationResult(-60, -80, 20, 0.2f);

        // 靠近阈值：5米处模型值 + 2dB 余量（宁灵敏勿迟钝）
        int near = rssiAtDistance(fit, 5.0) + 2;
        // 离开阈值：12米处模型值 - 2dB 余量
        int far = rssiAtDistance(fit, 12.0) - 2;

        // 越界保护 v2.48：正常 near[-70,-50] far[-85,-65]，写反曾导致 near 永远被推到 -35
        near = Math.max(-70, Math.min(-50, near));
        far = Math.max(-85, Math.min(-65, far));
        // 保证滞回 >= 8dB 且 <= 30dB
        int hysteresis = near - far;
        if (hysteresis < 8) { far = near - 8; hysteresis = 8; }
        if (hysteresis > 30) { far = near - 30; hysteresis = 30; }

        // 置信度：R² 越高越可信
        float confidence = (float) Math.max(0.3, Math.min(0.95, fit.r2 * 0.8 + 0.15));

        String env = judgeEnvironment(fit.n);
        return new CalibrationResult(near, far, hysteresis, confidence,
                5.0, 12.0, env, fit.a, fit.n);
    }
}
