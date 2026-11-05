package io.github.wangnan05563.airmirror.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行期权限的「什么时候要」与「要之前说什么」（PRD 3.4 权限动态申请与说明 + 7.4 权限缺失要说明用在哪一步）。
 *
 * 这里锁的是判据和文案，不是弹窗：弹窗只在真正用到的那一刻由界面发起，
 * 而「该不该要」和「说了什么」必须是可单测的纯逻辑。
 */
class RuntimePermissionTest {

    private val nothing = emptySet<String>()

    @Test
    fun `an already granted permission is never asked for again`() {
        val granted = RuntimePermission.entries.map { it.key }.toSet()
        assertEquals(
            "两个都批了还弹一次，就是拿用户的时间换开发者的安心",
            emptyList<RuntimePermission>(),
            RuntimePermission.needs(wantsAudio = true, wantsCastingNotification = true, granted = granted),
        )
    }

    @Test
    fun `turning audio off never needs a permission`() {
        // 关声音是本机自己的事；只有要出声时才需要录音权限
        val missing = RuntimePermission.needs(wantsAudio = false, wantsCastingNotification = true, granted = nothing)
        assertEquals(listOf(RuntimePermission.CASTING_NOTIFICATION), missing)
    }

    @Test
    fun `casting asks for exactly the two it will use`() {
        val missing = RuntimePermission.needs(wantsAudio = true, wantsCastingNotification = true, granted = nothing)
        assertEquals(
            listOf(RuntimePermission.CASTING_NOTIFICATION, RuntimePermission.MIRROR_AUDIO).map { it.key },
            missing.map { it.key },
        )
    }

    @Test
    fun `the explanation lists one line per missing permission and nothing else`() {
        val audio = RuntimePermission.explain(listOf(RuntimePermission.MIRROR_AUDIO))
        assertEquals("· " + RuntimePermission.MIRROR_AUDIO.usage, audio)

        val both = RuntimePermission.explain(
            RuntimePermission.needs(wantsAudio = true, wantsCastingNotification = true, granted = nothing),
        )
        assertEquals(2, both.lines().size)
        assertTrue(both, both.contains("投屏中通知"))
        assertTrue(both, both.contains("镜像带声音"))
    }

    @Test
    fun `every usage names the step it belongs to`() {
        for (p in RuntimePermission.entries) {
            assertTrue(p.name + " 的说明没写清是哪一步", p.usage.contains("投屏") || p.usage.contains("镜像"))
        }
    }

    @Test
    fun `declining degrades a feature instead of stopping the cast`() {
        // PRD 7.4「权限缺失」那一行要的是「跳转设置、使用替代功能」，不是「投屏失败」
        for (p in RuntimePermission.entries) {
            assertTrue(p.name + " 的拒绝口径没说明还能用：" + p.declined, p.declined.contains("照样投屏"))
            assertFalse(p.name + " 把拒绝说成故障", p.declined.contains("失败"))
        }
    }

    @Test
    fun `the audio wording says it does not touch the microphone`() {
        // 用户看到「录音权限」的第一反应是窃听；这一句是本功能对外唯一的解释口径
        assertTrue(RuntimePermission.MIRROR_AUDIO.usage.contains("不采麦克风"))
    }

    @Test
    fun `the askable set stays closed and holds nothing unrelated to casting`() {
        val keys = RuntimePermission.entries.map { it.key }
        assertEquals(
            listOf(
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.RECORD_AUDIO",
            ),
            keys,
        )
        val banned = listOf("CONTACTS", "LOCATION", "SMS", "STORAGE", "CAMERA", "READ_EXTERNAL")
        for (word in banned) {
            assertFalse("不该申请与投屏无关的权限：" + word, keys.any { it.contains(word) })
        }
    }

    @Test
    fun `the permission keys match what the manifest declares`() {
        // 申请一个清单里没声明的权限会被静默拒掉，所以「要什么」和「声明了什么」必须当场核对
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        for (p in RuntimePermission.entries) {
            assertTrue(p.key + " 没在清单里声明", manifest.contains("android:name=\"${p.key}\""))
        }
    }

    @Test
    fun `a permission is only blocked after we asked and the system stopped giving a reason`() {
        // 四个组合必须分开：granted 的不用管；还带理由的还能问；从没问过的也还能问。
        // 只有「问过 + 没授予 + 系统不再给理由」才是真关死，可以引导去设置。
        assertFalse(RuntimePermission.isBlocked(granted = true, rationale = false, asked = true))
        assertFalse(RuntimePermission.isBlocked(granted = false, rationale = true, asked = true))
        assertFalse(RuntimePermission.isBlocked(granted = false, rationale = false, asked = false))
        assertTrue(RuntimePermission.isBlocked(granted = false, rationale = false, asked = true))
    }

    @Test
    fun `never asked is never treated as permanently denied`() {
        // 这就是 T04486 的核心：rationale 为假有两种含义，分不清就会把首次申请也写成「去设置」
        for (p in RuntimePermission.entries) {
            assertFalse(
                p.name + " 没问过就被判成关死",
                RuntimePermission.isBlocked(granted = false, rationale = false, asked = false),
            )
        }
    }

    @Test
    fun `the blocked message names the setting, the purpose and the consequence`() {
        val blocked = RuntimePermission.entries
        val text = RuntimePermission.blockedMessage(blocked)
        assertTrue("没说清已经在系统里关掉：" + text, text.contains("已在系统里关掉"))
        assertTrue("没告诉用户去哪里打开：" + text, text.contains("设置"))
        for (p in blocked) {
            assertTrue(p.name + " 的用途没写进只能去设置的说明", text.contains(p.usage))
            assertTrue(p.name + " 的后果没写进只能去设置的说明", text.contains(p.declined))
        }
    }

    @Test
    fun `returning from settings reports the granted facts only`() {
        // 从设置页回来只认系统当前授予态：全给了才说「已全部打开」，缺的必须点名
        assertEquals("权限已全部打开", RuntimePermission.settingsReturnNote(emptyList()))
        val one = RuntimePermission.settingsReturnNote(listOf(RuntimePermission.MIRROR_AUDIO))
        assertTrue(one, one.contains(RuntimePermission.MIRROR_AUDIO.label))
        assertTrue(one, one.contains(RuntimePermission.MIRROR_AUDIO.declined))
        assertFalse("缺权限时不能说全都打开了", one.contains("全部打开"))
    }

    @Test
    fun `every label is the opening words of its usage line`() {
        // 短名用于设置页回来后的状态行；和用途行对不上会让同一项权限在两个地方叫两个名字
        for (p in RuntimePermission.entries) {
            assertTrue(p.name + " 的短名不是用途行的开头：" + p.label + " / " + p.usage, p.usage.startsWith(p.label))
        }
    }
}
