package io.github.wangnan05563.airmirror.protocol

/**
 * PRD §04 P0 边界 + §13.1 平台限制的唯一出口：界面能说的协议只从这里取。
 *
 * Miracast 已从 P0 承诺中移除（Android 8.0+ 第三方 APK 无法注册接收端）；
 * AirPlay / Cast 接收端尚未落地，补齐前同样按「暂不支持」展示，
 * 避免出现「待连接页写着 iPhone 屏幕镜像、实际连不上」的假能力。
 *
 * Browser 不是设备广播出来的协议，而是接收端内置的网页入口（PRD §5.9）：
 * 它同样属于「本机真能做到」的能力，所以进矩阵，但绝不会出现在发现列表里。
 */
object ProtocolBoundary {

    enum class Protocol(val wireName: String, val label: String) {
        AMRC("AMRC", "AirMirror 镜像"),
        DLNA("DLNA", "DLNA 推送"),
        BROWSER("Browser", "浏览器投屏"),
        AIRPLAY("AirPlay", "AirPlay 镜像"),
        CAST("Cast", "Cast 推送"),
        MIRACAST("Miracast", "Miracast 镜像"),
        HUAWEI_CAST_PLUS("Cast+", "华为 Cast+"),
    }

    data class Capability(
        val protocol: Protocol?,
        val supported: Boolean,
        val headline: String,
        val nextStep: String,
    ) {
        fun text(): String = "$headline，$nextStep"
    }

    /** 已实现的接收能力；AirPlay/Cast 补齐后把对应项加进来，文案与角标随之一致 */
    private val implemented = setOf(Protocol.AMRC, Protocol.DLNA, Protocol.BROWSER)

    private fun of(name: String): Protocol? = Protocol.values().firstOrNull { it.wireName.equals(name, true) }

    fun supports(name: String): Boolean = of(name)?.let { it in implemented } ?: false

    /** 同一台设备可能同时广播多种协议，取其中第一个我们真能用的；没有则返回 null */
    fun bestSupported(protocols: List<String>): String? = protocols.firstOrNull { supports(it) }

    fun describe(name: String): Capability = when (val protocol = of(name)) {
        Protocol.AMRC -> Capability(protocol, true, "AirMirror 镜像", "在本机打开 AirMirror，扫码或输入投屏码即可。")
        Protocol.DLNA -> Capability(protocol, true, "DLNA 推送", "在视频 App 里点「投屏」选本机即可。")
        Protocol.BROWSER -> Capability(
            protocol, true, "浏览器投屏",
            "用手机或电脑浏览器打开大屏上的地址，选择文件即可投。",
        )
        Protocol.MIRACAST -> Capability(
            protocol, false, "不支持 Miracast",
            "普通安装的 App 收不了 Miracast，请改用电视自带的无线显示，或扫码投屏。",
        )
        Protocol.HUAWEI_CAST_PLUS -> Capability(protocol, false, "暂不支持华为 Cast+", "请改用 DLNA 推送，或打开 AirMirror 扫码镜像。")
        Protocol.AIRPLAY -> Capability(protocol, false, "iPhone 镜像暂未开放", "当前可先用 DLNA 推送视频，屏幕镜像会在后续版本提供。")
        Protocol.CAST -> Capability(protocol, false, "Cast 接收暂未开放", "请改用 DLNA 推送，或在手机打开 AirMirror 镜像。")
        null -> Capability(null, false, "这个协议还不认识", "请改用投屏码连接电视上的 AirMirror。")
    }

    /**
     * 接收端待连接页只列已实现的入口，不写做不到的。
     * 电脑端发送端（M7）尚未实现，这里不能写「电脑」，否则用户会去找一个不存在的客户端。
     */
    fun receiverMirrorLine(): String = "安卓：打开 AirMirror 扫码或输投屏码镜像"

    fun receiverPushLine(): String = "视频 App：DLNA 推送直接投到本机"

    fun receiverUnsupportedLine(): String = "不支持 Miracast；iPhone 屏幕镜像暂未开放，可先推送视频"

    /** 发送端列表角标：已实现的协议按能力分档，未实现的统一「不支持」 */
    fun badgeFor(name: String): String = when (of(name)) {
        Protocol.AMRC -> "镜像 · 私有通道"
        Protocol.DLNA -> "DLNA 推送"
        Protocol.BROWSER -> "浏览器上传"
        else -> "不支持"
    }
}
