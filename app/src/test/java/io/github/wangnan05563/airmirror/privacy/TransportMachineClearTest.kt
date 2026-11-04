package io.github.wangnan05563.airmirror.privacy

import io.github.wangnan05563.airmirror.dlna.TransportMachine
import io.github.wangnan05563.airmirror.dlna.Upnp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** PRD FS-11「投屏内容留存：不保存；会话结束即删除临时缓存」的内存侧收口。 */
class TransportMachineClearTest {

    private fun media() = Upnp.MediaMeta(
        uri = "http://192.168.1.9:8080/vod/私人录像.mp4",
        title = "私人录像",
        upnpClass = "object.item.videoItem.movie",
        durationMs = 90_000L,
        mime = "video/mp4",
    )

    @Test
    fun `清掉会话内容后片名与地址都不再可读`() {
        val machine = TransportMachine()
        machine.setUri(media())
        machine.onLoaded()
        machine.onPlayInvoked()
        assertEquals("PLAYING", machine.transportState())
        assertNotNull(machine.media)

        machine.clear()

        assertNull(machine.media)
        assertEquals("NO_MEDIA_PRESENT", machine.transportState())
        assertNotNull("清空后不能再接受 Play", machine.canPlay())
        assertEquals(false, machine.wantsPlayback)
    }

    @Test
    fun `clear 幂等，重复调用不抛异常`() {
        val machine = TransportMachine()
        machine.clear()
        machine.setUri(media())
        machine.clear()
        machine.clear()
        assertEquals("NO_MEDIA_PRESENT", machine.transportState())
    }
}
