package io.github.wangnan05563.airmirror.pc

import java.io.File
import java.util.UUID

/** PC 设备标识：持久化到 %APPDATA%\AirMirrorPC\device-id，断线续会/信任记录靠它。 */
object PcIdentity {
    fun deviceId(): String {
        val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "AirMirrorPC")
        val file = File(dir, "device-id")
        runCatching {
            if (file.isFile) {
                val saved = file.readText().trim()
                if (saved.isNotEmpty()) return saved
            }
            dir.mkdirs()
            val id = UUID.randomUUID().toString()
            file.writeText(id)
            return id
        }
        return "pc-" + UUID.randomUUID().toString().take(8)
    }
}
