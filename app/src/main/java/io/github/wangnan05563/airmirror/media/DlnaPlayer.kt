package io.github.wangnan05563.airmirror.media

import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import io.github.wangnan05563.airmirror.privacy.Redactor
import java.io.IOException

/**
 * DLNA 推送用的播放器包装：MediaPlayer 的回调要在有 Looper 的线程上建，
 * 因此所有操作都投到主线程，控制点线程只拿快照。
 */
class DlnaPlayer {

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var holder: SurfaceHolder? = null

    /** Play 早于准备完成时记下的待播意图，仅主线程读写。 */
    private var pendingPlay = false

    /**
     * PRD FS-02「循环」：单文件播到尾回起点继续。
     * 用 MediaPlayer 自带的 looping 而不是在完成回调里再发一次 start——
     * 后者在 PlaybackCompleted 状态确实可用，但每次循环都要经过一次回调，
     * 中间那一下状态真假难辨；交给播放器自己接最省事。
     */
    @Volatile
    private var looping = false

    @Volatile
    var prepared = false
        private set

    @Volatile
    var playing = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var completed = false
        private set

    /** 主线程回调：媒体就绪（未起播）／播放到末尾／播放失败（what, extra 原样带回，由上层归类）。 */
    var onPreparedCallback: (() -> Unit)? = null
    var onCompletedCallback: (() -> Unit)? = null
    var onErrorCallback: ((Int, Int) -> Unit)? = null

    /**
     * 当前有没有可渲染的面。接收端退后台或息屏时 SurfaceView 的 Surface 会被销毁，
     * 这时「推无音轨的纯视频」会 prepared + start 全成功但一帧不进、进度永远停在 0，
     * 15 秒看门狗管不到它（那条只管未就绪）。上层用这个位来决定要不要提示。
     */
    @Volatile
    var surfaceReady: Boolean = false
        private set

    /** 起播请求的时刻（nanoTime），首帧到达即打点；仅主线程读写（PRD 14.1「视频推送起播」）。 */
    private var playAtNano = 0L

    /** 准备段耗时（毫秒），首帧日志里带回，避免取证脚本只能量到含 SOAP 往返与轮询粒度的上界。 */
    @Volatile
    var prepareMs = 0L
        private set

    /** SetAVTransportURI 落地的时刻（nanoTime）；控制点线程写、主线程读，故为 Volatile。 */
    @Volatile
    private var pushAtNano = 0L

    fun attach(holder: SurfaceHolder?) {
        main.post {
            this.holder = holder
            // surfaceCreated 才带得到有效面；surfaceDestroyed 传进来的是 null
            surfaceReady = holder?.surface?.isValid == true
            Log.i(TAG, "render surface " + if (surfaceReady) "attached" else "cleared")
            player?.let { bindSurface(it, holder) }
        }
    }

    /** 载入地址；返回 false 表示当场就失败（地址不合法或不支持）。 */
    fun load(uri: String): Boolean {
        lastError = null
        completed = false
        prepared = false
        playing = false
        pushAtNano = System.nanoTime()
        if (!uri.startsWith("http://") && !uri.startsWith("https://") && !uri.startsWith("file://")) {
            lastError = "unsupported uri scheme"
            return false
        }
        currentUri = uri
        failed = false
        main.post { openUri(uri) }
        return true
    }

    /** 上一次取流是否以失败收场；失败之后控制点再发 Play 要当成重试，而不是接着谎报在播。 */
    @Volatile
    private var failed = false

    private var currentUri: String? = null

    /** 主线程：真正建 MediaPlayer、挂回调、开始取流。重试也走这里。 */
    private fun openUri(uri: String) {
        releasePlayer()
        pendingPlay = false
        playAtNano = 0L
        prepared = false
        playing = false
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        try {
            mediaPlayer.setDataSource(uri)
            bindSurface(mediaPlayer, holder)
            mediaPlayer.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && playAtNano > 0L) {
                    val firstFrameMs = (System.nanoTime() - playAtNano) / 1_000_000
                    playAtNano = 0L
                    // 分段才看得出瓶颈在取流还是在解码上屏；total 从推送落地起算，不含 SOAP 往返与取证脚本的轮询粒度
                    Log.i(TAG, "first frame ${firstFrameMs}ms after play, prepare ${prepareMs}ms, total ${((System.nanoTime() - pushAtNano) / 1_000_000)}ms after push")
                }
                false
            }
            mediaPlayer.setOnPreparedListener {
                cancelLoadTimeout()
                prepared = true
                prepareMs = (System.nanoTime() - pushAtNano) / 1_000_000
                // setLooping 只能在非 Idle 状态调用，prepared 是第一个安全时点
                runCatching { it.setLooping(looping) }
                if (pendingPlay) start(it)
                onPreparedCallback?.invoke()
            }
            mediaPlayer.setOnCompletionListener {
                cancelLoadTimeout()
                completed = true
                playing = false
                Log.i(TAG, "playback completed")
                onCompletedCallback?.invoke()
            }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                cancelLoadTimeout()
                val description = "MediaPlayer what=$what extra=$extra"
                lastError = description
                prepared = false
                playing = false
                playAtNano = 0L
                failed = true
                Log.w(TAG, description)
                // 光写日志等于没告诉任何人：控制点还在按 PLAYING 轮询，大屏也还是黑的
                onErrorCallback?.invoke(what, extra)
                true
            }
            mediaPlayer.prepareAsync()
            armLoadTimeout()
        } catch (e: IOException) {
            lastError = e.message
            failed = true
            // MediaPlayer 的 IOException 常自带片源地址，进日志前统一脱敏
            Log.w(TAG, "load failed: ${Redactor.throwable(e)}")
            // 建不起来同样要通知：否则状态停在 TRANSITIONING，控制点会一直等到超时
            onErrorCallback?.invoke(1, -1004)
        }
    }

    private fun bindSurface(mediaPlayer: MediaPlayer, surfaceHolder: SurfaceHolder?) {
        val target = surfaceHolder ?: return
        if (Build.VERSION.SDK_INT >= 21) {
            runCatching { mediaPlayer.setSurface(target.surface) }
                .onFailure { Log.w(TAG, "setSurface failed: ${Redactor.throwable(it)}") }
        } else {
            @Suppress("DEPRECATION")
            runCatching { mediaPlayer.setDisplay(target) }
        }
    }

    fun play() {
        main.post {
            val retry = currentUri
            if (failed && retry != null) {
                // 上次取流已经失败，这个 MediaPlayer 不能再 start；Play 就当重试来一次
                failed = false
                openUri(retry)
            }
            // 起播计时从 Play 落到主线程开始，含「还没准备好」的等待；
            // 必须在 openUri 之后取，重试那一支会在 openUri 里清零，否则这一跑的耗时量不出来
            playAtNano = System.nanoTime()
            val mediaPlayer = player ?: return@post
            if (prepared) start(mediaPlayer) else pendingPlay = true
        }
    }

    /** 只在主线程调用；start 在未完成准备时会抛非法状态，此处一并兜住。 */
    private fun start(mediaPlayer: MediaPlayer) {
        if (runCatching { mediaPlayer.start() }.isSuccess) {
            playing = true
            Log.i(TAG, "playback started")
        } else {
            lastError = "start failed"
            Log.w(TAG, "start failed")
        }
    }

    fun pause() {
        main.post {
            player?.let { runCatching { if (it.isPlaying) it.pause() } }
            playing = false
        }
    }

    /**
     * 控制点改 PlayMode 时同步循环开关。未就绪时只记下期望值，
     * prepared 回调会补上（见 openUri 里的 setLooping）。
     */
    fun setLooping(enabled: Boolean) {
        looping = enabled
        main.post {
            val mediaPlayer = player ?: return@post
            if (!prepared) return@post
            runCatching { mediaPlayer.setLooping(enabled) }
        }
    }

    /**
     * UPnP 的 Stop 之后控制点常会直接 Play，而 MediaPlayer.stop() 不可再 start，
     * 因此这里按「暂停并回到片头」实现，保留已准备的媒体。
     */
    fun stop() {
        main.post {
            player?.let { runCatching { if (it.isPlaying) it.pause() } }
            if (prepared) runCatching { player?.seekTo(0) }
            playing = false
            pendingPlay = false
        }
    }

    fun seekTo(ms: Long) {
        main.post {
            val mediaPlayer = player ?: return@post
            if (!prepared) return@post
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) {
                    mediaPlayer.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
                } else {
                    @Suppress("DEPRECATION")
                    mediaPlayer.seekTo(ms.toInt())
                }
            }
        }
    }

    /** 供控制点轮询的快照；未就绪时按 0 报，避免把 -1 这类值写进 DIDL。 */
    fun positionMs(): Long = readProp { it.currentPosition.toLong() }

    fun durationMs(): Long = readProp { it.duration.toLong() }

    private fun readProp(read: (MediaPlayer) -> Long): Long {
        val mediaPlayer = player ?: return 0L
        if (!prepared) return 0L
        return runCatching { read(mediaPlayer) }.getOrNull() ?: 0L
    }

    fun setVolume(volume: Int, muted: Boolean) {
        main.post {
            val mediaPlayer = player ?: return@post
            val level = if (muted) 0f else (volume.coerceIn(0, 100) / 100f)
            runCatching { mediaPlayer.setVolume(level, level) }
        }
    }

    fun release() {
        main.post { releasePlayer() }
    }

    /**
     * PRD FS-02 状态迁移：LOADING 15 秒未就绪判为加载失败。
     * 来源「连得上但一直不给数据」时 MediaPlayer 既不回 prepared 也不回 error，
     * 没有这条超时，状态会永远停在 TRANSITIONING。
     */
    private fun armLoadTimeout() {
        cancelLoadTimeout()
        val pending = Runnable {
            if (prepared || failed) return@Runnable
            lastError = "load timeout"
            failed = true
            Log.w(TAG, "media not ready in ${LOAD_TIMEOUT_MS}ms")
            onErrorCallback?.invoke(1, -110)
        }
        loadTimeout = pending
        main.postDelayed(pending, LOAD_TIMEOUT_MS)
    }

    private fun cancelLoadTimeout() {
        loadTimeout?.let { main.removeCallbacks(it) }
        loadTimeout = null
    }

    private var loadTimeout: Runnable? = null

    private fun releasePlayer() {
        cancelLoadTimeout()
        player?.let {
            runCatching { it.reset() }
            runCatching { it.release() }
        }
        player = null
        prepared = false
        playing = false
    }

    private companion object {
        const val TAG = "AirMirrorDlnaPlayer"
        const val LOAD_TIMEOUT_MS = 15_000L
    }
}
