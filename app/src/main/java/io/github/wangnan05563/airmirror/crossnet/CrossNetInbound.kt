package io.github.wangnan05563.airmirror.crossnet

import kotlin.concurrent.thread

/**
 * 接收端跨网待命循环（PRD FS-04 第 3 步接收侧）。
 *
 * 接收端不能等用户按了什么才开始：发送端随时可能拿着大屏上的 6 位码来匹配。
 * 所以只要配置了信令，大屏就持续登记当前码并在建连失败后重新来一轮。
 *
 * 为什么每轮都重新登记：投屏码每 60 秒轮换，旧会话的码哈希已经作废；
 * 沿用旧会话只会让发送端匹配到一个大屏已经放弃的 id。重新登记多花一次请求，换来语义简单。
 *
 * 一次成功交付后就停：隧道交给 [onLink]，由会话层按正常连接处理；
 * 但它在下一次循环会重新登记，因为用户可能结束投屏后再投一次。
 */
class CrossNetInbound(
    private val connect: (String) -> CrossNetConnector.Outcome,
    private val credential: () -> String,
    private val onLink: (CrossNetConnector.Outcome) -> Unit,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val idleMs: Long = 500,
    private val retryMs: Long = 1_000,
) {
    @Volatile
    private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "airmirror-crossnet-in", isDaemon = true) {
            while (running) {
                runCatching { loopOnce() }
            }
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    /** 返回是否在本轮交付了一条可用链路。异常不外抛：跨网失败不该把接收端待命循环打断。 */
    fun loopOnce(): Boolean {
        val code = credential()
        if (code.isBlank()) {
            // 码被锁定时不出新码：此时登记任何哈希都会让发送端匹配到一个「大屏根本不认」的会话
            sleep(idleMs)
            return false
        }
        val outcome = runCatching { connect(code) }.getOrNull()
        val link = outcome?.link
        if (outcome != null && outcome.connected && link != null) {
            runCatching { onLink(outcome) }
            // 交付后留一个间隔：不让用户刚结束投屏就被立刻重新拉进匹配
            sleep(retryMs)
            return true
        }
        sleep(retryMs)
        return false
    }
}
