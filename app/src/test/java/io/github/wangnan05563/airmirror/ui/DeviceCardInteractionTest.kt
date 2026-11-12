package io.github.wangnan05563.airmirror.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 源码守卫：卡片交互不能退回「点一下直接连」，投屏中控制条与列表收起必须同时接线。 */
class DeviceCardInteractionTest {

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw AssertionError("未定位到项目根，cwd=" + System.getProperty("user.dir"))
    }

    @Test
    fun `卡片展开 控制条 列表收起 三条线都接了`() {
        val src = File(projectRoot(), "app/src/main/java/io/github/wangnan05563/airmirror/ui/SenderActivity.kt").readText()
        assertTrue("未使用展开状态机", src.contains("DeviceCardPolicy.toggle("))
        assertTrue("未渲染两个动作", src.contains("CardAction.PUSH_VIDEO"))
        assertTrue("停止按钮未复用 ACTION_STOP", src.contains("CaptureService.ACTION_STOP"))
        assertTrue("投屏中控制条未接线", src.contains("controlBar.visibility = if (casting)"))
        assertTrue("投屏中列表未收起", src.contains("listScroller.visibility = if (casting)"))
        assertTrue(
            "离线卡片未显示上次连接",
            src.contains("DeviceCardPolicy.subtitle(") || src.contains("DeviceCardPolicy.offlineLabel("),
        )
        // 旧行为：点卡片直接 connectTo(target)，现在只允许在展开后的动作按钮里调
        val directConnect = Regex("""setOnClickListener \{\s*if \(usable != null\) \{\s*connectTo\(target\)""")
        assertTrue("卡片又退回点一下直连", !directConnect.containsMatchIn(src))
    }
}
