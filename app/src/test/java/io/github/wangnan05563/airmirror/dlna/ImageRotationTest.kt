package io.github.wangnan05563.airmirror.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD FS-02「图片按 15 秒轮播」的判定：间隔写死、暂停不轮播、末张不自动回卷（列表循环属 P1）。 */
class ImageRotationTest {

    @Test
    fun intervalMatchesProductSpec() {
        assertEquals(15_000L, ImageRotation.INTERVAL_MS)
    }

    @Test
    fun rotatesOnlyForMultiImageWhilePlaying() {
        assertTrue(ImageRotation.shouldRotate(MediaKind.IMAGE, 3, playing = true))
        // 单张图没有「下一张」
        assertFalse(ImageRotation.shouldRotate(MediaKind.IMAGE, 1, playing = true))
        // 暂停时停在当前图，别在用户暂停后继续翻页
        assertFalse(ImageRotation.shouldRotate(MediaKind.IMAGE, 3, playing = false))
        // 视频/音频没有轮播概念
        assertFalse(ImageRotation.shouldRotate(MediaKind.VIDEO, 3, playing = true))
    }

    @Test
    fun advancesOneByOne() {
        assertEquals(1, ImageRotation.nextIndex(0, 3))
        assertEquals(2, ImageRotation.nextIndex(1, 3))
    }

    @Test
    fun stopsAtLastImage() {
        assertNull(ImageRotation.nextIndex(2, 3))
        assertNull(ImageRotation.nextIndex(0, 1))
        assertNull(ImageRotation.nextIndex(0, 0))
    }
}
