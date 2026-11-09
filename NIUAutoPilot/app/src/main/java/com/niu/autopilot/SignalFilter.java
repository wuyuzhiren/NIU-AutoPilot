package com.niu.autopilot;

import java.util.ArrayDeque;

/**
 * RSSI信号滤波器 v2.29
 * 简化：去掉中值滤波（会过滤瞬时强信号），只用高灵敏度EWMA
 * v2.0/v2.1 时代就是简单比较，信号复杂环境下反而更可靠
 */
public class SignalFilter {
    private static final float EWMA_ALPHA = 0.8f; // v2.29: 从0.6提到0.8，更跟手减少滞后

    private final float ewmaAlpha;
    private float ewmaValue = 0f;
    private boolean ewmaInitialized = false;
    private int sampleCount = 0;
    private int latestRaw = 0;

    public SignalFilter() {
        this(EWMA_ALPHA);
    }

    public SignalFilter(int windowSize) {
        this(EWMA_ALPHA); // v2.29: 忽略窗口参数，统一用高灵敏度EWMA
    }

    public SignalFilter(float ewmaAlpha) {
        this.ewmaAlpha = ewmaAlpha;
    }

    /**
     * 输入原始RSSI，返回滤波后的值
     * v2.29: 只用EWMA，不再中值滤波，保留瞬时强信号
     */
    public synchronized float filter(int rawRssi) {
        latestRaw = rawRssi;
        sampleCount++;
        if (!ewmaInitialized) {
            ewmaValue = rawRssi;
            ewmaInitialized = true;
        } else {
            ewmaValue = ewmaAlpha * rawRssi + (1 - ewmaAlpha) * ewmaValue;
        }
        return ewmaValue;
    }

    /** 获取当前滤波后的值 */
    public synchronized float getCurrent() {
        return ewmaValue;
    }

    /** 获取原始RSSI的最新值 */
    public synchronized int getLatestRaw() {
        return latestRaw;
    }

    /** 获取有效样本数 */
    public synchronized int getSampleCount() {
        return sampleCount;
    }

    /** 重置滤波器 */
    public synchronized void reset() {
        ewmaValue = 0f;
        ewmaInitialized = false;
        sampleCount = 0;
        latestRaw = 0;
    }
}
