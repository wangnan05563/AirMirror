package io.github.wangnan05563.airmirror.media

import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
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

    /** 主线程回调：媒体就绪（未起播）／播放到末尾。 */
    var onPreparedCallback: (() -> Unit)? = null
    var onCompletedCallback: (() -> Unit)? = null

    /** 起播请求的时刻（nanoTime），首帧到达即打点；仅主线程读写（PRD 14.1「视频推送起播」）。 */
    private var playAtNano = 0L

    fun attach(holder: SurfaceHolder?) {
        main.post {
            this.holder = holder
            player?.let { bindSurface(it, holder) }
        }
    }

    /** 载入地址；返回 false 表示当场就失败（地址不合法或不支持）。 */
    fun load(uri: String): Boolean {
        lastError = null
        completed = false
        prepared = false
        playing = false
        if (!uri.startsWith("http://") && !uri.startsWith("https://") && !uri.startsWith("file://")) {
            lastError = "unsupported uri scheme"
            return false
        }
        main.post {
            releasePlayer()
            pendingPlay = false
            playAtNano = 0L
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            try {
                mediaPlayer.setDataSource(uri)
                bindSurface(mediaPlayer, holder)
                mediaPlayer.setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && playAtNano > 0L) {
                        val firstFrameMs = (System.nanoTime() - playAtNano) / 1_000_000
                        playAtNano = 0L
                        Log.i(TAG, "first frame ${firstFrameMs}ms after play")
                    }
                    false
                }
                mediaPlayer.setOnPreparedListener {
                    prepared = true
                    if (pendingPlay) start(it)
                    onPreparedCallback?.invoke()
                }
                mediaPlayer.setOnCompletionListener {
                    completed = true
                    playing = false
                    Log.i(TAG, "playback completed")
                    onCompletedCallback?.invoke()
                }
                mediaPlayer.setOnErrorListener { _, what, extra ->
                    val description = "MediaPlayer what=$what extra=$extra"
                    lastError = description
                    prepared = false
                    playing = false
                    Log.w(TAG, description)
                    true
                }
                mediaPlayer.prepareAsync()
            } catch (e: IOException) {
                lastError = e.message
                Log.w(TAG, "load failed: ${e.message}")
            }
        }
        return true
    }

    private fun bindSurface(mediaPlayer: MediaPlayer, surfaceHolder: SurfaceHolder?) {
        val target = surfaceHolder ?: return
        if (Build.VERSION.SDK_INT >= 21) {
            runCatching { mediaPlayer.setSurface(target.surface) }
                .onFailure { Log.w(TAG, "setSurface failed: ${it.message}") }
        } else {
            @Suppress("DEPRECATION")
            runCatching { mediaPlayer.setDisplay(target) }
        }
    }

    fun play() {
        main.post {
            // 起播计时从 Play 落到主线程开始，含「还没准备好」的等待
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

    private fun releasePlayer() {
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
    }
}
