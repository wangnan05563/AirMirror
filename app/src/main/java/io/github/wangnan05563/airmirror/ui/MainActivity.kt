package io.github.wangnan05563.airmirror.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.wangnan05563.airmirror.privacy.Diagnostics

/**
 * 入口页：装完就能用，首次启动没有任何引导或账号流程（PRD M1「安装即用」+ 14.4 无账号）。
 * 手机当发送端，电视/盒子当接收端，两个入口平铺在一屏内。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diagnostics(this).install()
        // PRD §11.5：TV 上按 3 米观看距离取字号，手机端仍用 14sp 正文
        val isTv = TvFocus.isTvMode(this)
        val bodySp = FocusPolicy.bodySp(isTv)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Theme.BACKGROUND)
            val pad = Theme.dp(this@MainActivity, Theme.SPACE_XL_DP)
            setPadding(pad, Theme.dp(this@MainActivity, 56), pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "AirMirror"
            textSize = FocusPolicy.titleSp(isTv)
            setTextColor(Theme.TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "同一网络里，把画面投到另一块屏"
            textSize = bodySp
            setTextColor(Theme.TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@MainActivity, 6), 0, Theme.dp(this@MainActivity, Theme.SPACE_XXL_DP))
        })
        root.addView(Button(this).apply {
            text = "投到其他设备"
            setTextColor(Theme.ACCENT)
            setOnClickListener { startActivity(Intent(this@MainActivity, SenderActivity::class.java)) }
            A11y.describe(this, A11yCopy.mainSend())
        })
        root.addView(Button(this).apply {
            text = "接收别人投来的画面"
            setTextColor(Theme.TEXT_PRIMARY)
            setOnClickListener { startActivity(Intent(this@MainActivity, ReceiverActivity::class.java)) }
            A11y.describe(this, A11yCopy.mainReceive())
        })
        root.addView(TextView(this).apply {
            text = "局域网直连 · 无广告 · 不需要账号"
            textSize = bodySp
            setTextColor(StatusColors.offlineText())
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@MainActivity, Theme.SPACE_XL_DP), 0, 0)
        })
        // PRD FS-11 首次启动：一句话数据边界说明，不用长文弹窗；「查看完整说明」按需展开
        root.addView(TextView(this).apply {
            text = "内容不出门，数据不落地 · 画面、文件名、地址都不保存"
            textSize = bodySp
            setTextColor(StatusColors.offlineText())
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@MainActivity, Theme.SPACE_SM_DP), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "查看完整说明"
            textSize = bodySp
            setTextColor(Theme.ACCENT)
            gravity = Gravity.CENTER
            setPadding(0, Theme.dp(this@MainActivity, Theme.SPACE_MD_DP), 0, 0)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("数据边界")
                    .setMessage(PRIVACY_NOTE)
                    .setPositiveButton("知道了", null)
                    .show()
            }
            A11y.describe(this, A11yCopy.mainPrivacy())
        })
        setContentView(root)
        // 全站可点控件统一接遥控器焦点（含「查看完整说明」这类 TextView 入口）
        TvFocus.applyTree(root)
    }

    private companion object {
        const val PRIVACY_NOTE =
            "投屏内容只在本次会话内使用，不保存画面、文件名与地址。\n\n" +
                "崩溃日志默认关闭；开启后仅在本机记录崩溃堆栈与设备型号，不含画面、文件名和地址，不上传，7 天后自动清除。\n\n" +
                "设备标识与已授权设备只存在本机，卸载即由系统清除，且不允许备份导出。\n\n" +
                "不要求登录，不采集手机号、位置与通讯录。"
    }
}
