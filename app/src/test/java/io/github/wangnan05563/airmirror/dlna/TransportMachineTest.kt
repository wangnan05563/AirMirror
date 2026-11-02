package io.github.wangnan05563.airmirror.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun playBeforePreparedStaysPlayingAfterLoad() {
        val m = TransportMachine()
        m.setUri(meta)
        m.onPlayInvoked()
        assertEquals("PLAYING", m.transportState())
        m.onLoaded()
        assertEquals("PLAYING", m.transportState())
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
}
