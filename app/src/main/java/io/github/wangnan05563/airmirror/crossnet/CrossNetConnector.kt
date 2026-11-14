package io.github.wangnan05563.airmirror.crossnet

import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * 跨网建连编排：信令匹配 → 候选交换 → UDP 打洞 → 直连隧道（PRD FS-04 第 3～5 步）。
 *
 * 全程阻塞，调用方必须放在后台线程（Android 主线程不允许网络 IO）。
 * 成功时返回一条已经启动的 [UdpTunnel] 与它的对端，调用方拿到后应把它交给
 * `StreamConnection` 完成握手与加密协商——隧道只负责「字节有序可靠」，
 * 身份校验与端到端加密仍由既有协议层负责，不在这一层另造一套。
 *
 * 失败一律带确定原因与可执行替代路径；Q-4 裁定的「不自建中继」体现在：
 * 打洞失败只回「改用同一网络/手机热点」，不会出现任何中继开关。
 */
class CrossNetConnector(
    private val role: CrossNetProtocol.Role,
    private val client: SignalingClient?,
    private val now: () -> Long = System::currentTimeMillis,
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
    private val collect: (DatagramSocket) -> List<CrossNetProtocol.Candidate> = { socket ->
        CandidateCollector.collect(socket)
    },
    private val timeouts: Timeouts = Timeouts(),
) {
    data class Timeouts(
        val matchMs: Long = 15_000,
        val candidatesMs: Long = 15_000,
        val punchMs: Long = 15_000,
        val punchIntervalMs: Long = 500,
    )

    data class Outcome(
        val state: CrossNetState,
        val prompt: String,
        val failure: FailureReason?,
        val sessionId: String? = null,
        val link: UdpTunnel? = null,
        val peer: InetSocketAddress? = null,
        /**
         * 这条隧道建立时两端共用的投屏码。握手必须认它，而不是只看「此刻屏幕上是什么码」：
         * 跨网建连后面还隔着录屏授权，等用户点完，码可能已经轮换过一轮以上，
         * 而打洞期的 HMAC 探针已经用这个码互证过两端身份，凭据本身没有失效。
         */
        val credential: String? = null,
    ) {
        val connected: Boolean get() = link != null
    }

    /**
     * 发送端：投屏码校验通过、且局域网没找到同一台设备时走这里。
     * [sameSubnet] 为 true 时不上信令，直接返回「已局域网直连」，满足「同网不触云」。
     */
    fun connectSender(credential: String, sameSubnet: Boolean = false): Outcome =
        connect(credential, sameSubnet)

    /** 接收端：登记本机当前可受理的码，等发送端来匹配。 */
    fun connectReceiver(credential: String): Outcome = connect(credential, sameSubnet = false)

    private fun connect(credential: String, sameSubnet: Boolean): Outcome {
        require(credential.isNotBlank()) { "credential is required for cross-net connect" }
        val session = CrossNetSession(timeouts.matchMs, timeouts.punchMs)
        session.onCodeAccepted(now(), sameSubnet)
        if (session.state == CrossNetState.DIRECT) {
            return Outcome(session.state, session.prompt, null)
        }
        val signaling = client
        if (signaling == null) {
            session.onFailure(FailureReason.SIGNALING_DISABLED)
            return Outcome(session.state, session.prompt, session.failure)
        }

        val codeHash = CrossNetProtocol.codeHash(credential)
        var sessionId = ""
        // 状态机的 onPunchFailed 首次会把状态打回 MATCHING 表示「回第 3 步重试一次」。
        // 这里必须真的再走一轮匹配：只在文案里写「正在重试」而已经收手，用户会盯着那行字
        // 等一件不会发生的事，而大屏那边还在等这一端的候选。
        while (true) {
            sessionId = try {
                matchSession(signaling, codeHash)
            } catch (e: SignalingException) {
                session.onFailure(e.reason)
                return Outcome(session.state, session.prompt, session.failure, sessionId.ifEmpty { null })
            }
            session.onMatched(now())
            val outcome = punchAndConnect(session, signaling, sessionId, credential) ?: continue
            return outcome
        }
    }

    /** 一次匹配：发送端去认领接收端登记的码，接收端开一个会话等发送端来认领。 */
    private fun matchSession(signaling: SignalingClient, codeHash: ByteArray): String =
        if (role == CrossNetProtocol.Role.SENDER) {
            signaling.matchSender(codeHash, timeouts.matchMs)
        } else {
            signaling.openSession(codeHash, timeouts.matchMs)
        }

    /**
     * 候选交换 + 打洞。返回 null 表示状态机要求「回第 3 步重试一次」，
     * 由 [connect] 重新匹配；不在这一层自旋，免得重试语义和状态机两处各说一套。
     */
    private fun punchAndConnect(
        session: CrossNetSession,
        signaling: SignalingClient,
        sessionId: String,
        credential: String,
    ): Outcome? {
        var socket: DatagramSocket? = null
        try {
            val bound = socketFactory().also { socket = it }
            val candidates = collect(bound)
            if (candidates.isEmpty()) {
                session.onFailure(FailureReason.NO_CANDIDATES)
                closeQuietly(bound)
                return Outcome(session.state, session.prompt, session.failure, sessionId)
            }
            signaling.publishCandidates(sessionId, role, candidates)
            val peers = signaling.awaitPeerCandidates(sessionId, role, timeouts.candidatesMs)
            val punch = UdpPuncher(bound, sessionId, role, credential, now)
                .punch(peers, timeouts.punchMs, timeouts.punchIntervalMs)
            if (!punch.connected || punch.peer == null) {
                signaling.abort(sessionId, role, FailureReason.PUNCH_BLOCKED)
                session.onPunchFailed(now(), FailureReason.PUNCH_BLOCKED)
                closeQuietly(bound)
                if (session.state == CrossNetState.MATCHING) return null
                return Outcome(session.state, session.prompt, session.failure, sessionId)
            }
            session.onDirectEstablished()
            val link = UdpTunnel(bound, punch.peer).start()
            return Outcome(session.state, session.prompt, null, sessionId, link, punch.peer, credential)
        } catch (e: SignalingException) {
            // 超时也要主动放弃会话：否则服务端会一直保留这个 session，等它自己过期
            runCatching { signaling.abort(sessionId, role, e.reason) }
            session.onFailure(e.reason)
            socket?.let { closeQuietly(it) }
            return Outcome(session.state, session.prompt, session.failure, sessionId)
        } catch (e: IOException) {
            // 本地 socket 异常（端口耗尽、网络切换）：按网络不可用收口，不冒充「信号不好」
            session.onFailure(FailureReason.NO_INTERNET)
            socket?.let { closeQuietly(it) }
            return Outcome(session.state, session.prompt, session.failure, sessionId)
        }
    }

    private fun closeQuietly(socket: DatagramSocket) {
        runCatching { socket.close() }
    }
}
