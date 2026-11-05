package io.github.wangnan05563.airmirror.crossnet

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 跨网链路交接（发送端 UI → 前台采集服务）。
 *
 * 为什么不用 Intent 直接传隧道：`UdpTunnel` 持有线程与 socket，不是 Parcelable；
 * 强行序列化只能把地址信息传过去，却传不过已建立的 NAT 映射，等于重新打洞。
 * 发送端与采集服务在同一进程，用一次性 token 在进程内交接即可，Intent 只带 token。
 *
 * token 在 [take] 时立即移除：一条隧道只能交给一个会话，重复交付会出现两个读者抢同一队列。
 * UI 在失败/取消时用 [discard] 关掉链路，避免没有服务接手时泄漏 socket 与线程。
 */
object CrossNetLinkRegistry {
    private val pending = ConcurrentHashMap<String, CrossNetConnector.Outcome>()

    fun put(outcome: CrossNetConnector.Outcome): String {
        require(outcome.link != null) { "only connected outcomes can be handed over" }
        val token = UUID.randomUUID().toString()
        pending[token] = outcome
        return token
    }

    fun take(token: String): CrossNetConnector.Outcome? = pending.remove(token)

    fun discard(token: String) {
        pending.remove(token)?.link?.let { runCatching { it.close() } }
    }

    /** 仅供单测观察，避免测试里留下跨用例的链路。 */
    fun size(): Int = pending.size
}
