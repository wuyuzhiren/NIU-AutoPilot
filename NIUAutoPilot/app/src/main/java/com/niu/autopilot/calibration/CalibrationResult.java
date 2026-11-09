package com.niu.autopilot.calibration;

/**
 * 校准算法输出结果。
 * 新增：触发距离、环境判定、路径损耗模型参数（用于历史回退和AI微调）。
 */
public class CalibrationResult {
    public int nearThreshold;      // 推荐靠近阈值 (-dBm)
    public int farThreshold;       // 推荐离开阈值 (-dBm)
    public int hysteresis;         // 推荐滞回区间 (dB)
    public float confidence;       // 0~1 置信度（基于数据波动/拟合质量）

    public double nearDistanceM;   // 靠近触发距离（米），默认约 5.0
    public double farDistanceM;    // 离开触发距离（米），默认约 11.8
    public String environment;     // 环境判定：开阔地 / 半遮挡区域 / 室内地库
    public double modelA;          // 路径损耗模型 A（1米处信号 dBm）
    public double modelN;          // 路径损耗模型 n（衰减指数）
    public double verifyErrorM;    // 验证环节实测触发距离误差（米），-1 = 未验证/跳过

    public CalibrationResult(int near, int far, int hyster, float conf) {
        this.nearThreshold = near;
        this.farThreshold = far;
        this.hysteresis = hyster;
        this.confidence = conf;
        this.nearDistanceM = 5.0;
        this.farDistanceM = 11.8;
        this.environment = "半遮挡区域";
        this.modelA = -45;
        this.modelN = 2.5;
        this.verifyErrorM = -1;
    }

    public CalibrationResult(int near, int far, int hyster, float conf,
                             double nearDist, double farDist, String env,
                             double a, double n) {
        this.nearThreshold = near;
        this.farThreshold = far;
        this.hysteresis = hyster;
        this.confidence = conf;
        this.nearDistanceM = nearDist;
        this.farDistanceM = farDist;
        this.environment = env;
        this.modelA = a;
        this.modelN = n;
        this.verifyErrorM = -1;
    }
}
