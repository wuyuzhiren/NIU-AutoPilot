package com.niu.autopilot;

import java.util.ArrayDeque;

/**
 * 信号趋势检测器 v2.72
 * v2.72(P0): 连续达标次数默认提高到 3（靠近）/ 5（离开），配合阈值迟滞防 RSSI 抖动翻转；
 *           提前触发区（信号持续增强）仍保留 2 次增强判定，兼顾"蓝色区间就开机"。
 */
public class TrendDetector {
    private static final int TREND_WINDOW = 4;
    private static final float STRONG_SIGNAL_MARGIN = 3.0f; // v2.29: 从5dB降到3dB
    /** v2.71: 提前触发区宽度(dB)——信号进入靠近阈值下方该区间且持续增强时即触发开机 */
    private static final float EARLY_MARGIN = 8.0f;
    /** v2.71: 提前触发所需的最小上升斜率(dB/采样)，防止信号抖动误触发 */
    private static final float EARLY_SLOPE = 2.0f;
    /** v2.71: 提前触发需连续N次采样处于增强趋势，楼上有信号波动也不会误开机 */
    private static final int EARLY_CONSECUTIVE = 2;
    /** v2.72: 离开确认需要更多连续次数，比靠近更严格，防止短暂波动就误关机 */
    private static final int FAR_CONSECUTIVE_REQUIRED = 5;

    private ArrayDeque<Float> trendSamples = new ArrayDeque<>();
    private int consecutiveNear = 0;
    private int consecutiveFar = 0;
    private int consecutiveRequired = 3; // v2.72: 默认3次，防抖动翻转
    /** v2.81: 最近一次靠近触发的判定原因（STRONG强信号 / EARLY提前趋势 / NORMAL连续达标），写入日志便于诊断 */
    private volatile String lastNearReason = "NORMAL";

    public synchronized boolean checkNear(float smoothedRssi, int nearThreshold) {
        addTrendSample(smoothedRssi);
        boolean isNear = smoothedRssi >= nearThreshold;
        if (isNear) {
            consecutiveNear++;
            // v2.29: 强信号(超阈值3dB)立即触发
            if (smoothedRssi >= nearThreshold + STRONG_SIGNAL_MARGIN) {
                lastNearReason = "STRONG";
                return true;
            }
            // 普通信号：连续N次达标即触发
            if (consecutiveNear >= consecutiveRequired) {
                lastNearReason = "NORMAL";
                return true;
            }
            return false;
        }
        // v2.71: 信号尚未到阈值，但进入提前触发区且持续增强——按"靠近趋势"触发
        // 连续2次增强才触发，楼上有信号波动也不会误开机
        if (smoothedRssi >= nearThreshold - EARLY_MARGIN && getSlope() >= EARLY_SLOPE) {
            consecutiveNear++;
            if (consecutiveNear >= EARLY_CONSECUTIVE) {
                lastNearReason = "EARLY";
                return true;
            }
            return false;
        }
        consecutiveNear = 0;
        return false;
    }

    public synchronized boolean checkFar(float smoothedRssi, int farThreshold) {
        boolean isFar = smoothedRssi < farThreshold;
        if (isFar) {
            consecutiveFar++;
        } else {
            consecutiveFar = 0;
            return false;
        }
        // 强离开信号(低于阈值3dB)立即触发
        if (smoothedRssi <= farThreshold - STRONG_SIGNAL_MARGIN) {
            return true;
        }
        // v2.72: 离开需连续5次达标，防短暂波动误关机
        return consecutiveFar >= FAR_CONSECUTIVE_REQUIRED;
    }

    private void addTrendSample(float value) {
        trendSamples.addLast(value);
        if (trendSamples.size() > TREND_WINDOW) {
            trendSamples.removeFirst();
        }
    }

    public synchronized float getSlope() {
        if (trendSamples.size() < 3) return 0f;
        Float[] arr = trendSamples.toArray(new Float[0]);
        int n = arr.length;
        float sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += arr[i];
            sumXY += i * arr[i];
            sumX2 += i * i;
        }
        float denom = n * sumX2 - sumX * sumX;
        if (Math.abs(denom) < 0.001f) return 0f;
        return (n * sumXY - sumX * sumY) / denom;
    }

    public synchronized void reset() {
        trendSamples.clear();
        consecutiveNear = 0;
        consecutiveFar = 0;
    }

    public void setConsecutiveRequired(int n) {
        this.consecutiveRequired = Math.max(1, n);
    }

    public synchronized int getConsecutiveNear() {
        return consecutiveNear;
    }

    /** v2.81: 最近一次靠近触发的判定原因（STRONG/EARLY/NORMAL），供日志/诊断使用 */
    public synchronized String getLastNearReason() {
        return lastNearReason;
    }

    public synchronized int getConsecutiveFar() {
        return consecutiveFar;
    }
}
