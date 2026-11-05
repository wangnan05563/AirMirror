package io.github.wangnan05563.airmirror.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkDownloadTest {

    private val temp = File(System.getProperty("java.io.tmpdir"), "airmirror-update-test").apply {
        deleteRecursively()
        mkdirs()
    }

    @After
    fun cleanup() {
        temp.deleteRecursively()
    }

    @Test
    fun `文件名只保留本身 防路径穿越`() {
        assertEquals("app-universal-release.apk", ApkDownload.safeName("app-universal-release.apk"))
        assertEquals("a.apk", ApkDownload.safeName("../../a.apk"))
        assertEquals("a.apk", ApkDownload.safeName("dir\\sub\\a.apk"))
        assertEquals("airmirror-update.apk", ApkDownload.safeName(""))
        assertEquals("airmirror-update.apk", ApkDownload.safeName("notes.txt"))
        assertEquals("airmirror-update.apk", ApkDownload.safeName(".."))
    }

    @Test
    fun `下载成功时内容一致 且不留下 part 文件`() {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val server = serveOnce(payload)
        try {
            val target = File(temp, "app.apk")
            val progress = ArrayList<Long>()
            ApkDownload.download("http://127.0.0.1:${server.port}/app.apk", target) { done, _ ->
                progress += done
            }
            assertArrayEquals(payload, target.readBytes())
            assertTrue(progress.isNotEmpty())
            assertFalse("半截文件不能留下", File(temp, "app.apk.part").exists())
        } finally {
            server.close()
        }
    }

    @Test
    fun `服务端声明长度大于实际时判失败 不产生可安装文件`() {
        val payload = ByteArray(100) { 1 }
        val server = serveOnce(payload, declared = 500)
        try {
            val target = File(temp, "short.apk")
            val error = runCatching {
                ApkDownload.download("http://127.0.0.1:${server.port}/short.apk", target)
            }.exceptionOrNull()
            assertTrue(error != null)
            assertFalse(target.exists())
            assertFalse(File(temp, "short.apk.part").exists())
        } finally {
            server.close()
        }
    }

    @Test
    fun `非 2xx 直接失败`() {
        val server = serveOnce(ByteArray(0), status = "404 Not Found")
        try {
            val error = runCatching {
                ApkDownload.download("http://127.0.0.1:${server.port}/missing.apk", File(temp, "x.apk"))
            }.exceptionOrNull()
            assertTrue(error != null)
        } finally {
            server.close()
        }
    }

    private class OneShot(val socket: ServerSocket) {
        val port: Int get() = socket.localPort
        fun close() = runCatching { socket.close() }.let { }
    }

    private fun serveOnce(
        payload: ByteArray,
        declared: Int = payload.size,
        status: String = "200 OK",
    ): OneShot {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val thread = Thread({
            runCatching {
                server.accept().use { client ->
                    val input = client.getInputStream()
                    readHead(input)
                    val head = "HTTP/1.1 $status\r\nContent-Length: $declared\r\n" +
                        "Content-Type: application/vnd.android.package-archive\r\nConnection: close\r\n\r\n"
                    val out = client.getOutputStream()
                    out.write(head.toByteArray(Charsets.US_ASCII))
                    out.write(payload)
                    out.flush()
                }
            }
            runCatching { server.close() }
        }, "apk-test-server").apply { isDaemon = true }
        thread.start()
        return OneShot(server)
    }

    private fun readHead(input: java.io.InputStream) {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val value = input.read()
            if (value < 0) return
            buffer.write(value)
            matched = when {
                value == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                value == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                else -> 0
            }
        }
    }
}
