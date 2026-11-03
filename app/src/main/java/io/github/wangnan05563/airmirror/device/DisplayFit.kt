package io.github.wangnan05563.airmirror.device

/** 接收端显示模式（PRD FS-08 参数表）：默认完整显示，按设备持久化。 */
enum class DisplayMode(val label: String, val hint: String) {
    FIT("完整显示", "等比完整显示，不裁切画面"),
    FILL("铺满", "等比填满屏幕，边缘会被裁切"),
    STRETCH("拉伸", "铺满并拉伸，画面可能变形");

    /** 拉伸会改变比例，切换前必须二次确认（PRD FS-08 步骤 4）。 */
    val needsConfirm: Boolean get() = this == STRETCH

    companion object {
        fun parse(raw: String?): DisplayMode = entries.firstOrNull { it.name == raw } ?: FIT
    }
}

data class Viewport(val width: Int, val height: Int)

/** 画面在容器内的摆放计算；不依赖 android.graphics，便于 JVM 直接验证。 */
object DisplayFit {

    /** 竖屏内容缩得太窄会看不清，宽度不低于屏幕的 25%（PRD FS-08 参数表）。 */
    const val MIN_WIDTH_FRACTION = 0.25f

    fun viewport(mode: DisplayMode, boxW: Int, boxH: Int, videoW: Int, videoH: Int): Viewport {
        if (boxW <= 0 || boxH <= 0 || videoW <= 0 || videoH <= 0) return Viewport(0, 0)
        return when (mode) {
            DisplayMode.STRETCH -> Viewport(boxW, boxH)
            DisplayMode.FILL -> {
                val scale = maxOf(boxW.toFloat() / videoW, boxH.toFloat() / videoH)
                Viewport(Math.round(videoW * scale), Math.round(videoH * scale))
            }
            DisplayMode.FIT -> {
                val scale = minOf(boxW.toFloat() / videoW, boxH.toFloat() / videoH)
                var width = Math.round(videoW * scale)
                var height = Math.round(videoH * scale)
                val minWidth = boxW * MIN_WIDTH_FRACTION
                if (width < minWidth) {
                    width = Math.round(minWidth)
                    height = Math.round(videoH.toFloat() * width / videoW)
                }
                Viewport(width, height)
            }
        }
    }

    /** 画面比例与屏幕差异明显时，角落提示可以换模式（PRD FS-08 步骤 5）；21:9 在 16:9 屏上必须触发。 */
    fun mismatched(boxW: Int, boxH: Int, videoW: Int, videoH: Int): Boolean {
        if (minOf(boxW, boxH, videoW, videoH) <= 0) return false
        val box = boxW.toFloat() / boxH
        val video = videoW.toFloat() / videoH
        return maxOf(box, video) / minOf(box, video) > 1.25f
    }
}