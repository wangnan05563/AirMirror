package io.github.wangnan05563.airmirror.pc.audio

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Windows 默认渲染端点的 WASAPI 回环采集（REQ-003）。
 *
 * 不依赖虚拟声卡、不要求「立体声混音」；直接向系统混音器要播放中的声音。
 * COM 调用走 JNA 的 vtable 函数指针，避免额外引入 JNA-platform 与 COM 封装。
 * 事件驱动、共享模式、100ms 缓冲；回调给 [PcmConverter] 的是设备原始 PCM。
 */
class WasapiLoopbackCapture(
    private val onPcm: (ByteArray) -> Unit,
    private val onFormat: (Format) -> Unit = {},
    /** 系统标记为静音（`AUDCLNT_BUFFERFLAGS_SILENT`）或本次没有可读数据时回调。 */
    private val onSilent: () -> Unit = {},
    private val onError: (String) -> Unit = {},
) : AutoCloseable {
    data class Format(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val isFloat: Boolean,
        val blockAlign: Int,
    )

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile
    private var client: Pointer? = null

    @Volatile
    private var capture: Pointer? = null

    @Volatile
    private var event: Pointer? = null

    @Volatile
    private var mixFormat: Pointer? = null

    fun start(): Boolean {
        if (!isSupported()) {
            onError("当前系统不是 Windows，无法使用 WASAPI")
            return false
        }
        if (!running.compareAndSet(false, true)) return true
        thread = Thread({ runLoop() }, "airmirror-wasapi").apply {
            isDaemon = true
            start()
        }
        return true
    }

    override fun close() {
        running.set(false)
        thread?.let { runCatching { it.join(1500) } }
        thread = null
        releaseCom()
    }

    private fun runLoop() {
        try {
            val ole32 = Native.load("ole32", Ole32::class.java)
            val hr = ole32.CoInitializeEx(null, COINIT_MULTITHREADED)
            val comReady = hr == S_OK || hr == S_FALSE || hr == RPC_E_CHANGED_MODE
            if (!comReady) fail("CoInitializeEx 失败 hr=0x${hex(hr)}")

            val enumerator = activateEnumerator(ole32)
            val device = defaultRenderDevice(enumerator)
            val audioClient = activateAudioClient(device)
            val format = readMixFormat(audioClient)
            onFormat(format)
            initializeClient(audioClient, format)
            val captureClient = getCaptureClient(audioClient)
            val win = Native.load("kernel32", Kernel32Api::class.java)
            val handle = win.CreateEventW(null, 0, 0, null) ?: fail("CreateEventW 失败")
            event = handle
            checkHr(call(audioClient, 13, handle), "SetEventHandle")
            checkHr(call(audioClient, 10), "Start")
            client = audioClient
            capture = captureClient

            val formatPtr = mixFormat
            val blockAlign = format.blockAlign
            val wait = Native.load("kernel32", Kernel32Api::class.java)
            val dataOut = Memory(8)
            val framesOut = Memory(4)
            val flagsOut = Memory(4)
            val devPosOut = Memory(8)
            val qpcOut = Memory(8)

            while (running.get()) {
                val signaled = wait.WaitForSingleObject(handle, 50)
                if (signaled != WAIT_OBJECT_0) continue
                var packet = nextPacketSize(captureClient)
                while (packet > 0 && running.get()) {
                    val got = call(
                        captureClient, 3,
                        dataOut, framesOut, flagsOut, devPosOut, qpcOut,
                    )
                    if (got < 0) {
                        fail("IAudioCaptureClient.GetBuffer hr=0x${hex(got)}")
                        return
                    }
                    val data = dataOut.getPointer(0)
                    val frames = framesOut.getInt(0)
                    val flags = flagsOut.getInt(0)
                    val silent = (flags and AUDCLNT_BUFFERFLAGS_SILENT) != 0
                    if (data != null && frames > 0 && !silent) {
                        val bytes = ByteArray(frames * blockAlign)
                        data.read(0, bytes, 0, bytes.size)
                        if (running.get()) onPcm(bytes)
                    } else if (running.get()) {
                        // 静音包没有 PCM，但仍是「系统此刻没有可转发声音」的事实；
                        // 交给上层做连续静音计时，不能把这一段直接当成正常数据。
                        onSilent()
                    }
                    call(captureClient, 4, frames)
                    packet = nextPacketSize(captureClient)
                }
            }
            runCatching { call(audioClient, 11) }
        } catch (t: Throwable) {
            fail(t.message ?: t::class.java.simpleName)
        } finally {
            running.set(false)
        }
    }

    private fun fail(message: String): Nothing {
        running.set(false)
        onError(message)
        throw IllegalStateException(message)
    }

    private fun activateEnumerator(ole32: Ole32): Pointer {
        val out = Memory(8)
        val hr = ole32.CoCreateInstance(
            guid(CLSID_MM_DEVICE_ENUMERATOR), null, CLSCTX_ALL,
            guid(IID_IMM_DEVICE_ENUMERATOR), out,
        )
        checkHr(hr, "CoCreateInstance(IMMDeviceEnumerator)")
        return out.getPointer(0) ?: fail("IMMDeviceEnumerator 为空")
    }

    private fun defaultRenderDevice(enumerator: Pointer): Pointer {
        val out = Memory(8)
        checkHr(call(enumerator, 4, E_RENDER, E_CONSOLE, out), "GetDefaultAudioEndpoint")
        return out.getPointer(0) ?: fail("默认渲染端点为空")
    }

    private fun activateAudioClient(device: Pointer): Pointer {
        val out = Memory(8)
        checkHr(call(device, 3, guid(IID_IAUDIO_CLIENT), CLSCTX_ALL, null, out), "IMMDevice.Activate")
        return out.getPointer(0) ?: fail("IAudioClient 为空")
    }

    private fun readMixFormat(audioClient: Pointer): Format {
        val out = Memory(8)
        checkHr(call(audioClient, 8, out), "GetMixFormat")
        val ptr = out.getPointer(0) ?: fail("GetMixFormat 返回空")
        mixFormat = ptr
        val tag = ptr.getShort(0).toInt() and 0xFFFF
        val channels = ptr.getShort(2).toInt() and 0xFFFF
        val sampleRate = ptr.getInt(4)
        val blockAlign = ptr.getShort(12).toInt() and 0xFFFF
        val bits = ptr.getShort(14).toInt() and 0xFFFF
        val subFormat = if (tag == WAVE_FORMAT_EXTENSIBLE && ptr.getInt(16) >= 22) ptr.getInt(24) else tag
        val isFloat = subFormat == WAVE_FORMAT_IEEE_FLOAT
        if (!isFloat && subFormat != WAVE_FORMAT_PCM) {
            fail("不支持的混音格式 tag=0x${hex(subFormat)}")
        }
        return Format(sampleRate, channels, bits, isFloat, blockAlign)
    }

    private fun initializeClient(audioClient: Pointer, format: Format) {
        val bufferDuration = 100L * 10_000L
        val flags = AUDCLNT_STREAMFLAGS_LOOPBACK or AUDCLNT_STREAMFLAGS_EVENTCALLBACK
        checkHr(
            call(audioClient, 3, 0, flags, bufferDuration, 0L, mixFormat, null),
            "IAudioClient.Initialize(loopback)",
        )
        if (format.channels !in 1..2 || format.sampleRate < 8_000) {
            fail("混音格式异常：${format.sampleRate}Hz/${format.channels}ch/${format.bitsPerSample}bit")
        }
    }

    private fun getCaptureClient(audioClient: Pointer): Pointer {
        val out = Memory(8)
        checkHr(call(audioClient, 14, guid(IID_IAUDIO_CAPTURE_CLIENT), out), "GetService(IAudioCaptureClient)")
        return out.getPointer(0) ?: fail("IAudioCaptureClient 为空")
    }

    private fun nextPacketSize(captureClient: Pointer): Int {
        val out = Memory(4)
        val hr = call(captureClient, 5, out)
        if (hr < 0) return 0
        return out.getInt(0)
    }

    private fun releaseCom() {
        val win = runCatching { Native.load("kernel32", Kernel32Api::class.java) }.getOrNull()
        val audio = client
        if (audio != null) {
            runCatching { call(audio, 11) }
            runCatching { call(audio, 2) }
        }
        capture?.let { runCatching { call(it, 2) } }
        event?.let { win?.CloseHandle(it) }
        mixFormat?.let { runCatching { Native.load("ole32", Ole32::class.java).CoTaskMemFree(it) } }
        client = null
        capture = null
        event = null
        mixFormat = null
    }

    private fun call(obj: Pointer, index: Int, vararg args: Any?): Int {
        val fn = Function.getFunction(obj.getPointer(index.toLong() * Native.POINTER_SIZE.toLong()), 0)
        return fn.invokeInt(arrayOf<Any?>(obj, *args))
    }

    private fun checkHr(hr: Int, what: String) {
        if (hr < 0) fail("$what 失败 hr=0x${hex(hr)}")
    }

    private fun hex(value: Int): String = Integer.toHexString(value)

    private fun guid(text: String): Memory {
        val parts = text.split("-")
        val memory = Memory(16)
        memory.setInt(0, parts[0].toLong(16).toInt())
        memory.setShort(4, parts[1].toLong(16).toShort())
        memory.setShort(6, parts[2].toLong(16).toShort())
        val tail = parts[3] + parts[4]
        for (i in 0 until 8) {
            memory.setByte(8L + i, tail.substring(i * 2, i * 2 + 2).toInt(16).toByte())
        }
        return memory
    }

    interface Ole32 : Library {
        fun CoInitializeEx(pvReserved: Pointer?, coInit: Int): Int
        fun CoCreateInstance(rclsid: Pointer, pUnkOuter: Pointer?, dwClsContext: Int, riid: Pointer, ppv: Pointer): Int
        fun CoTaskMemFree(pv: Pointer?)
    }

    interface Kernel32Api : Library {
        fun CreateEventW(attributes: Pointer?, manualReset: Int, initialState: Int, name: String?): Pointer?
        fun WaitForSingleObject(handle: Pointer, milliseconds: Int): Int
        fun CloseHandle(handle: Pointer): Int
    }

    companion object {
        private const val S_OK = 0
        private const val S_FALSE = 1
        private const val RPC_E_CHANGED_MODE = 0x80010106.toInt()
        private const val COINIT_MULTITHREADED = 0
        private const val CLSCTX_ALL = 23
        private const val E_RENDER = 0
        private const val E_CONSOLE = 0
        private const val WAIT_OBJECT_0 = 0

        private const val WAVE_FORMAT_PCM = 1
        private const val WAVE_FORMAT_IEEE_FLOAT = 3
        private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE

        private const val AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000
        private const val AUDCLNT_STREAMFLAGS_EVENTCALLBACK = 0x00040000
        private const val AUDCLNT_BUFFERFLAGS_SILENT = 0x2

        private const val CLSID_MM_DEVICE_ENUMERATOR = "BCDE0395-E52F-467C-8E3D-C4579291692E"
        private const val IID_IMM_DEVICE_ENUMERATOR = "A95664D2-9614-4F35-A746-DE8DB63617E6"
        private const val IID_IAUDIO_CLIENT = "1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"
        private const val IID_IAUDIO_CAPTURE_CLIENT = "C8ADBD64-E71E-48A0-A4DE-185C395CD317"

        fun isSupported(): Boolean {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            return os.contains("win")
        }
    }
}
