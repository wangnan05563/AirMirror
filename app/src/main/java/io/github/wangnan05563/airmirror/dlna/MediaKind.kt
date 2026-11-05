package io.github.wangnan05563.airmirror.dlna

import java.util.Locale

/** PRD FS-02 的媒体类型：video / audio / image；OTHER 只表示识别不出来，仍按视频链路尝试。 */
enum class MediaKind { VIDEO, AUDIO, IMAGE, OTHER }

/**
 * 媒体类型识别（纯逻辑，可在 JVM 单测直接跑）。
 *
 * 控制点的 DIDL 三种线索可信度不同：`upnp:class` 最权威，`protocolInfo` 的 MIME 次之，
 * 扩展名只是兜底——有些控制点只填 `http-get:*:*:*`，这时只能看地址后缀。
 */
object MediaKinds {

    fun of(meta: Upnp.MediaMeta): MediaKind = of(meta.upnpClass, meta.mime, meta.uri)

    fun of(upnpClass: String, mime: String, uri: String): MediaKind {
        val cls = upnpClass.lowercase(Locale.US)
        when {
            "imageitem" in cls -> return MediaKind.IMAGE
            "videoitem" in cls -> return MediaKind.VIDEO
            "audioitem" in cls -> return MediaKind.AUDIO
        }
        val type = mime.lowercase(Locale.US)
        when {
            type.startsWith("image/") -> return MediaKind.IMAGE
            type.startsWith("video/") -> return MediaKind.VIDEO
            type.startsWith("audio/") -> return MediaKind.AUDIO
        }
        return byExtension(uri.substringAfterLast('/').substringBefore('?'))
    }

    private fun byExtension(name: String): MediaKind {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif" -> MediaKind.IMAGE
            "mp4", "m4v", "mkv", "webm", "avi", "mov", "ts", "3gp", "flv", "mpg", "mpeg" -> MediaKind.VIDEO
            "mp3", "aac", "m4a", "flac", "wav", "ogg", "opus", "wma", "amr" -> MediaKind.AUDIO
            else -> MediaKind.OTHER
        }
    }
}
