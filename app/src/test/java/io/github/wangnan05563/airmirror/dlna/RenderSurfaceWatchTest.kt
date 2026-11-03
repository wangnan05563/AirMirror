package io.github.wangnan05563.airmirror.dlna

import io.github.wangnan05563.airmirror.error.ErrorNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「推过去了但接收端没有可用渲染面」的口径（T04459 选项 2：提醒但不拒）。
 */
class RenderSurfaceWatchTest {

    private val video = "object.item.videoItem"

    @Test
    fun `playing a video with no surface is warned`() {
        assertEquals(
            ErrorNotice.Cause.DISPLAY_UNREADY,
            RenderSurfaceWatch.causeFor(video, wantsPlayback = true, hasSurface = false),
        )
    }

    @Test
    fun `a surface present means nothing to say`() {
        assertNull(RenderSurfaceWatch.causeFor(video, wantsPlayback = true, hasSurface = true))
    }

    @Test
    fun `audio only push is not a picture problem`() {
        assertNull(RenderSurfaceWatch.causeFor("object.item.audioItem.musicTrack", wantsPlayback = true, hasSurface = false))
    }

    @Test
    fun `nothing asked to play means no picture expectation`() {
        assertNull(RenderSurfaceWatch.causeFor(video, wantsPlayback = false, hasSurface = false))
        assertNull(RenderSurfaceWatch.causeFor(null, wantsPlayback = true, hasSurface = false))
    }

    @Test
    fun `the notice stays inside the wording budget and offers a next step`() {
        val notice = ErrorNotice.noticeFor(ErrorNotice.Cause.DISPLAY_UNREADY)
        assertTrue(
            "标题超出 18 字上限：${notice.headline.length}",
            notice.headline.length <= ErrorNotice.MAX_HEADLINE_CHARS,
        )
        assertEquals(ErrorNotice.Category.SESSION, notice.category)
        // 不判失败，所以给的是「等面回来自动接上」而不是「重新投一次」
        assertEquals(listOf(ErrorNotice.Action.AUTO_RECOVER), notice.actions)
    }
}
