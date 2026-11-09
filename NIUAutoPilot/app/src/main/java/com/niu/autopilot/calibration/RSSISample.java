package com.niu.autopilot.calibration;

/**
 * 单次 RSSI 采样点：时间戳 + 信号强度。
 * 注意：未收到包时绝不往列表里塞 -100，只记录实际收到的值，
 * 这样 CalibrationHelper 的 valid 过滤才干净。
 */
public class RSSISample {
    public long timestamp;   // System.currentTimeMillis()
    public int rssi;         // 原始信号强度（-dBm 负数）

    public RSSISample(long timestamp, int rssi) {
        this.timestamp = timestamp;
        this.rssi = rssi;
    }
}
