package io.github.wangnan05563.airmirror.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {

    @Test
    fun defaultNameKeepsModelAndAppendsTwoDigitSuffix() {
        val name = Onboarding.defaultDeviceName("SM-G998B") { 42 }
        assertEquals("SMG998B-42", name)
    }

    @Test
    fun defaultNameStripsSpacesAndSymbolsFromModel() {
        assertEquals("GoogleTV-07", Onboarding.defaultDeviceName("Google TV ", { 7 }))
        assertEquals("小米电视-00", Onboarding.defaultDeviceName("小米电视", { 0 }))
    }

    @Test
    fun defaultNameFallsBackToTvWhenModelIsEmptyOrSymbolOnly() {
        assertEquals("电视-13", Onboarding.defaultDeviceName("", { 13 }))
        assertEquals("电视-99", Onboarding.defaultDeviceName("@@@###", { 99 }))
    }

    @Test
    fun defaultNameCoercesOutOfRangeRandomIntoTwoDigits() {
        assertEquals("Model-00", Onboarding.defaultDeviceName("Model", { -5 }))
        assertEquals("Model-99", Onboarding.defaultDeviceName("Model", { 250 }))
        assertEquals("Model-05", Onboarding.defaultDeviceName("Model", { 5 }))
    }

    @Test
    fun defaultNameTruncatesOverlongModelBeforeSuffix() {
        val longModel = "A".repeat(40)
        val name = Onboarding.defaultDeviceName(longModel) { 1 }
        // 机型部分截断到 12 位，再加 "-01"
        assertEquals("A".repeat(12) + "-01", name)
    }

    @Test
    fun sanitizeNameTrimsAndCollapsesWhitespace() {
        assertEquals("Living Room", Onboarding.sanitizeName("  Living   Room  ", "fallback"))
        assertEquals("客厅电视", Onboarding.sanitizeName("客厅电视", "fallback"))
    }

    @Test
    fun sanitizeNameTruncatesToMaxLength() {
        val long = "B".repeat(100)
        assertEquals(Onboarding.MAX_NAME_LEN, Onboarding.sanitizeName(long, "fallback").length)
    }

    @Test
    fun sanitizeNameFallsBackWhenBlank() {
        assertEquals("默认名", Onboarding.sanitizeName("   ", "默认名"))
        assertEquals("默认名", Onboarding.sanitizeName("", "默认名"))
    }

    @Test
    fun sanitizeNameKeepsValidInputUnchanged() {
        val input = "我的客厅电视"
        assertTrue(Onboarding.sanitizeName(input, "fallback") == input)
        assertFalse(Onboarding.sanitizeName(input, "fallback").isEmpty())
    }
}
