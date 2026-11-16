package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI-07 关于页口径：版本号、许可证摘要、开源声明入口三件事必须能被单测钉住。 */
class AboutContentTest {

    @Test
    fun versionLine_keeps_fullVersionName() {
        assertEquals("版本 0.1.0-m0.20261116-003", AboutContent.versionLine("0.1.0-m0.20261116-003"))
    }

    @Test
    fun versionLine_handlesBlankWithoutCrash() {
        assertEquals("版本 未知", AboutContent.versionLine(""))
    }

    @Test
    fun licenseSummary_namesAllBundledOpenSourceComponents() {
        assertTrue(AboutContent.LICENSE_SUMMARY.contains("AndroidX AppCompat"))
        assertTrue(AboutContent.LICENSE_SUMMARY.contains("Material Components"))
        assertTrue(AboutContent.LICENSE_SUMMARY.contains("ZXing Core"))
        assertTrue(AboutContent.LICENSE_SUMMARY.contains("Apache License 2.0"))
    }

    @Test
    fun openSourceNotice_hasApacheTextAndProjectEntry() {
        assertTrue(AboutContent.OPEN_SOURCE_NOTICE.contains("Apache License 2.0"))
        assertTrue(AboutContent.OPEN_SOURCE_NOTICE.contains(AboutContent.PROJECT_URL))
    }

    @Test
    fun projectUrl_isHttpsGitHub() {
        assertTrue(AboutContent.PROJECT_URL.startsWith("https://github.com/"))
    }

    @Test
    fun packageLine_keepsApplicationId() {
        assertEquals("包名 io.github.wangnan05563.airmirror",
            AboutContent.packageLine("io.github.wangnan05563.airmirror"))
    }
}
