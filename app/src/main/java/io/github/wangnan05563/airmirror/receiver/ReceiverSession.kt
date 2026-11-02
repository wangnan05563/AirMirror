package io.github.wangnan05563.airmirror.receiver

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Surface
import io.github.wangnan05563.airmirror.media.VideoDecoder
import io.github.wangnan05563.airmirror.net.ServiceAdvertiser
import io.github.wangnan05563.airmirror.net.StreamConnection
import io.github.wangnan05563.airmirror.protocol.Handshake
import io.github.wangnan05563.airmirror.protocol.Protocol
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 接收端会话：随机端口 TCP 服务 + NSD 广播 + 版本握手 + H.264 解码渲染。
 *
 * 会话生命周期与 Activity 的 Surface 解耦：界面切到后台时继续收流，
 * 有 Surface 才建解码器；回到前台重新挂 Surface 后用缓存的编码配置重建解码器。
 */
class ReceiverSession(
    private val context: Context,
    private val onStatus: (String) -> Unit,
) {
    private val server = ServerSocket(0)
    private val port: Int = server.localPort
    private var advertiser: ServiceAdvertiser? = null
    private var worker: Thread? = null
    private var client: Socket? = null

    @Volatile
    private var running = false

    @Volatile
    private var decoder: VideoDecoder? = null

    @Volatile
    private var pendingConfig: ByteArray? = null

    fun start(advertisedName: String) {
        if (running) return
        running = true
        advertiser = ServiceAdvertiser(context, advertisedName, port).also { it.start() }
        onStatus("等待连接 · $advertisedName:$port")
        worker = thread(name = "airmirror-receiver") {
            try {
                while (running) {
                    val socket = server.accept()
                    client = socket
                    onStatus("已连接 ${socket.inetAddress?.hostAddress ?: "发送端"}")
                    handle(socket)
                    onStatus("连接结束，继续等待")
                }
            } catch (e: Exception) {
                if (running) onStatus("服务异常：${e.message}")
            }
        }
    }

    /** Surface 就绪：若已有缓存的编码配置，立刻建解码器。 */
    fun attachSurface(surface: Surface) {
        val codec = VideoDecoder(surface)
        pendingConfig?.let { config ->
            runCatching { codec.configure(config) }
                .onFailure { onStatus("解码器初始化失败：${it.message}") }
        }
        decoder = codec
    }

    fun detachSurface() {
        decoder?.release()
        decoder = null
    }

    private fun handle(socket: Socket) {
        StreamConnection(socket).use { conn ->
            val hello = conn.acceptAsServer(Build.MODEL, Handshake.Capability.VIDEO_H264)
            onStatus("${hello.deviceName} 已连接")
            var received = 0
            var decoded = 0
            try {
                while (running) {
                    val frame = conn.receive()
                    if (frame == null) {
                        Log.i(TAG, "peer closed after received=$received decoded=$decoded")
                        break
                    }
                    when (frame.type) {
                        Protocol.Type.VIDEO -> {
                            if (frame.flags and Protocol.Flags.CONFIG != 0) {
                                pendingConfig = frame.payload
                                decoder?.let {
                                    runCatching { it.configure(frame.payload) }
                                        .onFailure { error -> onStatus("解码器初始化失败：${error.message}") }
                                }
                                Log.i(TAG, "config received: ${frame.payload.size}B, decoder=${decoder != null}")
                                onStatus("${hello.deviceName} 推流中")
                            } else {
                                received++
                                val codec = decoder
                                if (codec == null) {
                                    if (received == 1) Log.w(TAG, "video frame without surface decoder")
                                } else {
                                    runCatching { codec.decode(frame.payload, frame.ptsUs) }
                                        .onSuccess { decoded++ }
                                        .onFailure { Log.w(TAG, "decode failed", it) }
                                }
                                if (received % 30 == 0) {
                                    val rendered = codec?.renderedFrames ?: 0L
                                    Log.i(TAG, "received=$received decoded=$decoded rendered=$rendered")
                                    onStatus("${hello.deviceName} · 收 $received / 解码 $decoded / 渲染 $rendered 帧")
                                }
                            }
                        }

                        Protocol.Type.PING -> conn.send(Protocol.Frame(Protocol.Type.PONG, payload = frame.payload))
                        Protocol.Type.BYE -> return
                        else -> Unit
                    }
                }
            } finally {
                decoder?.release()
                decoder = null
            }
        }
    }

    fun stop() {
        running = false
        runCatching { client?.close() }
        runCatching { server.close() }
        advertiser?.stop()
        advertiser = null
        worker?.join(1_000)
        worker = null
        Log.i(TAG, "session stopped")
    }

    private companion object {
        const val TAG = "AirMirrorReceiver"
    }
}
