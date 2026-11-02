package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 120, 64, 64)
        }
        root.addView(TextView(this).apply {
            text = "AirMirror"
            textSize = 28f
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "M0 私有通道联调"
            textSize = 16f
            setPadding(0, 0, 0, 32)
        })
        root.addView(Button(this).apply {
            text = "作为发送端"
            setOnClickListener { startActivity(Intent(this@MainActivity, SenderActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "作为接收端"
            setOnClickListener { startActivity(Intent(this@MainActivity, ReceiverActivity::class.java)) }
        })
        setContentView(root)
    }
}
