package io.github.wangnan05563.airmirror.pairing

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.github.wangnan05563.airmirror.device.DisplayMode
import io.github.wangnan05563.airmirror.device.QualityTier
import io.github.wangnan05563.airmirror.util.Onboarding
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

    /** 最近连过的接收端（设备名 + 局域网地址 + 端口），供 FS-07 缓存展示与直连降级探测使用。 */
    fun recentTargets(): String = prefs.getString(KEY_RECENT, "").orEmpty()

    fun saveRecentTargets(snapshot: String) {
        prefs.edit().putString(KEY_RECENT, snapshot).apply()
    }

    fun deviceName(fallback: String): String = prefs.getString(KEY_NAME, null) ?: fallback

    fun saveDeviceName(name: String) {
        prefs.edit().putString(KEY_NAME, name).apply()
    }

    /**
     * 首次启动标记（PRD §1.2 三步引导）：未跑过引导时为 true，引导结束（含全部跳过）后置 false。
     * 仅控制是否弹引导页，不影响任何功能开关。
     */
    fun firstLaunch(): Boolean = prefs.getBoolean(KEY_FIRST_LAUNCH, true)

    fun setFirstLaunch(done: Boolean) {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, done).apply()
    }

    /**
     * 确保落盘里有一份默认设备名：没有时才按 PRD §1.2 生成「机型 + 随机两位」（机型为空/杂乱时回落「电视 + 随机两位」）
     * 并落盘。随机后缀只在第一次生成时取一次，落盘后稳定，避免每次进程启动都换名。
     * 在 [io.github.wangnan05563.airmirror.receiver.ReceiverHost.get] 里调用，
     * 因此无论接收端从 Activity 还是开机自启 Service 进入，默认名都会先就位。
     */
    fun ensureDefaultDeviceName(): String {
        val existing = prefs.getString(KEY_NAME, null)
        if (!existing.isNullOrEmpty()) return existing
        val fresh = Onboarding.defaultDeviceName(Build.MODEL)
        prefs.edit().putString(KEY_NAME, fresh).apply()
        return fresh
    }

    /** 首次连接自动允许：仅对递过有效凭据的会话有意义；无凭据会话一律由大屏显式确认（T04496 裁决），故该开关当前不影响匿名发现的确认方式。 */
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

    /**
     * 是否已经向系统为这项权限发起过申请。必须落盘：`shouldShowRequestPermissionRationale` 在
     * 「从未问过」与「已被不再询问」两种情况下都返回 false，只有这份记录能把它们分开；
     * 放在内存里会在进程重启后失效，于是永久拒绝的用户又会被弹一次假装还能申请的框。
     */
    fun permissionAsked(key: String): Boolean = prefs.getBoolean(KEY_PERMISSION_ASKED + key, false)

    fun savePermissionAsked(key: String) {
        prefs.edit().putBoolean(KEY_PERMISSION_ASKED + key, true).apply()
    }

    private companion object {
        const val FILE = "airmirror-pairing"
        const val KEY_ID = "device_id"
        const val KEY_TRUSTED = "trusted"
        const val KEY_RECENT = "recent_targets"
        const val KEY_NAME = "device_name"
        const val KEY_FIRST_LAUNCH = "first_launch"
        const val KEY_AUTO_ALLOW = "auto_allow"
        const val KEY_DISPLAY_MODE = "display_mode"
        const val KEY_FOLLOW_ROTATION = "follow_rotation"
        const val KEY_PORTRAIT_LABEL = "portrait_label"
        const val KEY_AUTO_START = "auto_start"
        const val KEY_MIRROR_AUDIO = "mirror_audio"
        const val KEY_QUALITY_LOCK = "quality_lock"
        const val KEY_QUALITY_BADGE = "quality_badge"
        const val KEY_PERMISSION_ASKED = "permission_asked_"
        const val AUTO = "auto"
    }
}
