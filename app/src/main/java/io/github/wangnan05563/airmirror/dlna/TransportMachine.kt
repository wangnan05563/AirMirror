package io.github.wangnan05563.airmirror.dlna

/**
 * AVTransport 状态机（纯逻辑，不含播放器调用）。
 *
 * 状态名用 UPnP 口径：NO_MEDIA_PRESENT / STOPPED / PLAYING / PAUSED_PLAYING / TRANSITIONING。
 * 播放进度由播放器提供，这里只管「能不能转、往哪转」。
 */
class TransportMachine {

    enum class State { NO_MEDIA, LOADING, PLAYING, PAUSED, STOPPED }

    /** 状态由 SOAP 线程改写、主线程播放器回调改写，读取方是另一个连接线程。 */
    @Volatile
    var state: State = State.NO_MEDIA
        private set

    @Volatile
    var media: Upnp.MediaMeta? = null
        private set

    @Volatile
    var playMode: String = "NORMAL"
        private set

    /** 控制点读到的 TransportState。 */
    fun transportState(): String = when (state) {
        State.NO_MEDIA -> "NO_MEDIA_PRESENT"
        State.LOADING -> "TRANSITIONING"
        State.PLAYING -> "PLAYING"
        State.PAUSED -> "PAUSED_PLAYING"
        State.STOPPED -> "STOPPED"
    }

    fun transportStatus(): String = if (state == State.NO_MEDIA) "NOT_IMPLEMENTED" else "OK"

    /** GetTransportInfo 的 CurrentSpeed：UPnP 口径只有 0 与 1。 */
    fun currentSpeed(): String = if (state == State.PLAYING) "1" else "0"

    fun currentActions(): String = when (state) {
        State.PLAYING -> "Play,Pause,Stop,Seek,Next"
        State.PAUSED, State.STOPPED, State.LOADING -> "Play,Stop,Seek,Next"
        State.NO_MEDIA -> ""
    }

    /** 收到新媒体描述：进入 LOADING，由服务端异步准备；未就绪前 Play 视为非法。 */
    fun setUri(meta: Upnp.MediaMeta?) {
        media = meta
        state = if (meta == null) State.NO_MEDIA else State.LOADING
    }

    /** 媒体准备完成（尚未起播）。若期间已收到 Play，则保持 PLAYING 不回退。 */
    fun onLoaded() {
        if (state == State.LOADING) state = State.STOPPED
    }

    fun onPlayInvoked() {
        if (state == State.LOADING || state == State.STOPPED || state == State.PAUSED) state = State.PLAYING
    }

    fun onPauseInvoked() {
        if (state == State.PLAYING) state = State.PAUSED
    }

    fun onStopInvoked() {
        if (media != null) state = State.STOPPED
    }

    fun onCompleted(loop: Boolean) {
        if (!loop && media != null) state = State.STOPPED
    }

    /** Play 前置校验：返回 null 表示允许。 */
    fun canPlay(): Int? = if (media == null) Upnp.FAULT_NO_MEDIA else null

    fun canPauseOrSeek(): Int? = when (state) {
        State.NO_MEDIA -> Upnp.FAULT_NO_MEDIA
        State.PLAYING, State.PAUSED, State.STOPPED, State.LOADING -> null
    }

    fun setPlayMode(mode: String): Int? {
        if (mode !in PLAY_MODES) return Upnp.FAULT_INVALID_ARGS
        playMode = mode
        return null
    }

    companion object {
        val PLAY_MODES = setOf("NORMAL", "REPEAT_ALL", "REPEAT_ONE", "SHUFFLE", "SHUFFLE_NOREPEAT")
    }
}
