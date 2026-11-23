package io.github.wangnan05563.airmirror.ui

import kotlin.math.ceil
import kotlin.math.max

/**
 * TV 关键页布局的纯逻辑口径（T06100 / REQ-006）。
 *
 * 负责三件在 JVM 单测里必须能钉死的事：
 * 1. 如何把「杂牌盒子不报 television」的大横屏也认成 TV 布局；
 * 2. 720p / 1080p / 4:3 下四周 5% 安全区换算成像素；
 * 3. 二维码在低分辨率或 4:3 屏幕上按高度收敛，宽屏时二维码与投屏码并排。
 */
object TvLayoutSpec {

    /** 四周安全区：屏宽/屏高各取 5%，向上取整，避免边缘像素被裁。 */
    const val SAFE_FRACTION = 0.05f

    /** 大横屏兜底：宽度至少 1024px 且横向，才按 TV 字号与安全区排版。 */
    const val LARGE_SCREEN_MIN_WIDTH_PX = 1024

    /** 二维码边长上限：不超过屏高的 34%，给标题、状态行和底部入口留出空间。 */
    const val QR_MAX_HEIGHT_FRACTION = 0.34f

    /** PRD UI-01：TV 二维码基准按屏宽分 160 / 200 / 240dp 三档。 */
    const val TV_QR_COMPACT_DP = 160
    const val TV_QR_MEDIUM_DP = 200
    const val TV_QR_LARGE_DP = 240
    const val TV_QR_MEDIUM_WIDTH_PX = 1280
    const val TV_QR_LARGE_WIDTH_PX = 1920

    /** 宽屏时二维码与投屏码并排；手机/竖屏仍上下排列，避免 6 位码被挤成两截。 */
    const val PAIR_ROW_MIN_WIDTH_DP = 500

    data class SafeInsets(val horizontal: Int, val vertical: Int)

    fun isTvLayout(uiModeTv: Boolean, widthPx: Int, heightPx: Int): Boolean =
        uiModeTv || (widthPx >= LARGE_SCREEN_MIN_WIDTH_PX && widthPx > heightPx)

    fun safeInsets(widthPx: Int, heightPx: Int): SafeInsets {
        if (widthPx <= 0 || heightPx <= 0) return SafeInsets(0, 0)
        return SafeInsets(
            horizontal = ceil(widthPx * SAFE_FRACTION).toInt(),
            vertical = ceil(heightPx * SAFE_FRACTION).toInt(),
        )
    }

    /** 二维码/码槽边长：保留调用方的基础尺寸，但在低高度屏上按 34% 屏高收敛。 */
    fun qrSidePx(widthPx: Int, heightPx: Int, basePx: Int): Int {
        if (basePx <= 0) return 0
        if (widthPx <= 0 || heightPx <= 0) return basePx
        val heightCap = ceil(heightPx * QR_MAX_HEIGHT_FRACTION).toInt()
        return max(0, minOf(basePx, heightCap))
    }

    /**
     * TV 二维码基准边长：按屏宽取 PRD 的 160 / 200 / 240dp 三档。
     * 单位是 dp，不随电视上报的 density 漂移；调用方再乘 density 换成像素。
     */
    fun tvQrBaseDp(widthPx: Int): Int = when {
        widthPx >= TV_QR_LARGE_WIDTH_PX -> TV_QR_LARGE_DP
        widthPx >= TV_QR_MEDIUM_WIDTH_PX -> TV_QR_MEDIUM_DP
        else -> TV_QR_COMPACT_DP
    }

    /**
     * TV 二维码实际像素：先按屏宽取基准 dp，再受屏高 34% 上限约束。
     * 1080p / 720p / 4:3 在 320dpi 下与 T06100 的 368 / 245 / 262px 保持一致；
     * 低密度电视（如 1080p mdpi）会得到 240px，不再沿用手机 190dp 的偏小尺寸。
     */
    fun tvQrSidePx(widthPx: Int, heightPx: Int, density: Float): Int {
        if (density <= 0f) return 0
        val basePx = ceil(tvQrBaseDp(widthPx) * density).toInt()
        if (widthPx <= 0 || heightPx <= 0) return basePx
        val heightCap = ceil(heightPx * QR_MAX_HEIGHT_FRACTION).toInt()
        return max(0, minOf(basePx, heightCap))
    }

    /**
     * 6 位码字号跟着码槽一起缩：码槽从 190dp 收敛到 122.5dp 时，
     * 仍用 36sp 会让六位数字顶出圆环；按同比例缩到 23sp 才能单行留在环内。
     */
    fun codeSp(
        sidePx: Int,
        density: Float,
        baseSideDp: Float,
        baseSp: Float,
    ): Float {
        if (sidePx <= 0 || density <= 0f || baseSideDp <= 0f) return baseSp
        val ratio = (sidePx / density) / baseSideDp
        return minOf(baseSp, baseSp * ratio)
    }

    fun usePairRow(isTvLayout: Boolean, widthDp: Int): Boolean =
        isTvLayout && widthDp >= PAIR_ROW_MIN_WIDTH_DP
}
