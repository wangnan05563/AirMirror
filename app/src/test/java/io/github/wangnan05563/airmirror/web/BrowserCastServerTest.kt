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
import org.junit.Assert.assertNull
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
        val name = AtomicReference("客厅电视")
        server = BrowserCastServer(dir, BrowserCastServer.DEFAULT_MAX_BYTES, {}, deviceName = { name.get() }).start()
        assertEquals(200, get(server!!.url("127.0.0.1")).first)
        assertTrue("标题行必须显示大屏名", get(server!!.url("127.0.0.1")).second.contains("<h1>客厅电视</h1>"))
        name.set("<img src=x>")
        val page = get(server!!.url("127.0.0.1")).second
        assertFalse("设备名未转义，注入面直接落到页面", page.contains("<img src=x>"))
        assertTrue(page.contains("&lt;img src=x&gt;"))
    }

    @Test
    fun `访客页文案不含词汇表禁用词`() {
        startServer()
        val page = get(server!!.url("127.0.0.1")).second
        listOf("投射", "同屏", "共享").forEach { word ->
            assertFalse("访客页出现禁用词「$word」", page.contains(word))
        }
        assertTrue("仍要说明不可用的原因", page.contains("WebRTC"))
        assertTrue("仍要给出当前可用的下一步", page.contains("文件上传"))
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

    @Test
    fun `连上不发数据的空连接会被超时关闭`() {
        // 每个连接一条线程；没有读超时的话，谁能连上谁就能永久占住一条线程
        server = BrowserCastServer(
            dir,
            BrowserCastServer.DEFAULT_MAX_BYTES,
            {},
            headTimeoutMs = 200,
        ).start()
        Socket("127.0.0.1", server!!.port).use { socket ->
            socket.soTimeout = 3_000
            val started = System.currentTimeMillis()
            // 一个字节都不发：服务端到点必须自己关掉这条连接，read 读到 EOF
            val eof = socket.getInputStream().read()
            val elapsed = System.currentTimeMillis() - started
            assertEquals("空连接必须被服务端主动关闭", -1, eof)
            assertTrue("应在读超时附近关闭，实际 ${elapsed}ms", elapsed < 2_000)
        }
    }

    @Test
    fun `并发连接超过上限会被当场断开`() {
        // 只加读超时挡不住「开几千个连接、每个慢慢发」：线程与栈内存会先被占满。
        // 这里用 10 秒读超时，确保被拒的第 N+1 条不是靠超时退出，而是当场被服务端关闭。
        server = BrowserCastServer(
            dir,
            BrowserCastServer.DEFAULT_MAX_BYTES,
            {},
            headTimeoutMs = 10_000,
        ).start()
        val port = server!!.port
        val held = ArrayList<Socket>()
        try {
            repeat(BrowserCastServer.MAX_CLIENTS) {
                val socket = Socket(InetAddress.getByName("127.0.0.1"), port)
                socket.soTimeout = 5_000
                // 只发半个请求头：服务端已 accept 并阻塞在读头，这条连接确实被占住
                socket.getOutputStream().write("GET /w/".toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()
                held.add(socket)
            }
            // 给 accept 线程把 8 条连接都登记进 clients 的时间（本地环回是微秒级）
            Thread.sleep(800)
            Socket(InetAddress.getByName("127.0.0.1"), port).use { extra ->
                extra.soTimeout = 3_000
                assertEquals("超过上限的连接必须当场关闭", -1, extra.getInputStream().read())
            }
        } finally {
            held.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `正常请求不会被读超时误伤`() {
        server = BrowserCastServer(
            dir,
            BrowserCastServer.DEFAULT_MAX_BYTES,
            {},
            headTimeoutMs = 200,
        ).start()
        // 超时只约束「一直收不到字节」；正常浏览器立刻发完请求头，200ms 足够
        val page = get(server!!.url("127.0.0.1"))
        assertEquals(200, page.first)
        assertTrue(page.second.contains("投到大屏"))
    }

    @Test
    fun `上传中途停止服务会断开连接并清掉半截文件`() {
        val uploaded = AtomicReference<BrowserCastServer.UploadedFile>()
        startServer { uploaded.set(it) }
        val token = server!!.url("127.0.0.1").substringAfter("/w/").substringBefore('/')
        val socket = Socket(InetAddress.getByName("127.0.0.1"), server!!.port)
        socket.soTimeout = 3_000
        // 声明 100KB 只发 8 字节：服务端会停在读 body，只有 stop 关连接才能让它退出
        val head = "POST /w/$token/upload HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "Content-Length: 102400\r\n" +
            "X-File-Name: partial.mp4\r\n" +
            "Content-Type: video/mp4\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().write(head.toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().write(ByteArray(8))
        socket.getOutputStream().flush()
        // 留出进入 body 读取的时间：此刻半截文件已经落盘
        Thread.sleep(300)
        val started = System.currentTimeMillis()
        server!!.stop()
        val closed = runCatching { socket.getInputStream().read() }.getOrDefault(-1)
        val elapsed = System.currentTimeMillis() - started
        socket.close()
        assertEquals("停止后应当场断开，而不是等 60 秒 body 超时", -1, closed)
        assertTrue("断开耗时 ${elapsed}ms", elapsed < 2_000)
        assertNull("上传未完成不应触发回调", uploaded.get())
        // 半截文件由上传线程在异常分支里删除，给它一点时间落定
        var remaining = dir.listFiles().orEmpty()
        val deadline = System.currentTimeMillis() + 2_000
        while (remaining.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            remaining = dir.listFiles().orEmpty()
        }
        assertTrue("停止后不应留下半截文件：${remaining.joinToString { it.name }}", remaining.isEmpty())
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
