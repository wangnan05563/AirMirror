package io.github.wangnan05563.airmirror.pairing

import android.content.Context
import android.content.SharedPreferences
import io.github.wangnan05563.airmirror.device.DisplayMode
import io.github.wangnan05563.airmirror.device.QualityTier
import java.util.UUID

/**
 * 授权状态的落盘：设备标识、可信列表快照与「5 秒无操作自动允许」开关。
 * 只存这三样，画面、文件名与 URL 一律不留（PRD 14.4「不保存画面/文件名/URL」）。
 */
class DeviceStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 接收端与发送端各自的长期身份，装好后第一次生成，之后重装才变。 */
    fun deviceId(): String {
        val stored = prefs.getString(KEY_ID, null)
        if (!stored.isNullOrEmpty()) return stored
        val fresh = "am-" + UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString(KEY_ID, fresh).apply()
        return fresh
    }

    fun trustedSnapshot(): String = prefs.getString(KEY_TRUSTED, "").orEmpty()

    fun saveTrustedSnapshot(snapshot: String) {
        prefs.edit().putString(KEY_TRUSTED, snapshot).apply()
    }

    fun deviceName(fallback: String): String = prefs.getString(KEY_NAME, null) ?: fallback

    fun saveDeviceName(name: String) {
        prefs.edit().putString(KEY_NAME, name).apply()
    }

    /** 首次连接 5 秒无操作默认允许，默认开启，可在设置里关掉。 */
    fun autoAllowEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_ALLOW, true)

    fun saveAutoAllowEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_ALLOW, enabled).apply()
    }

    /** 显示模式按设备持久化，重启后保持（PRD FS-08 验收「设置持久」）。 */
    fun displayMode(): DisplayMode =
        DisplayMode.parse(prefs.getString(KEY_DISPLAY_MODE, null))

    fun saveDisplayMode(mode: DisplayMode) {
        prefs.edit().putString(KEY_DISPLAY_MODE, mode.name).apply()
    }

    fun followRotation(): Boolean = prefs.getBoolean(KEY_FOLLOW_ROTATION, true)

    fun saveFollowRotation(follow: Boolean) {
        prefs.edit().putBoolean(KEY_FOLLOW_ROTATION, follow).apply()
    }

    fun portraitLabel(): Boolean = prefs.getBoolean(KEY_PORTRAIT_LABEL, true)

    fun savePortraitLabel(show: Boolean) {
        prefs.edit().putBoolean(KEY_PORTRAIT_LABEL, show).apply()
    }

    /** 开机自启默认开启（PRD FS-13）；系统拦截时降级为「从应用列表手动打开」。 */
    fun autoStartEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_START, true)

    fun saveAutoStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    /** 镜像是否带声音，默认开启（PRD FS-01 参数「音频：开启/关闭（默认开启）」）。 */
    fun mirrorAudioEnabled(): Boolean = prefs.getBoolean(KEY_MIRROR_AUDIO, true)

    fun saveMirrorAudioEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MIRROR_AUDIO, enabled).apply()
    }

    /**
     * 画质档位锁定（PRD FS-05「手动锁定」）：null 表示自动升降档。
     * 认不出的落盘值一律按自动处理——宁可自动降档，也不要锁在一个已被四道门判不可达的档上。
     */
    fun qualityLock(): QualityTier? {
        val stored = prefs.getString(KEY_QUALITY_LOCK, null)
        if (stored.isNullOrEmpty() || stored == AUTO) return null
        return QualityTier.entries.firstOrNull { it.name == stored && it.isUsable }
    }

    fun saveQualityLock(tier: QualityTier?) {
        prefs.edit().putString(KEY_QUALITY_LOCK, tier?.name ?: AUTO).apply()
    }

    /** 画面标识（PRD FS-05「默认不显示画质信息；用户可开启角标」），默认关。 */
    fun qualityBadgeEnabled(): Boolean = prefs.getBoolean(KEY_QUALITY_BADGE, false)

    fun saveQualityBadgeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QUALITY_BADGE, enabled).apply()
    }

    private companion object {
        const val FILE = "airmirror-pairing"
        const val KEY_ID = "device_id"
        const val KEY_TRUSTED = "trusted"
        const val KEY_NAME = "device_name"
        const val KEY_AUTO_ALLOW = "auto_allow"
        const val KEY_DISPLAY_MODE = "display_mode"
        const val KEY_FOLLOW_ROTATION = "follow_rotation"
        const val KEY_PORTRAIT_LABEL = "portrait_label"
        const val KEY_AUTO_START = "auto_start"
        const val KEY_MIRROR_AUDIO = "mirror_audio"
        const val KEY_QUALITY_LOCK = "quality_lock"
        const val KEY_QUALITY_BADGE = "quality_badge"
        const val AUTO = "auto"
    }
}
