package io.github.wangnan05563.airmirror.web

import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCastServerTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "airmirror-browser-cast-test").apply {
        deleteRecursively()
        mkdirs()
    }
    private var server: BrowserCastServer? = null

    @After
    fun cleanup() {
        server?.stop()
        dir.deleteRecursively()
    }

    @Test
    fun `页面带一次性 token 与上传地址`() {
        startServer()
        val page = get(server!!.url("127.0.0.1"))
        assertEquals(200, page.first)
        assertTrue(page.second.contains("投到大屏"))
        assertTrue(page.second.contains("/upload"))
        assertTrue("地址里必须带 token，裸路径不能上传", page.second.contains("/w/"))
    }

    @Test
    fun `页面标题行显示实时设备名并转义`() {
        startServer(deviceName = "客厅电视")
        assertEquals(200, get(server!!.url("127.0.0.1")).first)
        assertTrue("标题行必须显示大屏名", get(server!!.url("127.0.0.1")).second.contains("<h1>客厅电视</h1>"))
        server?.stop()
        startServer(deviceName = "<img src=x>")
        val page = get(server!!.url("127.0.0.1")).second
        assertFalse("设备名未转义，注入面直接落到页面", page.contains("<img src=x>"))
        assertTrue(page.contains("&lt;img src=x&gt;"))
    }

    @Test
    fun `上传后文件落盘并回调`() {
        val uploaded = AtomicReference<BrowserCastServer.UploadedFile>()
        val latch = CountDownLatch(1)
        startServer { file ->
            uploaded.set(file)
            latch.countDown()
        }
        val body = ByteArray(1024) { (it % 97).toByte() }
        val response = upload(server!!.url("127.0.0.1"), "视频 1.mp4", "video/mp4", body)
        assertEquals(200, response)
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        val file = uploaded.get()!!
        assertTrue(file.file.isFile)
        assertEquals(body.size.toLong(), file.size)
        assertEquals("视频1.mp4", file.name)
        assertEquals("video/mp4", file.mime)
    }

    @Test
    fun `文件名穿越被清洗 不写出缓存目录`() {
        val uploaded = AtomicReference<BrowserCastServer.UploadedFile>()
        val latch = CountDownLatch(1)
        startServer { file ->
            uploaded.set(file)
            latch.countDown()
        }
        val response = upload(server!!.url("127.0.0.1"), "../../evil.mp4", "video/mp4", ByteArray(16))
        assertEquals(200, response)
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        val file = uploaded.get()!!
        assertEquals("evil.mp4", file.name)
        assertTrue(file.file.canonicalPath.startsWith(dir.canonicalPath))
    }

    @Test
    fun `错误 token 返回 404 且不回调`() {
        val latch = CountDownLatch(1)
        startServer { _ -> latch.countDown() }
        val port = server!!.port
        assertEquals(404, status("http://127.0.0.1:$port/w/deadbeef/"))
        assertEquals(404, status("http://127.0.0.1:$port/"))
        assertEquals(false, latch.await(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `超过上限直接拒绝 不产生文件`() {
        val latch = CountDownLatch(1)
        startServer(maxBytes = 1024) { _ -> latch.countDown() }
        val response = rawUpload(server!!.port, "big.mp4", "video/mp4", ByteArray(2048))
        assertEquals(413, response)
        assertEquals(false, latch.await(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `停止服务会清掉临时文件`() {
        startServer()
        val body = ByteArray(64)
        assertEquals(200, upload(server!!.url("127.0.0.1"), "a.mp4", "video/mp4", body))
        assertTrue(dir.listFiles().orEmpty().isNotEmpty())
        server!!.stop()
        server = null
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `再上传一次只保留最新一份`() {
        startServer()
        assertEquals(200, upload(server!!.url("127.0.0.1"), "a.mp4", "video/mp4", ByteArray(32)))
        assertEquals(1, dir.listFiles().orEmpty().size)
        assertEquals(200, upload(server!!.url("127.0.0.1"), "b.mp4", "video/mp4", ByteArray(48)))
        val remaining = dir.listFiles().orEmpty()
        assertEquals("旧文件必须清掉，否则每次上传都会多留一份", 1, remaining.size)
        assertTrue(remaining.first().name.endsWith("b.mp4"))
    }

    private fun startServer(
        maxBytes: Long = BrowserCastServer.DEFAULT_MAX_BYTES,
        deviceName: String = BrowserCastServer.DEFAULT_DEVICE_NAME,
        onUpload: (BrowserCastServer.UploadedFile) -> Unit = {},
    ) {
        server = BrowserCastServer(dir, maxBytes, onUpload, deviceName = { deviceName }).start()
    }

    private fun get(url: String): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        return try {
            Pair(connection.responseCode, connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun status(url: String): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        return try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun upload(baseUrl: String, name: String, mime: String, body: ByteArray): Int {
        val connection = (URL(baseUrl + "upload").openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_000
            readTimeout = 3_000
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("X-File-Name", URLEncoder.encode(name, "UTF-8"))
            setRequestProperty("Content-Type", mime)
        }
        return try {
            connection.outputStream.use { it.write(body) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    /** 超限请求要在服务端读 body 之前拒绝，用裸 socket 才能避免 HttpURLConnection 写失败干扰断言。 */
    private fun rawUpload(port: Int, name: String, mime: String, body: ByteArray): Int {
        // token 从服务端暴露的地址里取，测试不复制内部实现
        val token = server!!.url("127.0.0.1").substringAfter("/w/").substringBefore('/')
        Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
            val head = "POST /w/$token/upload HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "X-File-Name: ${URLEncoder.encode(name, "UTF-8")}\r\n" +
                "Content-Type: $mime\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().write(head.toByteArray(Charsets.US_ASCII))
            runCatching { socket.getOutputStream().write(body) }
            socket.getOutputStream().flush()
            val line = socket.getInputStream().bufferedReader().readLine() ?: return 0
            return line.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        }
    }
}
