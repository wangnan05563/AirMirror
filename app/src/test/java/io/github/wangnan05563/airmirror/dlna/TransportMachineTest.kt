package io.github.wangnan05563.airmirror.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AVTransport 状态迁移：控制点看到的必须是 UPnP 口径的状态名。 */
class TransportMachineTest {

    private val meta = Upnp.MediaMeta("http://h/a.mp4", "a", "object.item.videoItem", 10_000L, "video/mp4")

    @Test
    fun startsWithoutMedia() {
        val m = TransportMachine()
        assertEquals("NO_MEDIA_PRESENT", m.transportState())
        assertEquals("NOT_IMPLEMENTED", m.transportStatus())
        assertEquals("", m.currentActions())
        assertEquals(Upnp.FAULT_NO_MEDIA, m.canPlay())
    }

    @Test
    fun setUriEntersTransitioningAndPlayIsAllowed() {
        val m = TransportMachine()
        m.setUri(meta)
        assertEquals("TRANSITIONING", m.transportState())
        assertNull(m.canPlay())
        m.onLoaded()
        assertEquals("STOPPED", m.transportState())
        assertEquals("0", m.currentSpeed())
    }

    @Test
    fun loadedThenPlayPauseStopCycle() {
        val m = TransportMachine()
        m.setUri(meta)
        m.onLoaded()
        m.onPlayInvoked()
        assertEquals("PLAYING", m.transportState())
        assertEquals("1", m.currentSpeed())
        m.onPauseInvoked()
        assertEquals("PAUSED_PLAYING", m.transportState())
        m.onPlayInvoked()
        assertEquals("PLAYING", m.transportState())
        m.onStopInvoked()
        assertEquals("STOPPED", m.transportState())
        // Stop 后媒体仍在，Play 可以直接续播
        m.onPlayInvoked()
        assertEquals("PLAYING", m.transportState())
    }

    @Test
    fun playBeforePreparedStaysTransitioningUntilLoaded() {
        val m = TransportMachine()
        m.setUri(meta)
        m.onPlayInvoked()
        // 画面还没开始走就报 PLAYING，控制点会一直轮询一个黑屏
        assertEquals("TRANSITIONING", m.transportState())
        assertEquals("0", m.currentSpeed())
        m.onLoaded()
        assertEquals("PLAYING", m.transportState())
        assertEquals("1", m.currentSpeed())
    }

    @Test
    fun playPendingIsDroppedByStop() {
        val m = TransportMachine()
        m.setUri(meta)
        m.onPlayInvoked()
        m.onStopInvoked()
        assertEquals("STOPPED", m.transportState())
        m.onLoaded()
        assertEquals("STOPPED", m.transportState())
    }

    @Test
    fun playPendingIsDroppedByFailure() {
        val m = TransportMachine()
        m.setUri(meta)
        m.onPlayInvoked()
        m.onPlaybackFailed()
        assertEquals("STOPPED", m.transportState())
        // 失败后迟到的 prepared 不能把状态又抬回 PLAYING
        m.onLoaded()
        assertEquals("STOPPED", m.transportState())
    }

    @Test
    fun newUriClearsPendingPlay() {
        val m = TransportMachine()
        m.setUri(meta)
        m.onPlayInvoked()
        m.setUri(meta)
        m.onLoaded()
        assertEquals("STOPPED", m.transportState())
    }

    @Test
    fun wantsPlaybackCoversQueuedPlay() {
        val m = TransportMachine()
        assertFalse(m.wantsPlayback)
        m.setUri(meta)
        // 只是推了地址、控制点还没发 Play：没画面不该抱怨
        assertFalse(m.wantsPlayback)
        m.onPlayInvoked()
        assertTrue(m.wantsPlayback)
        m.onLoaded()
        assertTrue(m.wantsPlayback)
        m.onStopInvoked()
        assertFalse(m.wantsPlayback)
    }

    @Test
    fun completionStopsWithMediaRetained() {
        val m = TransportMachine()
        m.setUri(meta)
        m.onPlayInvoked()
        m.onCompleted(loop = false)
        assertEquals("STOPPED", m.transportState())
        assertEquals(meta, m.media)
    }

    @Test
    fun clearUriReturnsToNoMedia() {
        val m = TransportMachine()
        m.setUri(meta)
        m.onLoaded()
        m.setUri(null)
        assertEquals("NO_MEDIA_PRESENT", m.transportState())
        assertEquals(Upnp.FAULT_NO_MEDIA, m.canPlay())
    }

    @Test
    fun actionsAdvertisedFollowState() {
        val m = TransportMachine()
        m.setUri(meta)
        m.onLoaded()
        assertEquals("Play,Stop,Seek,Next", m.currentActions())
        m.onPlayInvoked()
        assertEquals("Play,Pause,Stop,Seek,Next", m.currentActions())
    }

    @Test
    fun pauseAndSeekRejectedOnlyWithoutMedia() {
        val m = TransportMachine()
        assertEquals(Upnp.FAULT_NO_MEDIA, m.canPauseOrSeek())
        m.setUri(meta)
        assertNull(m.canPauseOrSeek())
    }

    @Test
    fun playModeValidatesAgainstSpecList() {
        val m = TransportMachine()
        assertEquals("NORMAL", m.playMode)
        assertNull(m.setPlayMode("REPEAT_ALL"))
        assertEquals("REPEAT_ALL", m.playMode)
        assertEquals(Upnp.FAULT_INVALID_ARGS, m.setPlayMode("REPEAT_EVERYTHING"))
        assertEquals("REPEAT_ALL", m.playMode)
    }

    @Test
    fun `failure while playing must stop claiming PLAYING`() {
        val m = TransportMachine()
        m.setUri(meta)
        m.onLoaded()
        m.onPlayInvoked()
        assertEquals("PLAYING", m.transportState())
        m.onPlaybackFailed()
        assertEquals("STOPPED", m.transportState())
        assertEquals("0", m.currentSpeed())
        // 媒体仍然挂着：控制点要能读到片名并重推同一条地址
        assertEquals("a", m.media?.title)
    }

    @Test
    fun failureDuringLoadingFallsOutOfTransitioning() {
        val m = TransportMachine()
        m.setUri(meta)
        m.onPlaybackFailed()
        assertEquals("STOPPED", m.transportState())
    }

    @Test
    fun failureWithoutMediaDoesNotInventOne() {
        val m = TransportMachine()
        m.onPlaybackFailed()
        assertEquals("NO_MEDIA_PRESENT", m.transportState())
        assertEquals(Upnp.FAULT_NO_MEDIA, m.canPlay())
    }
}
