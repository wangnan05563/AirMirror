package io.github.wangnan05563.airmirror.ui

/**
 * PRD UI-04 六态语义色与对比度（纯逻辑，不依赖 android.*）。
 *
 * 语义：强调色表「已连接」（正在投 / 已暂停），危险色表出错，进行中（连接中 / 恢复中）用正文白，
 * 未连接用次级白；六态各有一条取值，界面只按状态取色，不再各页自己挑颜色。
 *
 * 阈值按 2026-11-09 评审裁定升为硬指标：正文 ≥4.5:1、大字 ≥3:1（WCAG AA），
 * 任一条状态文字不达标即判 UI-04 未通过。半透明令牌（如 [Theme.TEXT_SECONDARY]）先与底色
 * 合成实色再算比值，不能拿原始 ARGB 直接套公式——否则会算出偏高的假数值。
 */
object StatusColors {

    /** WCAG AA 正文下限。 */
    const val BODY_MIN = 4.5

    /** WCAG AA 大字（≥18pt / 粗体 ≥14pt）下限。 */
    const val LARGE_MIN = 3.0

    /** 恢复条 / 浮层底色：90% 页底，压在任意画面上；文字对比度按最差（纯白画面）验。 */
    const val BAR_LAYER = 0xE60B1F3A.toInt()

    /** 投屏浮层底色：80% 页底（原 ReceiverActivity 同值，收敛到这里避免两处各写一份）。 */
    const val OVERLAY_LAYER = 0xCC0B1F3A.toInt()

    /** 恢复条压在画面上时的最差底色：90% 深蓝叠纯白画面。 */
    fun overVideo(layer: Int = BAR_LAYER): Int = composite(layer, 0xFFFFFFFF.toInt())

    /**
     * 六态状态文字色。
     *
     * [highContrast] 给 C 级设备（PRD「C 级设备使用静态高对比态」）：把唯一一处半透明的
     * 次级白换成全不透明正文白，其余色本身就已达 AA，保持语义不变。
     */
    fun textOf(state: CastStatus.State, highContrast: Boolean = false): Int = when (state) {
        CastStatus.State.IDLE -> secondaryText(highContrast)
        CastStatus.State.CONNECTING -> Theme.TEXT_PRIMARY
        CastStatus.State.CASTING -> Theme.ACCENT
        CastStatus.State.PAUSED -> Theme.ACCENT
        CastStatus.State.RECOVERING -> Theme.TEXT_PRIMARY
        CastStatus.State.ERROR -> Theme.DANGER
    }

    /**
     * 离线行的文字色：低透明度只留给非文字的装饰（[offlineMarker]）。
     * [Theme.OFFLINE] 在页底上只有 2.70:1，单独用灰度表达离线既违反 UI-03 也过不了 AA。
     */
    fun offlineText(): Int = Theme.TEXT_SECONDARY

    /** 离线图标 / 分隔等非文字装饰，不参与文字对比度。 */
    fun offlineMarker(): Int = Theme.OFFLINE

    /** 次级信息文字；C 级设备改用全不透明正文白，即「静态高对比态」。 */
    fun secondaryText(highContrast: Boolean = false): Int =
        if (highContrast) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY

    /**
     * 投屏中浮层文字。浮层压在任意画面之上，底色 80% 深蓝与白底合成最差仍是 8.76:1；
     * 强调色在那个最差底色上只有 3.95:1，所以浮层文字一律用正文白。
     */
    fun overlayText(): Int = Theme.TEXT_PRIMARY

    data class Entry(
        val state: CastStatus.State,
        val text: Int,
        val onPage: Double,
    )

    /** 六态色值表：每态在页底上的实测比值，供单测与取色复核对照。 */
    fun table(highContrast: Boolean = false): List<Entry> = CastStatus.State.values().map { state ->
        val fg = textOf(state, highContrast)
        Entry(state, fg, contrast(fg, Theme.BACKGROUND))
    }

    /** 前景带透明度时先按直通道 alpha 合成到底色，再算亮度比。 */
    fun contrast(fg: Int, bg: Int): Double {
        val back = if (alphaOf(bg) == 0xFF) bg else composite(bg, Theme.BACKGROUND)
        val front = if (alphaOf(fg) == 0xFF) fg else composite(fg, back)
        val a = relativeLuminance(front)
        val b = relativeLuminance(back)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    fun meetsAa(fg: Int, bg: Int, large: Boolean = false): Boolean =
        contrast(fg, bg) >= if (large) LARGE_MIN else BODY_MIN

    /** 直通道 alpha 合成（sRGB 空间逐通道线性插值，与 Android 层叠的观感一致）。 */
    fun composite(fg: Int, bg: Int): Int {
        val a = alphaOf(fg) / 255.0
        val r = channel((fg shr 16) and 0xFF, (bg shr 16) and 0xFF, a)
        val g = channel((fg shr 8) and 0xFF, (bg shr 8) and 0xFF, a)
        val b = channel(fg and 0xFF, bg and 0xFF, a)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun relativeLuminance(argb: Int): Double =
        0.2126 * linear((argb shr 16) and 0xFF) +
            0.7152 * linear((argb shr 8) and 0xFF) +
            0.0722 * linear(argb and 0xFF)

    private fun alphaOf(argb: Int): Int = (argb ushr 24) and 0xFF

    private fun channel(fg: Int, bg: Int, alpha: Double): Int = Math.round(fg * alpha + bg * (1 - alpha)).toInt()

    private fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
}
