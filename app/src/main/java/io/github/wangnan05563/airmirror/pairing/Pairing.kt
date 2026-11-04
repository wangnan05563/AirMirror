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
    val gate = AuthGate(trust, code)

    /** PRD 8.9 防滥用：与被拒次数挂钩的连接频率闸门，与投屏码错误限流各管一段。 */
    val throttle = ConnectThrottle(now = now)
    val deviceId: String = store.deviceId()

    init {
        trust.restore(store.trustedSnapshot())
    }

    fun persistTrusted() {
        store.saveTrustedSnapshot(trust.snapshot())
    }

    /** 待连接页二维码：设备标识 + 随码轮换的一次性令牌 + 同网直连所需的地址。 */
    fun qrPayload(host: String, port: Int, name: String): String =
        PairingLink.build(PairingLink.Link(deviceId, name, code.token(), host, port))

    private companion object {
        const val DEFAULT_AUTO_ALLOW = 5_000L
    }
}
