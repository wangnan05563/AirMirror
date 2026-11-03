package io.github.wangnan05563.airmirror.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * 接收端有没有能出声的地方（PRD FR-09 异常「未检测到扬声器」）。
 *
 * 判不出来时按「有」处理：老系统不给设备清单，不能因为查不到就把声音关掉。
 */
object AudioDevices {

    fun hasOutput(context: Context): Boolean {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val devices = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull() ?: return true
        if (devices.isEmpty()) return false
        // 只剩一个「耳机插着但没人听」之类的输出也不当有：至少要有一台能外放或已插入的输出
        return devices.any { it.type != AudioDeviceInfo.TYPE_UNKNOWN }
    }
}
