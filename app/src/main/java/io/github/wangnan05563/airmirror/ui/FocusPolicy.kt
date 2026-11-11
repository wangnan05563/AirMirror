package io.github.wangnan05563.airmirror.ui

/**
 * PRD §11.5 视觉与可用性规范的纯逻辑口径（TV 焦点 / 字号 / 动效）。
 *
 * 抽成不依赖 android.* 的一层，让「2px 描边、轻微放大、C 级关动效、TV 字号下限」
 * 这些判据能在 JVM 单测里被钉死，而不是靠遥控器点一遍凭感觉。
 */
object FocusPolicy {

    /** 「TV 焦点态必须有 2px 高对比描边」；TV 上按 dp 落，避免不同密度下粗细不一。 */
    const val OUTLINE_DP = 2

    /** 「轻微放大」：4% 足够在 3 米外辨认，再大就会把相邻行挤出屏幕。 */
    const val FOCUSED_SCALE = 1.04f
    const val FOCUS_ANIM_MS = Theme.DURATION_FOCUS_MS

    /** 「连接成功 200ms 淡入」。 */
    const val CONNECT_FADE_MS = Theme.DURATION_CONNECT_FADE_MS

    /** 放大是焦点态本身，不随动效开关取消；C 级只是把它变成瞬时切换（不做动画）。 */
    fun focusScale(animationsEnabled: Boolean, focused: Boolean): Float =
        if (focused) FOCUSED_SCALE else 1f

    fun focusAnimMs(animationsEnabled: Boolean): Long = if (animationsEnabled) FOCUS_ANIM_MS else 0L

    /** C 级设备关闭动效（PRD §11.5 / 8.8）：直接置终态，不做 200ms 淡入。 */
    fun fadeDurationMs(animationsEnabled: Boolean): Long = if (animationsEnabled) CONNECT_FADE_MS else 0L

    /** PRD UI-06：码轮换交叉淡化；C 级关动效时不排队动画，直接 0ms 瞬切。 */
    fun codeFadeMs(animationsEnabled: Boolean): Long = if (animationsEnabled) Theme.DURATION_CODE_FADE_MS else 0L

    /** 可点控件 = 遥控器焦点候选；纯展示文本不抢焦点。 */
    fun isFocusTarget(clickable: Boolean, hasClickListener: Boolean): Boolean = clickable || hasClickListener

    /**
     * 开局总是尝试给首个可点控件焦点：TV / 接遥控器的设备上，这是 D-pad 能起步的唯一入口。
     * 纯触屏手机处于 touch mode，[TvFocus] 把 `isFocusableInTouchMode` 置为 false，
     * `requestFocus()` 会自然失败，不会留下焦点框；所以这里**不能**只按 uiMode 判断——
     * 杂牌盒子经常不报 `UI_MODE_TYPE_TELEVISION`，只判断它会让遥控器彻底失效。
     */
    fun shouldRequestInitialFocus(): Boolean = true

    /** §11.5：TV 正文 ≥24px、标题 ≥40px；手机正文 ≥14px。 */
    fun titleSp(isTv: Boolean): Float = if (isTv) Theme.TITLE_SP else Theme.PHONE_TITLE_SP
    fun bodySp(isTv: Boolean): Float = if (isTv) Theme.BODY_SP else Theme.PHONE_BODY_SP
}
