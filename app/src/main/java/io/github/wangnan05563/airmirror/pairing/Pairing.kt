package io.github.wangnan05563.airmirror.pairing

/**
 * 一次会话需要的授权对象集合：设备身份、投屏码、可信列表与授权门禁。
 * 生命周期与界面一致，落盘只在授权变化时发生。
 */
class Pairing(
    private val store: DeviceStore,
    now: () -> Long = { System.currentTimeMillis() },
    autoAllowEnabled: Boolean = store.autoAllowEnabled(),
) {
    val trust = TrustRegistry(now = now, autoAllowMillis = if (autoAllowEnabled) DEFAULT_AUTO_ALLOW else 0L)
    val code = CastCode(now = now)
    val pin = PinCode(load = { store.pinState() }, save = { store.savePinState(it) }, now = now)
    val gate = AuthGate(
        trust = trust,
        code = code,
        pin = pin,
        accessMode = { store.accessMode() },
    )

    /** PRD 8.9 防滥用：与被拒次数挂钩的连接频率闸门，与投屏码错误限流各管一段。 */
    val throttle = ConnectThrottle(now = now)
    val deviceId: String = store.deviceId()

    init {
        when (trust.restore(store.trustedSnapshot())) {
            // 迁移成功或失败都立刻落一份 v2 快照：成功把旧行转成带版本头的新格式，
            // 失败则留下空表，后续一律走“每次确认”，不会每次启动重复迁移一份坏数据。
            TrustRegistry.RestoreResult.MIGRATED,
            TrustRegistry.RestoreResult.FAILED -> store.saveTrustedSnapshot(trust.snapshot())
            else -> Unit
        }
    }

    fun persistTrusted() {
        store.saveTrustedSnapshot(trust.snapshot())
    }

    /** 改名并同步落盘；未知设备、空名字或超长名字返回 false，不产生半截状态。 */
    fun renameTrusted(fingerprint: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_TRUSTED_NAME) return false
        if (!trust.rename(fingerprint, trimmed)) return false
        persistTrusted()
        return true
    }

    /** 撤销并同步落盘。返回 true 表示撤销时该设备正在投屏，调用方应立刻结束会话。 */
    fun revokeTrusted(fingerprint: String): Boolean {
        val wasActive = trust.revoke(fingerprint)
        persistTrusted()
        return wasActive
    }

    /** “清除信任数据”：内存与落盘一起清空，重启后不会把旧列表读回来。 */
    fun clearTrusted(): Boolean {
        val wasActive = trust.clear()
        persistTrusted()
        return wasActive
    }

    /** 待连接页二维码：设备标识 + 随码轮换的一次性令牌 + 同网直连所需的地址。 */
    fun qrPayload(host: String, port: Int, name: String): String =
        PairingLink.build(PairingLink.Link(deviceId, name, code.token(), host, port))

    private companion object {
        const val DEFAULT_AUTO_ALLOW = 5_000L
        /** 电视端展示与落盘都按同一上限，避免过长名字把设置页一行撑爆。 */
        const val MAX_TRUSTED_NAME = 24
    }
}
