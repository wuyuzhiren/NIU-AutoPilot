package com.niu.autopilot.vehicle

import android.graphics.PointF

/**
 * 车辆灯光点位定义
 *
 * 采用「归一化坐标 (0~1)」描述灯光在车身图中的相对位置，
 * 适配不同分辨率的车身素材：实际像素 = 坐标 × View 宽高。
 *
 * 默认坐标基于 NX Wind 45° 斜前视角车身图（车头偏左）：
 *   - 前大灯：车头左右两侧（用户视角的左侧偏上）
 *   - 尾灯：车身尾部右侧（用户视角的右侧偏下）
 *   - 仪表盘：车把中央偏上
 *   - 坐桶区域：座椅中部
 *
 * 接入时若素材视角不同，通过 [VehicleStageView.setLightPoints] 传入自定义坐标即可。
 */
data class VehicleLightPoint(
    val type: Type,
    /** 相对 X 坐标 (0.0 ~ 1.0)，以 View 宽度为基准 */
    val relX: Float,
    /** 相对 Y 坐标 (0.0 ~ 1.0)，以 View 高度为基准 */
    val relY: Float,
    /** 绘制半径 dp */
    val radiusDp: Float = 6f,
    /** 发光半径 dp（shadowLayer / glow） */
    val glowRadiusDp: Float = 16f,
    /** 基础颜色 */
    val color: Int = 0xFF00D4FF.toInt(), // 默认电光蓝
    /** 最大透明度 (0~1) */
    val maxAlpha: Float = 1.0f
) {
    enum class Type {
        HEADLAMP_LEFT,   // 左前大灯
        HEADLAMP_RIGHT,  // 右前大灯（NX Wind 单大灯可只用一个）
        TAILLAMP,        // 尾灯
        DASHBOARD_RING,  // 仪表盘辉光环
        BUCKET_AREA      // 坐桶区域提示光
    }

    companion object {
        /**
         * NX Wind 幻影紫 45° 斜前视角的默认灯光点位。
         * 这些坐标是在假设车身图占满整个 VehicleStageView 的前提下的经验值。
         *
         * 实际接入时，若车身图在 View 中有 padding 或 scaleType 不是 fitXY，
         * 需根据 [VehicleStageView.setBodyImageBounds] 修正的绘制矩形来换算。
         */
        fun defaultPoints(): List<VehicleLightPoint> = listOf(
            // 左前大灯（车头最前方，偏左上）
            VehicleLightPoint(
                type = Type.HEADLAMP_LEFT,
                relX = 0.22f, relY = 0.38f,
                radiusDp = 5f, glowRadiusDp = 20f,
                color = 0xFFFFFFFF.toInt(), maxAlpha = 0.95f
            ),
            // 尾灯（车身尾部，偏右下）
            VehicleLightPoint(
                type = Type.TAILLAMP,
                relX = 0.78f, relY = 0.52f,
                radiusDp = 4f, glowRadiusDp = 14f,
                color = 0xFFEF4444.toInt(), maxAlpha = 0.85f
            ),
            // 仪表盘辉光环（车把中央，偏上）
            VehicleLightPoint(
                type = Type.DASHBOARD_RING,
                relX = 0.35f, relY = 0.28f,
                radiusDp = 8f, glowRadiusDp = 18f,
                color = 0xFF00D4FF.toInt(), maxAlpha = 0.9f
            ),
            // 坐桶区域（座椅中部，用于坐桶打开时高亮）
            VehicleLightPoint(
                type = Type.BUCKET_AREA,
                relX = 0.48f, relY = 0.35f,
                radiusDp = 6f, glowRadiusDp = 22f,
                color = 0xFFFF6B35.toInt(), maxAlpha = 0.8f
            )
        )
    }
}
