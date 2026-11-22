package io.github.wangnan05563.airmirror.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** T06111：首启向导状态与 JSON 编解码；不依赖屏幕或安装目录。 */
class OnboardingStateTest {

    @Test
    fun `首次启动需要向导，跳过和完成都会阻止自动弹出`() {
        val dir = Files.createTempDirectory("airmirror-onboarding")
        val store = OnboardingStore(dir.resolve("onboarding.json"))
        assertTrue(store.shouldShow())
        assertTrue(store.load().completed.not())

        store.markSkipped()
        assertFalse(store.shouldShow())
        assertTrue(store.load().skipped)

        store.markCompleted("客厅电视", "192.168.50.223", 41523)
        assertFalse(store.shouldShow())
        val state = store.load()
        assertTrue(state.completed)
        assertFalse(state.skipped)
        assertEquals("客厅电视", state.lastDeviceName)
        assertEquals(41523, state.lastPort)
    }

    @Test
    fun `状态里的中文引号与反斜杠能原样往返`() {
        val dir = Files.createTempDirectory("airmirror-onboarding-json")
        val store = OnboardingStore(dir.resolve("onboarding.json"))
        val input = OnboardingStore.State(
            completed = true,
            completedAt = "2026-11-22T12:00:00Z",
            networkStep = "vpn_warning",
            firewallStep = "ok",
            lastDeviceId = "dev-\"001\"",
            lastDeviceName = "客厅电视 \\ 东侧",
            lastHost = "192.168.50.223",
            lastPort = 41523,
            firstFrameAt = "2026-11-22T12:00:01Z",
        )
        store.save(input)
        assertEquals(input, store.load())
    }

    @Test
    fun `损坏的状态文件不阻断启动`() {
        val dir = Files.createTempDirectory("airmirror-onboarding-broken")
        Files.writeString(dir.resolve("onboarding.json"), "{not-json")
        val store = OnboardingStore(dir.resolve("onboarding.json"))
        val state = store.load()
        assertFalse(state.completed)
        assertTrue(store.shouldShow())
    }
}
