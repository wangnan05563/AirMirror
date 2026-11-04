package io.github.wangnan05563.airmirror.sender

/**
 * 运行期权限的「用在哪一步」与「不给会怎样」（PRD 3.4「权限动态申请与说明」+ 7.4「权限缺失：说明该权限
 * 用于哪一步」）。
 *
 * 文案与判据放在这里而不是散在 Activity 里，是为了让单测能锁住两件事：说明必须点明是哪一步在用，
 * 拒绝后果必须是「功能降级」而不是「投屏停摆」。界面只在真正要用到的那一刻取用（PRD §04
 * 「权限申请在真正用到时才弹出」），启动时一个都不弹。
 */
enum class RuntimePermission(val key: String, val usage: String, val declined: String) {
    // 「投屏中」前台服务通知：Android 13 起才需要运行时申请
    CASTING_NOTIFICATION("android.permission.POST_NOTIFICATIONS", "投屏中通知：状态栏常驻一条「投屏中」，不打开 App 也能停止投屏", "不给照样投屏，只是通知栏里没有投屏中与停止入口"),
    // 镜像带声音：系统把「采集本机播放的声音」记在录音权限里，只取系统播放的内容
    MIRROR_AUDIO("android.permission.RECORD_AUDIO", "镜像带声音：采集本机正在播放的声音，只取系统播放的内容，不采麦克风", "不给照样投屏，只是镜像没有声音"),
    ;

    companion object {

        /**
         * 这一刻还缺哪些权限。两个 wants 由调用方带着系统版本判断传进来：
         * 「想要」和「系统认不认这个权限」是两件事，混在枚举里判会让低版本上多弹一次。
         */
        fun needs(
            wantsAudio: Boolean,
            wantsCastingNotification: Boolean,
            granted: Set<String>,
        ): List<RuntimePermission> = listOfNotNull(
            if (wantsCastingNotification && CASTING_NOTIFICATION.key !in granted) CASTING_NOTIFICATION else null,
            if (wantsAudio && MIRROR_AUDIO.key !in granted) MIRROR_AUDIO else null,
        )

        /** 说明面板的正文：一行一个权限的用途，缺哪个写哪个 */
        fun explain(missing: List<RuntimePermission>): String =
            missing.joinToString("\n") { "· " + it.usage }
    }
}
