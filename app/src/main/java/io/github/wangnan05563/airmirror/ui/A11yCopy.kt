package io.github.wangnan05563.airmirror.ui

/**
 * 无障碍文案唯一出口（PRD UI-03 / 设计 §4.1）：纯 Kotlin，不依赖 android.*。
 * 所有描述由状态组合生成，JVM 单测直接锁长度、术语与状态差异；界面层只调用这里，不写字面量。
 *
 * 硬规则（设计 §3）：动作开头状态置后；不写控件类型名；数字说人话；不朗读 ●/○/→/·；
 * 锁定期不回读旧码也不读占位符；术语只用「设备/投屏/镜像/推送/投屏码」；普通描述 ≤40 字、二维码 ≤50 字。
 */
object A11yCopy {

    /** 动态名字截断到安全长度，保证含名字的整句仍 ≤40 字；短名字原样保留。 */
    private fun clip(s: String, max: Int): String = if (s.length <= max) s else s.take(max)

    private fun selected(selected: Boolean): String = if (selected) "已选中" else "未选中"

    // ───────────────────────── 主入口 ─────────────────────────

    fun mainSend(): String = "投到其他设备，打开发送端"

    fun mainReceive(): String = "接收别人投来的画面，打开接收端"

    fun mainPrivacy(): String = "查看完整说明，打开数据边界说明"

    // ───────────────────────── 发送端 ─────────────────────────

    fun pause(paused: Boolean): String = if (paused) "继续投屏，当前已暂停" else "暂停投屏，当前正在投"

    fun stop(target: String): String = "停止投屏，当前投到「${clip(target, 16)}」"

    fun audio(on: Boolean): String = if (on) "关闭声音，当前有声" else "打开声音，当前无声"

    fun permissionFix(missing: List<String>): String {
        val names = clip(missing.joinToString("、").ifEmpty { "投屏所需权限" }, 24)
        return "去设置打开权限：$names"
    }

    fun crossNetSender(configured: Boolean, endpoint: String): String =
        if (configured) "跨网信令：已配置（${clip(endpoint, 16)}），双击修改"
        else "跨网信令：未配置，双击设置"

    fun checkUpdate(): String = "检查更新，双击查询新版本"

    fun codeConnect(): String = "用码连接，使用电视上的六位投屏码"

    fun addressConnect(): String = "直接连接，使用电视地址或配对链接"

    fun takeover(from: String, seconds: Int, yield: Boolean): String {
        val name = clip(from, 16)
        return if (yield) "让出投屏给「$name」，当前投屏将结束" else "拒绝「$name」接管投屏"
    }

    fun card(name: String, online: Boolean, supported: Boolean, expanded: Boolean, lastLabel: String): String {
        val n = clip(name, 12)
        return when {
            !online -> "「$n」，离线，${clip(lastLabel, 12)}，双击查看替代"
            !supported -> "「$n」，不支持，双击查看支持的投屏方式"
            expanded -> "「$n」，在线，已展开，双击收起"
            else -> "「$n」，在线，支持镜像，双击展开"
        }
    }

    fun cardAction(name: String, mirror: Boolean): String =
        if (mirror) "镜像屏幕到「${clip(name, 16)}」" else "推送视频到「${clip(name, 16)}」"

    fun codeInputLabel(): String = "输入投屏码，六位数字"

    fun addressInputLabel(): String = "输入设备地址或配对链接"

    /** UX-01 空态三按钮：沿用既有可见口径，不引入新词（R10）。 */
    fun diagnosisRetry(): String = "重试发现，重新搜索同一网络里的电视"

    fun diagnosisCode(): String = "用投屏码连接，输入大屏上的投屏码"

    fun diagnosisIp(): String = "手动输 IP，填写电视地址直连"

    // ───────────────────────── 接收端设置页 ─────────────────────────

    fun settingsEntry(casting: Boolean): String = if (casting) "打开显示设置，当前正在投屏" else "打开显示设置"

    fun mode(label: String, hint: String, selected: Boolean): String =
        "显示模式：${clip(label, 8)}，${selected(selected)}。${clip(hint, 14)}"

    fun qualityAuto(selected: Boolean): String = "画质：自动（推荐），${selected(selected)}"

    fun quality(tierLabel: String, width: Int, height: Int, fps: Int, selected: Boolean): String =
        "画质：${clip(tierLabel, 10)}，${width}x$height，${fps}帧，${selected(selected)}"

    fun toggle(label: String, on: Boolean): String =
        "${clip(label, 12)}，当前${if (on) "开" else "关"}，双击${if (on) "关闭" else "开启"}"

    fun signaling(configured: Boolean, endpoint: String): String =
        if (configured) "跨网信令：已配置（${clip(endpoint, 12)}），双击修改"
        else "跨网信令：未配置（仅局域网），双击填写"

    fun diagnostics(enabled: Boolean): String =
        "崩溃日志，当前${if (enabled) "开" else "关"}，双击${if (enabled) "关闭" else "开启"}，仅本机"

    fun clearData(count: Int): String = "清除本地日志与缓存，共 $count 条，双击清除"

    fun finish(): String = "完成，关闭显示设置"

    fun settingsOpened(): String = "显示设置已打开"

    fun settingsClosed(): String = "显示设置已关闭"

    // ───────────────────────── 接收端待连接 / 授权 / 结束 ─────────────────────────

    /** 锁动态不含任何数字（R5）：不回读旧码，也不读「------」占位符。 */
    fun code(code: String, locked: Boolean, seconds: Int): String =
        if (locked) "投屏码已临时锁定，请稍后重试" else "投屏码$code，$seconds 秒后更换"

    fun mirrorQr(): String = "投屏二维码，手机扫码即可投屏；也可念出上方六位投屏码"

    fun browserQr(): String = "浏览器投屏二维码，扫码打开上传页"

    fun browserUrl(url: String): String {
        if (url.isBlank()) return "访客上传地址，可在浏览器打开"
        // 读屏会把 http:// 念成「http 冒号斜杠斜杠」，只保留地址本体
        return "访客上传地址，可在浏览器打开：${clip(url.substringAfter("://"), 20)}"
    }

    fun promptAllow(name: String): String = "允许「${clip(name, 16)}」投屏"

    fun promptDeny(name: String): String = "拒绝「${clip(name, 16)}」投屏"

    fun endCancel(): String = "取消，继续投屏"

    fun endConfirm(): String = "结束投屏"

    fun endConfirmBar(): String = "结束投屏？十秒后自动取消"

    fun moreInfo(expanded: Boolean): String =
        if (expanded) "收起信息，双击收起次要入口" else "更多信息，双击展开次要入口"

    // ───────────────────────── 首启引导 ─────────────────────────

    fun onboardingStep(step: Int, total: Int, title: String): String =
        "第 $step 步，共 $total 步：${clip(title, 16)}"

    fun onboardingBack(): String = "上一步，返回前一步"

    fun onboardingSkip(): String = "跳过本步"

    fun onboardingNext(lastStep: Boolean): String =
        if (lastStep) "完成，保存设备名并开始等待投屏" else "下一步"

    fun onboardingSkipAll(): String = "跳过全部设置，使用默认设备名"

    fun onboardInputLabel(): String = "输入设备名"

    // ───────────────────────── 浮层播报 ─────────────────────────

    fun nameOverlay(name: String): String = "「${clip(name, 16)}」正在投屏"

    fun recovery(): String = "连接中断，正在恢复"

    fun codeRotated(code: String, seconds: Int): String = "投屏码已更换为 $code，$seconds 秒后再次更换"

    fun codeLocked(): String = "投屏码已临时锁定，请稍后重试"
}