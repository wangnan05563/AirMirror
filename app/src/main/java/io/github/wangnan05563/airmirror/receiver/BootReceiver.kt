package io.github.wangnan05563.airmirror.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.wangnan05563.airmirror.pairing.DeviceStore
import io.github.wangnan05563.airmirror.ui.ReceiverActivity

/**
 * 开机自启（PRD FS-13）：用户开关默认开启。系统拦截 Activity 拉起时
 * 至少保留前台服务常驻，待连接页底部也常驻手动打开提示。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!DeviceStore(context).autoStartEnabled()) {
            Log.i(TAG, "auto start disabled, skip $action")
            return
        }
        val started = runCatching { ReceiverService.start(context) }
            .onFailure { Log.w(TAG, "foreground service not started: ${it.message}") }
            .isSuccess
        val opened = runCatching {
            context.startActivity(
                Intent(context, ReceiverActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }.onFailure { Log.w(TAG, "receiver page not opened: ${it.message}") }.isSuccess
        Log.i(TAG, "auto start $action service=$started page=$opened")
    }

    private companion object {
        const val TAG = "AirMirrorBoot"
    }
}