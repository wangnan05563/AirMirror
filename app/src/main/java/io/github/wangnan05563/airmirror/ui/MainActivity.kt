package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 入口页：装完就能用，首次启动没有任何引导或账号流程（PRD M1「安装即用」+ 14.4 无账号）。
 * 手机当发送端，电视/盒子当接收端，两个入口平铺在一屏内。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Theme.BACKGROUND)
            val pad = Theme.dp(this@MainActivity, 24)
            setPadding(pad, Theme.dp(this@MainActivity, 56), pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "AirMirror"
            textSize = 32f
            setTextColor(Theme.TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "同一网络里，把画面投到另一块屏"
            textSize = Theme.PHONE_BODY_SP
            setTextColor(Theme.TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@MainActivity, 6), 0, Theme.dp(this@MainActivity, 32))
        })
        root.addView(Button(this).apply {
            text = "投到其他设备"
            setTextColor(Theme.ACCENT)
            setOnClickListener { startActivity(Intent(this@MainActivity, SenderActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "接收别人投来的画面"
            setTextColor(Theme.TEXT_PRIMARY)
            setOnClickListener { startActivity(Intent(this@MainActivity, ReceiverActivity::class.java)) }
        })
        root.addView(TextView(this).apply {
            text = "局域网直连 · 无广告 · 不需要账号"
            textSize = Theme.PHONE_BODY_SP
            setTextColor(Theme.OFFLINE)
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@MainActivity, 24), 0, 0)
        })
        setContentView(root)
    }
}
