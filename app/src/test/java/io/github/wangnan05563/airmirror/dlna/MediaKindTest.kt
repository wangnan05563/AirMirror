package io.github.wangnan05563.airmirror.dlna

import org.junit.Assert.assertEquals
import org.junit.Test

/** PRD FS-02 媒体类型识别：upnp:class → MIME → 扩展名三级线索。 */
class MediaKindTest {

    @Test
    fun upnpClassWins() {
        assertEquals(MediaKind.IMAGE, MediaKinds.of("object.item.imageItem.photo", "", ""))
        assertEquals(MediaKind.VIDEO, MediaKinds.of("object.item.videoItem", "", ""))
        assertEquals(MediaKind.AUDIO, MediaKinds.of("object.item.audioItem.musicTrack", "", ""))
    }

    @Test
    fun classMatchIgnoresCaseAndSurroundingText() {
        assertEquals(MediaKind.IMAGE, MediaKinds.of("object.item.ImageItem", "", ""))
        assertEquals(MediaKind.VIDEO, MediaKinds.of("object.item.VIDEOITEM.movie", "", ""))
    }

    @Test
    fun mimeIsSecondChance() {
        assertEquals(MediaKind.IMAGE, MediaKinds.of("", "image/jpeg", "http://h/x"))
        assertEquals(MediaKind.VIDEO, MediaKinds.of("", "video/mp4", "http://h/x"))
        assertEquals(MediaKind.AUDIO, MediaKinds.of("", "audio/mpeg", "http://h/x"))
    }

    @Test
    fun extensionIsLastResort() {
        // 一些控制点只填 http-get:*:*:*，protocolInfo 的 MIME 是空的
        assertEquals(MediaKind.IMAGE, MediaKinds.of("", "", "http://h/a.JPG"))
        assertEquals(MediaKind.IMAGE, MediaKinds.of("", "*", "http://h/a.png?w=100"))
        assertEquals(MediaKind.VIDEO, MediaKinds.of("", "", "http://h/a.mkv"))
        assertEquals(MediaKind.AUDIO, MediaKinds.of("", "", "http://h/a.flac"))
    }

    @Test
    fun unknownStaysUnknown() {
        assertEquals(MediaKind.OTHER, MediaKinds.of("", "", "http://h/a"))
        assertEquals(MediaKind.OTHER, MediaKinds.of("", "", "http://h/a.xyz"))
    }

    @Test
    fun metaOverloadUsesBothHints() {
        assertEquals(MediaKind.IMAGE, MediaKinds.of(Upnp.MediaMeta("http://h/1", "t", "", -1L, "image/png")))
        assertEquals(MediaKind.VIDEO, MediaKinds.of(Upnp.MediaMeta("http://h/1", "t", "", -1L, "video/mp4")))
    }
}
