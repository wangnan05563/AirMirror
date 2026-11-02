package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import io.github.wangnan05563.airmirror.receiver.ReceiverSession

/** 接收端：全屏 Surface + 状态浮层，自己广播并被发送端发现。 */
class ReceiverActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var status: TextView
    private var session: ReceiverSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        val surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        status = TextView(this).apply {
            text = "准备中…"
            textSize = 16f
            setBackgroundColor(0x99000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(32, 24, 32, 24)
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = 48 },
        )
        setContentView(root)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val name = "AirMirror-${Build.MODEL}".replace(Regex("[^A-Za-z0-9-]"), "-").take(30)
        val active = session ?: ReceiverSession(this) { text -> runOnUiThread { status.text = text } }
            .also { it.start(name) }
        session = active
        active.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // 只摘掉 Surface，会话继续收流；回到前台会重新挂载
        session?.detachSurface()
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        super.onDestroy()
    }
}
