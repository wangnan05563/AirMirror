package io.github.wangnan05563.airmirror.ui

/**
 * REQ-008 / AC-08：Raw → Semantic → Component 三层主题令牌。
 *
 * 约束：
 * 1. Raw 只描述色值/字阶/刻度本身，不承载页面语义；页面与组件不得直接引用 Raw。
 * 2. Semantic 描述“是什么颜色”，组件与页面按语义取色；默认主题 Midnight 与旧 Theme 逐值相等。
 * 3. Component 只按组件命名，同一组件在手机/电视只换尺寸，不换色。
 * 4. OLED 只注册能力位，本期不发布、不切换，默认仍为 Midnight。
 *
 * 旧 [Theme] 常量保留为兼容别名，页面可以继续用旧名；新代码优先走 [Theme.component]。
 */
enum class ThemeId { MIDNIGHT, OLED }

/** 主题能力位：能力位不是颜色，主题切换时不允许页面自行推断。 */
data class ThemeCapabilities(
    val supportsTrueBlack: Boolean,
    val debugPreview: Boolean,
    val releaseSelectable: Boolean,
)

/** Raw 原始色板：只允许 [ThemeTokens] 与 debug 预览读取。 */
internal data class RawColors(
    val ink950: Int,
    val ink900: Int,
    val ink800: Int,
    val teal500: Int,
    val white: Int,
    val red400: Int,
    val white70: Int,
    val white30: Int,
    val ink950_40: Int,
    val ink950_80: Int,
    val ink950_90: Int,
    val ink900_20: Int,
    val ink900_90: Int,
    val teal500_20: Int,
    val teal500_12: Int,
    val white10: Int,
    val white8: Int,
)

internal object RawPalette {
    val midnight = RawColors(
        ink950 = 0xFF0B1F3A.toInt(),
        ink900 = 0xFF12314F.toInt(),
        ink800 = 0xFF1A3D61.toInt(),
        teal500 = 0xFF17C3B2.toInt(),
        white = 0xFFFFFFFF.toInt(),
        red400 = 0xFFFF6B6B.toInt(),
        white70 = 0xB3FFFFFF.toInt(),
        white30 = 0x4DFFFFFF.toInt(),
        ink950_40 = 0x660B1F3A.toInt(),
        ink950_80 = 0xCC0B1F3A.toInt(),
        ink950_90 = 0xE60B1F3A.toInt(),
        ink900_20 = 0x3312314F.toInt(),
        ink900_90 = 0xE612314F.toInt(),
        teal500_20 = 0x3317C3B2.toInt(),
        teal500_12 = 0x1F17C3B2.toInt(),
        white10 = 0x1AFFFFFF.toInt(),
        white8 = 0x14FFFFFF.toInt(),
    )
}

/** Semantic：描述“是什么”，不描述“长什么样”。 */
data class SemanticTokens(
    val background: Int,
    val surface: Int,
    val surfaceElevated: Int,
    val accent: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val offline: Int,
    val danger: Int,
    val scrim: Int,
    val scrimStrong: Int,
    val panel: Int,
    val divider: Int,
    val focusFill: Int,
    val surfaceStroke: Int,
    val qrLight: Int,
    /** 12% 强调衬底：标签/角标底，不承载正文对比度。 */
    val accentSubtle: Int,
    /** 10% 白衬底：离线标签底。 */
    val surfaceSubtle: Int,
    /** 8% 白衬底：不支持标签底。 */
    val surfaceGhost: Int,
)

/** Component：只按组件命名，页面不得凭空造组件名。 */
data class ComponentTokens(
    val pageBackground: Int,
    val cardBackground: Int,
    val cardElevated: Int,
    val cardStroke: Int,
    val buttonPrimaryBg: Int,
    val buttonPrimaryFg: Int,
    val buttonSecondaryBg: Int,
    val buttonSecondaryFg: Int,
    val buttonGhostFg: Int,
    val dialogScrim: Int,
    val dialogNegativeFg: Int,
    val statusConnected: Int,
    val statusRecovering: Int,
    val statusIdle: Int,
    val statusError: Int,
    val emptyDiagnosisCard: Int,
    val codeSlotRing: Int,
    val codeSlotBackground: Int,
    val qrBackground: Int,
    val wizardStepActive: Int,
    val wizardStepIdle: Int,
    val tagProtocolBg: Int,
    val tagProtocolFg: Int,
    val tagOfflineBg: Int,
    val tagOfflineFg: Int,
    val tagUnsupportedBg: Int,
    val tagUnsupportedFg: Int,
    val insetBackground: Int,
    val cardRadius: Int,
    val buttonRadius: Int,
    val dialogRadius: Int,
)

/** 字阶：display/title/body/caption 为角色名，不按页面命名。 */
data class TypographyTokens(
    val display: Float,
    val title: Float,
    val body: Float,
    val caption: Float,
    val code: Float,
    val sectionTitle: Float,
    val phoneTitle: Float,
    val senderTitle: Float,
    val deviceName: Float,
    val tvButton: Float,
    val phoneButton: Float,
    val phoneButtonDense: Float,
    val phoneButtonCompact: Float,
)

data class SpacingTokens(
    val xs: Int,
    val sm: Int,
    val md: Int,
    val lg: Int,
    val xl: Int,
    val xxl: Int,
)

data class RadiusTokens(
    val focus: Int,
    val button: Int,
    val card: Int,
    val inset: Int,
    val dialog: Int,
)

data class MotionTokens(
    val focus: Long,
    val connect: Long,
    val codeFade: Long,
)

/** 主题注册表：页面只通过 [ThemeResolver] 取语义/组件，不得自行 when(themeId) 拼色。 */
interface ThemeSpec {
    val id: ThemeId
    val capabilities: ThemeCapabilities
    val semantic: SemanticTokens
    val component: ComponentTokens
    val typography: TypographyTokens
    val spacing: SpacingTokens
    val radii: RadiusTokens
    val motion: MotionTokens
}

/** 默认主题：与改造前的 Theme/StatusColors/DeviceCardTag 逐值相等，像素差异为 0。 */
object MidnightTheme : ThemeSpec {
    override val id = ThemeId.MIDNIGHT
    override val capabilities = ThemeCapabilities(
        supportsTrueBlack = false,
        debugPreview = true,
        releaseSelectable = true,
    )

    private val raw = RawPalette.midnight

    override val semantic = SemanticTokens(
        background = raw.ink950,
        surface = raw.ink900,
        surfaceElevated = raw.ink800,
        accent = raw.teal500,
        textPrimary = raw.white,
        textSecondary = raw.white70,
        offline = raw.white30,
        danger = raw.red400,
        scrim = raw.ink950_80,
        scrimStrong = raw.ink950_90,
        panel = raw.ink900_90,
        divider = raw.ink900_20,
        focusFill = raw.ink950_40,
        surfaceStroke = raw.teal500_20,
        qrLight = raw.white,
        accentSubtle = raw.teal500_12,
        surfaceSubtle = raw.white10,
        surfaceGhost = raw.white8,
    )

    override val radii = RadiusTokens(focus = 6, button = 14, card = 16, inset = 12, dialog = 24)

    override val component = ComponentTokens(
        pageBackground = semantic.background,
        cardBackground = semantic.surface,
        cardElevated = semantic.surfaceElevated,
        cardStroke = semantic.surfaceStroke,
        buttonPrimaryBg = semantic.accent,
        buttonPrimaryFg = semantic.background,
        // 现网次按钮是面底（Theme.SURFACE），不是抬升面；改值会破坏像素等价。
        buttonSecondaryBg = semantic.surface,
        buttonSecondaryFg = semantic.textPrimary,
        buttonGhostFg = semantic.accent,
        dialogScrim = semantic.scrimStrong,
        dialogNegativeFg = semantic.textSecondary,
        statusConnected = semantic.accent,
        statusRecovering = semantic.textPrimary,
        statusIdle = semantic.textSecondary,
        statusError = semantic.danger,
        emptyDiagnosisCard = semantic.surfaceElevated,
        codeSlotRing = semantic.accent,
        codeSlotBackground = semantic.surface,
        qrBackground = semantic.qrLight,
        wizardStepActive = semantic.accent,
        wizardStepIdle = semantic.textSecondary,
        tagProtocolBg = semantic.accentSubtle,
        tagProtocolFg = semantic.accent,
        tagOfflineBg = semantic.surfaceSubtle,
        tagOfflineFg = semantic.textSecondary,
        tagUnsupportedBg = semantic.surfaceGhost,
        tagUnsupportedFg = semantic.textSecondary,
        insetBackground = semantic.background,
        cardRadius = radii.card,
        buttonRadius = radii.button,
        dialogRadius = radii.dialog,
    )

    override val typography = TypographyTokens(
        display = 40f,
        title = 32f,
        body = 24f,
        caption = 14f,
        code = 36f,
        sectionTitle = 18f,
        phoneTitle = 32f,
        senderTitle = 32f,
        deviceName = 18f,
        tvButton = 24f,
        phoneButton = 16f,
        phoneButtonDense = 14f,
        phoneButtonCompact = 12f,
    )

    override val spacing = SpacingTokens(xs = 4, sm = 8, md = 12, lg = 16, xl = 24, xxl = 32)
    override val motion = MotionTokens(focus = 120L, connect = 200L, codeFade = 150L)
}

/** OLED 接口预留：能力位已声明，本期不发布、不进入 release 选择器，取值暂与 Midnight 相同。 */
object OledTheme : ThemeSpec {
    override val id = ThemeId.OLED
    override val capabilities = ThemeCapabilities(
        supportsTrueBlack = true,
        debugPreview = false,
        releaseSelectable = false,
    )
    override val semantic = MidnightTheme.semantic
    override val component = MidnightTheme.component
    override val typography = MidnightTheme.typography
    override val spacing = MidnightTheme.spacing
    override val radii = MidnightTheme.radii
    override val motion = MidnightTheme.motion
}

object ThemeResolver {
    fun resolve(id: ThemeId): ThemeSpec = when (id) {
        ThemeId.MIDNIGHT -> MidnightTheme
        ThemeId.OLED -> OledTheme
    }

    /** release 允许切换的主题；OLED 只预留，不在此列表。 */
    fun releaseThemes(): List<ThemeSpec> = listOf(MidnightTheme)
}

/** 默认主题的便捷入口；旧 Theme 常量从这里取值。 */
object ThemeTokens {
    val midnight: ThemeSpec get() = ThemeResolver.resolve(ThemeId.MIDNIGHT)
    val semantic: SemanticTokens get() = midnight.semantic
    val component: ComponentTokens get() = midnight.component
    val typography: TypographyTokens get() = midnight.typography
    val spacing: SpacingTokens get() = midnight.spacing
    val radii: RadiusTokens get() = midnight.radii
    val motion: MotionTokens get() = midnight.motion
    internal val raw: RawColors get() = RawPalette.midnight
}
