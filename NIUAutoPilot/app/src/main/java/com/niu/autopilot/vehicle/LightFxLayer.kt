package com.niu.autopilot.vehicle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.niu.autopilot.R
import kotlin.math.min

/**
 * 车辆灯光特效层 v3.3
 *
 * 设计要点（依据调研结论）：
 * 1. **单一 View 绘制所有光效**：合并原来 5 个 LightFxView 为 1 个 render node，省合成开销。
 * 2. **PorterDuff.Mode.SCREEN 混合**：这是"真实灯光"的数学基础。
 *    result = 1-(1-a)(1-b)，光加光变亮而不会像 SRC_OVER 那样把车漆压暗。
 * 3. **分层绘制**（物理由远及近）：
 *    层级1 地面投影光斑 → 层级2 光锥本体 → 层级3 贴图纹理 → 层级4 灯珠核心
 * 4. **性能**：Path / Shader / Paint / RectF 全部成员变量缓存，
 *    仅在 onSizeChanged 或主题切换时重建；动画只改 paint.alpha，绝不重建 shader。
 *
 * 调用方：由 VehicleStageView 持有，动画通过 setXxxProperty 驱动。
 */
class LightFxLayer(context: Context) : View(context) {

    // ==================== 绘制资源（全部缓存，禁止每帧新建） ====================
    private val texPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val conePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        color = Color.WHITE
    }

    private val conePath = Path()
    private val dstRect = RectF()
    private val groundRect = RectF()
    private val texMatrix = Matrix()

    // ---- 贴图 ----
    private var haloBmp: Bitmap? = null
    private var beamBmp: Bitmap? = null
    private var tailBmp: Bitmap? = null
    private var turnBmp: Bitmap? = null
    private var bucketBmp: Bitmap? = null

    private var haloShader: BitmapShader? = null
    private var tailShader: BitmapShader? = null
    private var turnShader: BitmapShader? = null
    private var bucketShader: BitmapShader? = null
    private var coneShader: Shader? = null
    private var groundShader: Shader? = null

    // ==================== 车身内容 bbox（用于锚点换算） ====================
    /**
     * 车身图（nx_body.webp 1500×1000）中实际车身的透明边界。
     * 像素实测：bbox = (437,70)-(1063,930)。
     * 锚点坐标 headXf/headYf 等是「相对车身内容」的归一化值（0=车身最左/最上，1=最右/最下），
     * 绘制时先映射回画布像素，再按 View 的 FIT_CENTER 实际显示矩形换算。
     */
    private val BODY_BBOX_LEFT = 437f
    private val BODY_BBOX_TOP = 70f
    private val BODY_BBOX_RIGHT = 1063f
    private val BODY_BBOX_BOTTOM = 930f
    private val BODY_SRC_W = 1500f
    private val BODY_SRC_H = 1000f

    // ==================== 归一化锚点（相对车身内容 bbox，0~1） ====================
    /**
     * 大灯：车头左侧圆形大灯（像素实测白色高亮质心 x=609, y=319，
     * 相对车身内容 bbox (437,70)-(1063,930) = (0.275, 0.29)）。
     */
    var headXf = 0.27f
    var headYf = 0.29f
    /** 尾灯：车尾右下（ASCII 轮廓 + 车身形状定位） */
    var tailXf = 0.35f
    var tailYf = 0.70f
    /** 转向/双闪：车头两侧 */
    var turnXf = 0.30f
    var turnYf = 0.20f
    /** 坐桶箱内透光：座椅中部 */
    var bucketXf = 0.50f
    var bucketYf = 0.30f

    // ==================== 动画驱动属性（0..1） ====================
    /** 大灯灯珠核心亮度 */
    var headCoreAlpha = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    /** 大灯光晕贴图 */
    var haloAlpha = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    /** 光锥本体 */
    var coneAlpha = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    /** 光锥长度比（0.2 = 刚亮起，1.0 = 完全照出） */
    var coneProgress = 1f
        set(v) { field = v.coerceIn(0f, 1f); rebuildCone(); invalidate() }
    /** 光束贴图纹理 */
    var beamTexAlpha = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    /** 地面光斑 */
    var groundAlpha = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    /** 尾灯 */
    var tailAlpha = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    /** 转向/双闪（左右同相） */
    var turnAlpha = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    /** 坐桶箱内透光 */
    var bucketAlpha = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    /** 降级等级：0=全部关，1=基础，2=完整 */
    var quality = 2
        set(v) { field = v; rebuildShaders(); invalidate() }

    init {
        loadBitmaps()
    }

    private fun loadBitmaps() {
        val res = context.resources
        val opts = BitmapFactory.Options().apply { inScaled = false }
        haloBmp = decode(res, R.drawable.fx_headlamp_halo, opts)
        beamBmp = decode(res, R.drawable.fx_headlamp_beam, opts)
        tailBmp = decode(res, R.drawable.fx_taillamp_glow, opts)
        turnBmp = decode(res, R.drawable.fx_turn_signal, opts)
        bucketBmp = decode(res, R.drawable.fx_bucket_glow, opts)

        haloShader = haloBmp?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        tailShader = tailBmp?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        turnShader = turnBmp?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        bucketShader = bucketBmp?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    }

    private fun decode(res: android.content.res.Resources, id: Int, opts: BitmapFactory.Options): Bitmap? =
        try { BitmapFactory.decodeResource(res, id, opts) } catch (e: Throwable) { null }

    // ==================== 坐标换算 ====================
    // 锚点 fx/fy 是「相对车身内容 bbox」的归一化值。
    // 车身图 1500x1000 经 FIT_CENTER 居中显示到 View（width x height）。
    // 先映射回源图像素，再按 FIT_CENTER 的缩放和平移换算到 View 像素。

    /** 源图在 View 中的 FIT_CENTER 显示矩形（左/上/缩放） */
    private var fitScale = 1f
    private var fitLeft = 0f
    private var fitTop = 0f

    private fun computeFitRect() {
        if (width == 0 || height == 0) return
        val vw = width.toFloat()
        val vh = height.toFloat()
        fitScale = min(vw / BODY_SRC_W, vh / BODY_SRC_H)
        val drawW = BODY_SRC_W * fitScale
        val drawH = BODY_SRC_H * fitScale
        fitLeft = (vw - drawW) / 2f
        fitTop = (vh - drawH) / 2f
    }

    /** 车身内容归一化锚点 → View 像素坐标 */
    private fun anchorToViewX(fx: Float): Float {
        computeFitRect()
        val srcX = BODY_BBOX_LEFT + fx * (BODY_BBOX_RIGHT - BODY_BBOX_LEFT)
        return fitLeft + srcX * fitScale
    }

    private fun anchorToViewY(fy: Float): Float {
        computeFitRect()
        val srcY = BODY_BBOX_TOP + fy * (BODY_BBOX_BOTTOM - BODY_BBOX_TOP)
        return fitTop + srcY * fitScale
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeFitRect()
        rebuildShaders()
    }

    /** 尺寸变化/主题变化/画质切换时重建 shader（不含动画状态，避免每帧重建） */
    private fun rebuildShaders() {
        if (width == 0 || height == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val hx = anchorToViewX(headXf)
        val hy = anchorToViewY(headYf)

        // 光锥渐变：根亮末透（0.85 → 0.55 → 0）
        coneShader?.let { } ?: run {
            coneShader = RadialGradient(
                hx, hy, 0.42f * w,
                intArrayOf(
                    argb(0xD9, 0xFF, 0xFF, 0xFF), // 根：暖白 85%
                    argb(0x8C, 0xC8, 0xE8, 0xFF), // 中：淡蓝 55%
                    argb(0x00, 0x90, 0xC0, 0xFF)  // 末：全透明
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        groundShader = RadialGradient(
            0.30f * w, 0.78f * h, 0.30f * w,
            intArrayOf(argb(0x55, 0xFF, 0xFF, 0xFF), argb(0x00, 0xFF, 0xFF, 0xFF)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        rebuildCone()
    }

    /** 光锥几何：车头朝左上，光锥向左上照出（随 coneProgress 展开） */
    private fun rebuildCone() {
        if (width == 0 || height == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val hx = anchorToViewX(headXf)
        val hy = anchorToViewY(headYf)
        val beamLen = 0.32f * w * (0.25f + 0.75f * coneProgress)
        val spread = 0.20f * h * (0.35f + 0.65f * coneProgress)

        // 车头朝左上，光锥向左上发散（用 -beamLen 表示向左，-spread 表示向上）
        conePath.rewind()
        conePath.moveTo(hx, hy)
        conePath.quadTo(
            hx - beamLen * 0.55f, hy - spread * 0.35f,
            hx - beamLen, hy - spread * 0.5f
        )
        conePath.quadTo(
            hx - beamLen * 1.02f, hy,
            hx - beamLen, hy + spread * 0.5f
        )
        conePath.quadTo(
            hx - beamLen * 0.55f, hy + spread * 0.35f,
            hx, hy
        )
        conePath.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (quality <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 预先算好各锚点在 View 中的像素坐标
        val hx = anchorToViewX(headXf)
        val hy = anchorToViewY(headYf)
        val tx = anchorToViewX(tailXf)
        val ty = anchorToViewY(tailYf)
        val ux = anchorToViewX(turnXf)
        val uy = anchorToViewY(turnYf)
        val bx = anchorToViewX(bucketXf)
        val by = anchorToViewY(bucketYf)

        // ---------- 层级 1：地面投影光斑（车头前方，左上方向） ----------
        if (groundAlpha > 0.01f) {
            groundRect.set(hx - 0.42f * w, hy + 0.30f * h, hx + 0.10f * w, hy + 0.55f * h)
            groundPaint.alpha = (groundAlpha * 0x22).toInt().coerceIn(0, 255)
            groundPaint.shader = groundShader
            canvas.drawOval(groundRect, groundPaint)
        }

        // ---------- 层级 2：光锥本体（朝左上照出） ----------
        if (coneAlpha > 0.01f) {
            conePaint.alpha = (coneAlpha * 255).toInt().coerceIn(0, 255)
            conePaint.shader = coneShader
            canvas.drawPath(conePath, conePaint)
        }

        // ---------- 层级 3：光束贴图纹理 ----------
        if (quality >= 2 && beamTexAlpha > 0.01f && beamBmp != null) {
            val bw = 0.40f * w
            val bh = 0.24f * w
            dstRect.set(hx - bw, hy - bh / 2f, hx, hy + bh / 2f)
            texPaint.alpha = (beamTexAlpha * 0xB0).toInt().coerceIn(0, 255)
            texPaint.shader = null
            canvas.save()
            canvas.rotate(0f, hx, hy)
            canvas.drawBitmap(beamBmp!!, null, dstRect, texPaint)
            canvas.restore()
        }

        // ---------- 层级 4：大灯灯珠核心 + 光晕 ----------
        if (haloAlpha > 0.01f) {
            val hs = 0.26f * w
            dstRect.set(hx - hs / 2f, hy - hs / 2f, hx + hs / 2f, hy + hs / 2f)
            texPaint.alpha = (haloAlpha * 255).toInt().coerceIn(0, 255)
            haloBmp?.let { canvas.drawBitmap(it, null, dstRect, texPaint) }
                ?: run {
                    texPaint.shader = haloShader
                    canvas.drawRect(dstRect, texPaint)
                }
        }
        if (headCoreAlpha > 0.01f) {
            corePaint.alpha = (headCoreAlpha * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(hx, hy, 0.014f * w, corePaint)
        }

        // ---------- 尾灯 ----------
        if (tailAlpha > 0.01f) {
            val ts = 0.20f * w
            dstRect.set(tx - ts / 2f, ty - ts / 2f, tx + ts / 2f, ty + ts / 2f)
            texPaint.alpha = (tailAlpha * 255).toInt().coerceIn(0, 255)
            tailBmp?.let { canvas.drawBitmap(it, null, dstRect, texPaint) }
        }

        // ---------- 双闪（左右同相：正品双闪是同时闪，不是交替） ----------
        if (turnAlpha > 0.01f) {
            val us = 0.24f * w
            // 前转向灯（车头侧）
            dstRect.set(ux - us / 2f, uy - us / 2f, ux + us / 2f, uy + us / 2f)
            texPaint.alpha = (turnAlpha * 255).toInt().coerceIn(0, 255)
            turnBmp?.let { canvas.drawBitmap(it, null, dstRect, texPaint) }
        }

        // ---------- 坐桶箱内透光 ----------
        if (bucketAlpha > 0.01f) {
            val bs = 0.28f * w
            dstRect.set(bx - bs / 2f, by - bs / 2f, bx + bs / 2f, by + bs / 2f)
            texPaint.alpha = (bucketAlpha * 255).toInt().coerceIn(0, 255)
            bucketBmp?.let { canvas.drawBitmap(it, null, dstRect, texPaint) }
        }
    }

    /** 全部熄灭（reset） */
    fun resetAll() {
        headCoreAlpha = 0f
        haloAlpha = 0f
        coneAlpha = 0f
        beamTexAlpha = 0f
        groundAlpha = 0f
        tailAlpha = 0f
        turnAlpha = 0f
        bucketAlpha = 0f
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        Color.argb(a, r, g, b)
}
