package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.wangnan05563.airmirror.net.ServiceBrowser
import io.github.wangnan05563.airmirror.sender.CaptureService

/** 发送端：发现接收端 → 取得屏幕录制授权 → 交给 CaptureService 推流。 */
class SenderActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private val found = LinkedHashMap<String, ServiceBrowser.DiscoveredService>()
    private var browser: ServiceBrowser? = null
    private var pending: ServiceBrowser.DiscoveredService? = null

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
        }
        status = TextView(this).apply { text = "正在发现接收端…"; textSize = 16f }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(status)
        root.addView(list)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        startBrowsing()

        // 联调入口：带 host/port 启动时跳过发现直接连（debug 包才可从 adb 拉起本 Activity）
        val directHost = intent.getStringExtra(EXTRA_TARGET_HOST)
        val directPort = intent.getIntExtra(EXTRA_TARGET_PORT, 0)
        if (!directHost.isNullOrEmpty() && directPort > 0) {
            requestCapture(ServiceBrowser.DiscoveredService("direct", directHost, directPort))
        }
    }

    private fun startBrowsing() {
        browser = ServiceBrowser(
            this,
            onFound = { service ->
                runOnUiThread {
                    if (found.put(service.name, service) == null) addButton(service)
                    status.text = "发现 ${found.size} 个接收端，点选开始投屏"
                }
            },
            onLost = { name ->
                runOnUiThread {
                    found.remove(name)
                    rebuildList()
                    status.text = if (found.isEmpty()) "正在发现接收端…" else "发现 ${found.size} 个接收端"
                }
            },
        ).also { it.start() }
    }

    private fun addButton(service: ServiceBrowser.DiscoveredService) {
        list.addView(Button(this).apply {
            text = "${service.name}  (${service.host}:${service.port})"
            setOnClickListener { requestCapture(service) }
        })
    }

    private fun rebuildList() {
        list.removeAllViews()
        found.values.forEach { addButton(it) }
    }

    private fun requestCapture(service: ServiceBrowser.DiscoveredService) {
        pending = service
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Activity result API kept dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        val target = pending ?: return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "未获得屏幕录制授权", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_HOST, target.host)
            putExtra(CaptureService.EXTRA_PORT, target.port)
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        status.text = "正在投屏到 ${target.name}"
    }

    override fun onDestroy() {
        browser?.stop()
        // 发送端离开界面即停止采集，避免后台持续录屏
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CAPTURE = 1
        const val EXTRA_TARGET_HOST = "io.github.wangnan05563.airmirror.TARGET_HOST"
        const val EXTRA_TARGET_PORT = "io.github.wangnan05563.airmirror.TARGET_PORT"
    }
}
