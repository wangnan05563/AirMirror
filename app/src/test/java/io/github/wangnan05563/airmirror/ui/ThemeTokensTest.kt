package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REQ-008 / AC-08：三层令牌完整性与 Midnight 等价迁移。
 * 这里所有期望值都从冻结规范抄来，不从实现反推；改值必须先改本测试与设计文档。
 */
class ThemeTokensTest {

    private val semantic = ThemeTokens.semantic
    private val component = ThemeTokens.component

    @Test
    fun `Raw 层只承载原始值且 Midnight 逐项可用`() {
        val raw = ThemeTokens.raw
        assertEquals(0xFF0B1F3A.toInt(), raw.ink950)
        assertEquals(0xFF12314F.toInt(), raw.ink900)
        assertEquals(0xFF1A3D61.toInt(), raw.ink800)
        assertEquals(0xFF17C3B2.toInt(), raw.teal500)
        assertEquals(0xFFFFFFFF.toInt(), raw.white)
        assertEquals(0xFFFF6B6B.toInt(), raw.red400)
        assertEquals(0xB3FFFFFF.toInt(), raw.white70)
        assertEquals(0x4DFFFFFF.toInt(), raw.white30)
        assertEquals(0x1F17C3B2.toInt(), raw.teal500_12)
        assertEquals(0x1AFFFFFF.toInt(), raw.white10)
        assertEquals(0x14FFFFFF.toInt(), raw.white8)
    }

    @Test
    fun `Semantic 层与冻结规范逐项相等`() {
        assertEquals(0xFF0B1F3A.toInt(), semantic.background)
        assertEquals(0xFF12314F.toInt(), semantic.surface)
        assertEquals(0xFF1A3D61.toInt(), semantic.surfaceElevated)
        assertEquals(0xFF17C3B2.toInt(), semantic.accent)
        assertEquals(0xFFFFFFFF.toInt(), semantic.textPrimary)
        assertEquals(0xB3FFFFFF.toInt(), semantic.textSecondary)
        assertEquals(0x4DFFFFFF.toInt(), semantic.offline)
        assertEquals(0xFFFF6B6B.toInt(), semantic.danger)
        assertEquals(0xCC0B1F3A.toInt(), semantic.scrim)
        assertEquals(0xE60B1F3A.toInt(), semantic.scrimStrong)
        assertEquals(0xE612314F.toInt(), semantic.panel)
        assertEquals(0x3312314F.toInt(), semantic.divider)
        assertEquals(0x660B1F3A.toInt(), semantic.focusFill)
        assertEquals(0x3317C3B2.toInt(), semantic.surfaceStroke)
        assertEquals(0xFFFFFFFF.toInt(), semantic.qrLight)
        assertEquals(0x1F17C3B2.toInt(), semantic.accentSubtle)
        assertEquals(0x1AFFFFFF.toInt(), semantic.surfaceSubtle)
        assertEquals(0x14FFFFFF.toInt(), semantic.surfaceGhost)
    }

    @Test
    fun `Semantic 无悬空且 Component 只引用 Semantic`() {
        assertNotNull(semantic)
        assertEquals(semantic.background, component.pageBackground)
        assertEquals(semantic.surface, component.cardBackground)
        assertEquals(semantic.surfaceElevated, component.cardElevated)
        assertEquals(semantic.surfaceStroke, component.cardStroke)
        assertEquals(semantic.accent, component.buttonPrimaryBg)
        assertEquals(semantic.background, component.buttonPrimaryFg)
        assertEquals(semantic.surface, component.buttonSecondaryBg)
        assertEquals(semantic.textPrimary, component.buttonSecondaryFg)
        assertEquals(semantic.accent, component.buttonGhostFg)
        assertEquals(semantic.scrimStrong, component.dialogScrim)
        assertEquals(semantic.textSecondary, component.dialogNegativeFg)
        assertEquals(semantic.accent, component.statusConnected)
        assertEquals(semantic.textPrimary, component.statusRecovering)
        assertEquals(semantic.textSecondary, component.statusIdle)
        assertEquals(semantic.danger, component.statusError)
        assertEquals(semantic.surfaceElevated, component.emptyDiagnosisCard)
        assertEquals(semantic.accent, component.codeSlotRing)
        assertEquals(semantic.surface, component.codeSlotBackground)
        assertEquals(semantic.qrLight, component.qrBackground)
        assertEquals(semantic.accent, component.wizardStepActive)
        assertEquals(semantic.textSecondary, component.wizardStepIdle)
        assertEquals(semantic.accentSubtle, component.tagProtocolBg)
        assertEquals(semantic.accent, component.tagProtocolFg)
        assertEquals(semantic.surfaceSubtle, component.tagOfflineBg)
        assertEquals(semantic.textSecondary, component.tagOfflineFg)
        assertEquals(semantic.surfaceGhost, component.tagUnsupportedBg)
        assertEquals(semantic.textSecondary, component.tagUnsupportedFg)
        assertEquals(semantic.background, component.insetBackground)
    }

    @Test
    fun `Component 圆角引用 Radius 令牌而非重复字面量`() {
        val radii = ThemeTokens.radii
        assertEquals(radii.card, component.cardRadius)
        assertEquals(radii.button, component.buttonRadius)
        assertEquals(radii.dialog, component.dialogRadius)
    }

    @Test
    fun `Typography 与旧常量逐值相等`() {
        val type = ThemeTokens.typography
        assertEquals(40f, type.display, 0.0001f)
        assertEquals(32f, type.title, 0.0001f)
        assertEquals(24f, type.body, 0.0001f)
        assertEquals(14f, type.caption, 0.0001f)
        assertEquals(36f, type.code, 0.0001f)
        assertEquals(18f, type.sectionTitle, 0.0001f)
        assertEquals(32f, type.phoneTitle, 0.0001f)
        assertEquals(32f, type.senderTitle, 0.0001f)
        assertEquals(18f, type.deviceName, 0.0001f)
        assertEquals(24f, type.tvButton, 0.0001f)
        assertEquals(16f, type.phoneButton, 0.0001f)
        assertEquals(14f, type.phoneButtonDense, 0.0001f)
        assertEquals(12f, type.phoneButtonCompact, 0.0001f)
    }

    @Test
    fun `Spacing Radius Motion 与旧常量逐值相等`() {
        val spacing = ThemeTokens.spacing
        assertEquals(listOf(4, 8, 12, 16, 24, 32), listOf(spacing.xs, spacing.sm, spacing.md, spacing.lg, spacing.xl, spacing.xxl))
        val radii = ThemeTokens.radii
        assertEquals(listOf(6, 14, 16, 12, 24), listOf(radii.focus, radii.button, radii.card, radii.inset, radii.dialog))
        val motion = ThemeTokens.motion
        assertEquals(listOf(120L, 200L, 150L), listOf(motion.focus, motion.connect, motion.codeFade))
    }

    @Test
    fun `旧 Theme 常量全部指向令牌，不保留第二套值`() {
        assertEquals(semantic.background, Theme.BACKGROUND)
        assertEquals(semantic.surface, Theme.SURFACE)
        assertEquals(semantic.accent, Theme.ACCENT)
        assertEquals(semantic.textPrimary, Theme.TEXT_PRIMARY)
        assertEquals(semantic.textSecondary, Theme.TEXT_SECONDARY)
        assertEquals(semantic.offline, Theme.OFFLINE)
        assertEquals(semantic.danger, Theme.DANGER)
        assertEquals(semantic.focusFill, Theme.BACKGROUND_ALPHA_40)
        assertEquals(semantic.scrim, Theme.BACKGROUND_ALPHA_80)
        assertEquals(semantic.scrimStrong, Theme.BACKGROUND_ALPHA_90)
        assertEquals(semantic.panel, Theme.SURFACE_ALPHA_90)
        assertEquals(semantic.divider, Theme.DIVIDER)
        assertEquals(semantic.surfaceElevated, Theme.SURFACE_ELEVATED)
        assertEquals(semantic.surfaceStroke, Theme.SURFACE_STROKE)
        assertEquals(semantic.qrLight, Theme.QR_BACKGROUND)
        assertEquals(semantic.scrim, Theme.SCRIM)
        assertEquals(semantic.scrimStrong, Theme.SCRIM_STRONG)
        assertEquals(semantic.panel, Theme.PANEL)
        assertEquals(semantic.focusFill, Theme.FOCUS_FILL)
        assertSame(component, Theme.component)
    }

    @Test
    fun `ThemeResolver 只发布 Midnight，OLED 只预留能力位`() {
        assertSame(MidnightTheme, ThemeResolver.resolve(ThemeId.MIDNIGHT))
        assertSame(OledTheme, ThemeResolver.resolve(ThemeId.OLED))
        assertEquals(listOf(ThemeId.MIDNIGHT), ThemeResolver.releaseThemes().map { it.id })
        assertFalse(MidnightTheme.capabilities.supportsTrueBlack)
        assertTrue(MidnightTheme.capabilities.releaseSelectable)
        assertTrue(OledTheme.capabilities.supportsTrueBlack)
        assertFalse(OledTheme.capabilities.releaseSelectable)
        assertFalse(OledTheme.capabilities.debugPreview)
    }

    @Test
    fun `未知主题不会静默回退到 Midnight`() {
        // 枚举 when 已覆盖全部分支；这条断言防止未来新增 ThemeId 时漏配。
        assertEquals(ThemeId.values().toSet(), setOf(ThemeId.MIDNIGHT, ThemeId.OLED))
        assertTrue(ThemeId.values().all { ThemeResolver.resolve(it).id == it })
    }
}
