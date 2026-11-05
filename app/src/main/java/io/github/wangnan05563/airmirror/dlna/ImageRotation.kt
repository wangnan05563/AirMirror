package io.github.wangnan05563.airmirror.dlna

/**
 * 图片轮播的判定（纯逻辑）。
 *
 * PRD FS-02 参数表：图片按 **15 秒**轮播或手动切换。播放列表循环属 P1，
 * 因此轮播推进到末张就停住——不在这里擅自回卷，避免把 P1 行为偷偷做进 P0。
 */
object ImageRotation {

    /** PRD FS-02 写死的轮播间隔。 */
    const val INTERVAL_MS = 15_000L

    fun shouldRotate(kind: MediaKind, count: Int, playing: Boolean): Boolean =
        kind == MediaKind.IMAGE && playing && count > 1

    /** 返回下一张的索引；已在末张（或只有一张）返回 null，由控制点手动切换。 */
    fun nextIndex(current: Int, count: Int): Int? {
        if (count <= 1) return null
        val next = current + 1
        return if (next < count) next else null
    }
}
