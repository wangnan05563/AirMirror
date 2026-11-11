package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-01 令牌取值锁：所有值必须与《AirMirror-UI风格规范冻结-20261109》一致。
 * 令牌化只搬位置、不改渲染值；这里把冻结值钉死，改值需产品签字后同步本测试。
 */
class ThemeTokenTest {

    @Test
    fun `主色板与冻结规范一致`() {
        assertEquals(0xFF0B1F3A.toInt(), Theme.BACKGROUND)
        assertEquals(0xFF12314F.toInt(), Theme.SURFACE)
        assertEquals(0xFF17C3B2.toInt(), Theme.ACCENT)
        assertEquals(0xFFFFFFFF.toInt(), Theme.TEXT_PRIMARY)
        assertEquals(0xB3FFFFFF.toInt(), Theme.TEXT_SECONDARY)
        assertEquals(0x4DFFFFFF.toInt(), Theme.OFFLINE)
        assertEquals(0xFFFF6B6B.toInt(), Theme.DANGER)
    }

    @Test
    fun `派生透明度色不改渲染值`() {
        assertEquals(0x660B1F3A.toInt(), Theme.BACKGROUND_ALPHA_40)
        assertEquals(0xCC0B1F3A.toInt(), Theme.BACKGROUND_ALPHA_80)
        assertEquals(0xE60B1F3A.toInt(), Theme.BACKGROUND_ALPHA_90)
        assertEquals(0xE612314F.toInt(), Theme.SURFACE_ALPHA_90)
        assertEquals(0x3312314F.toInt(), Theme.DIVIDER)
        assertEquals(0xFFFFFFFF.toInt(), Theme.QR_BACKGROUND)
    }

    @Test
    fun `字号满足三端下限且与冻结值一致`() {
        assertEquals(40f, Theme.TITLE_SP, 0.0001f)
        assertEquals(24f, Theme.BODY_SP, 0.0001f)
        assertEquals(14f, Theme.PHONE_BODY_SP, 0.0001f)
        assertEquals(36f, Theme.CODE_SP, 0.0001f)
        assertEquals(32f, Theme.PHONE_TITLE_SP, 0.0001f)
        assertEquals(28f, Theme.SENDER_TITLE_SP, 0.0001f)
        assertEquals(18f, Theme.DEVICE_NAME_SP, 0.0001f)
        assertTrue(Theme.BODY_SP >= 24f)
        assertTrue(Theme.TITLE_SP >= 40f)
        assertTrue(Theme.PHONE_BODY_SP >= 14f)
    }

    @Test
    fun `动效时长唯一来源在 Theme，FocusPolicy 只委托`() {
        assertEquals(120L, Theme.DURATION_FOCUS_MS)
        assertEquals(200L, Theme.DURATION_CONNECT_FADE_MS)
        assertEquals(150L, Theme.DURATION_CODE_FADE_MS)
        assertEquals(Theme.DURATION_FOCUS_MS, FocusPolicy.FOCUS_ANIM_MS)
        assertEquals(Theme.DURATION_CONNECT_FADE_MS, FocusPolicy.CONNECT_FADE_MS)
        assertEquals(Theme.DURATION_FOCUS_MS, FocusPolicy.focusAnimMs(true))
        assertEquals(Theme.DURATION_CONNECT_FADE_MS, FocusPolicy.fadeDurationMs(true))
        assertEquals(Theme.DURATION_CODE_FADE_MS, FocusPolicy.codeFadeMs(true))
    }

    @Test
    fun `C 级设备三个动效都归零`() {
        assertEquals(0L, FocusPolicy.focusAnimMs(false))
        assertEquals(0L, FocusPolicy.fadeDurationMs(false))
        assertEquals(0L, FocusPolicy.codeFadeMs(false))
    }

    @Test
    fun `圆角与留边刻度取值固定`() {
        assertEquals(6, Theme.RADIUS_FOCUS_DP)
        assertEquals(4, Theme.SPACE_XS_DP)
        assertEquals(8, Theme.SPACE_SM_DP)
        assertEquals(12, Theme.SPACE_MD_DP)
        assertEquals(16, Theme.SPACE_LG_DP)
        assertEquals(24, Theme.SPACE_XL_DP)
        assertEquals(32, Theme.SPACE_XXL_DP)
    }

    @Test
    fun `手机标题经 FocusPolicy 取 Theme 令牌`() {
        assertEquals(Theme.PHONE_TITLE_SP, FocusPolicy.titleSp(false), 0.0001f)
        assertEquals(Theme.TITLE_SP, FocusPolicy.titleSp(true), 0.0001f)
    }
}
