plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 发布构建的当天日期+序号（YYYYMMDD-NNN）由 scripts/build.ps1 -Patch 生成并传入；
// 普通调试构建不传，versionName 保持固定的 0.1.0-m0，保证同一 commit 的产物可复现。
val versionPatch = providers.gradleProperty("versionPatch").orNull

// 可选 release 签名：参数来源 = -P 显式传入 > 仓库根 .env（本机开发用，已 gitignore）。
// .env 兜底的意义：一键构建脚本不传参时也不会掉回 unsigned —— 线上曾把 unsigned 包发到
// GitHub Release，应用内「检查更新」下到手点安装，系统只能报「软件包似乎无效」。
val envFileValue: (String) -> String? = { key ->
    rootProject.file(".env").takeIf { it.isFile }
        ?.readLines()
        ?.firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

val keystoreFile = providers.gradleProperty("keystoreFile").orNull?.takeIf { it.isNotBlank() }
    ?: envFileValue("AIRMIRROR_KEYSTORE")
val keystorePassword = providers.gradleProperty("keystorePassword").orNull?.takeIf { it.isNotBlank() }
    ?: envFileValue("AIRMIRROR_KEYSTORE_PASSWORD")
val keyAliasName = providers.gradleProperty("keyAlias").orNull?.takeIf { it.isNotBlank() }
    ?: envFileValue("AIRMIRROR_KEY_ALIAS")
val keyPasswordValue = providers.gradleProperty("keyPassword").orNull?.takeIf { it.isNotBlank() }
    ?: keystorePassword

android {
    namespace = "io.github.wangnan05563.airmirror"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.wangnan05563.airmirror"
        // Q-5 已重签（2026-11-12，T04908 裁决 1）：下限抬到 Android 5.1 (API 22)，
        // 以启用 View.setAccessibilityTraversalBefore/After 修正无障碍遍历顺序。
        minSdk = 22
        targetSdk = 35
        versionCode = 1
        versionName = if (versionPatch.isNullOrBlank()) "0.1.0-m0" else "0.1.0-m0.$versionPatch"
    }

    // PRD 1.1 安装包与 ABI 适配：本项目当前不含任何 native 库，per-ABI 包只会产出三份内容
    // 完全相同的重复包（2026-11-15 实测：三者 SHA-256 一致、lib/ 条目为 0），故收敛为
    // **只出单个 universal 包**（全 ABI 通吃，模拟器/开发机也装它）。
    // 实测记录：splits 保持 isEnable=true 但清空 ABI 列表时，AGP 会产出缺 dex 的残缺包（7KB），
    // 因此这里直接关掉 splits，让 AGP 走标准单包产出，再由文件末尾的重命名补 universal 标记。
    // 将来引入 .so 原生库后，按 PRD 1.1 在这里重新启用 splits 并加回双 ABI 列表即可再拆双包。
    splits {
        abi {
            isEnable = false
            reset()
        }
    }
    // 注：各 ABI 包需独立 versionCode 时（Play 商店分发）再按 ABI 分配基段；本项目发到 GitHub，
    // 多 ABI 包可并存，故此处不强制覆盖 versionCode，避免耦合 AGP 内部 API。

    signingConfigs {
        if (!keystoreFile.isNullOrBlank() && !keystorePassword.isNullOrBlank() && !keyAliasName.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue ?: keystorePassword
            }
        }
    }

    buildTypes {
        // 签名统一（2026-11-15）：debug 也用发布密钥。Android 只允许同证书的包互相覆盖安装，
        // debug/release 各用一套证书会让「应用内在线更新」注定装不上（系统报签名不一致）。
        // 没传/没配 keystore 时保持 AGP 默认 debug 证书，不影响纯本地调试。
        if (signingConfigs.findByName("release") != null) {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = false
            // 没传签名参数且 .env 无配置时为 null，产物仍是未签名包（与 T04396 的包体核对口径一致）
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { test ->
            // 单测会写临时文件（下载器、上传服务）。默认 java.io.tmpdir 常在系统盘，
            // 系统盘满时会出现「磁盘空间不足」这种与被测代码无关的失败，统一指到仓库内 build 目录。
            test.systemProperty("java.io.tmpdir", layout.buildDirectory.dir("test-tmp").get().asFile.absolutePath)
            test.doFirst {
                layout.buildDirectory.dir("test-tmp").get().asFile.mkdirs()
            }
        }
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // T05734 重命名逻辑见下方 Project 作用域的 afterEvaluate 块（android 扩展接收者内无法调用 afterEvaluate）。
}

// T05734：构建产物 APK 文件名加 AirMirror 前缀 + 版本号（PRD 命名规范）。
// AGP 原始名是 app-<abi>-<variant>.apk；当前只出 universal 包，原始名退化为 app-<variant>.apk，
// 统一改名为 AirMirror-<versionName>-<abi|universal>-<variant>.apk：
//   debug            -> AirMirror-0.1.0-m0-universal-debug.apk（保留 debug 标记，便于与本机 release 包区分）
//   release 未签名   -> AirMirror-0.1.0-m0-universal-release-unsigned.apk（保留 AGP 的签名状态标识）
// 版本号取 android.defaultConfig.versionName，与包内 BuildConfig/系统「应用信息」显示的版本一致。
// 实现：在 assemble 生命周期任务收尾时，对 build/outputs/apk/<variant>/ 下的 app-*.apk
// 做纯文件重命名。这样绕开 variant.outputs 集合在 Kotlin DSL 下的 all/forEach 重载歧义，
// 且 push-apk.cmd / 配置里的 AirMirror-*.apk 命名能直接命中。
val apkVersionLabel = android.defaultConfig.versionName ?: "0.1.0-m0"

afterEvaluate {
    // T05734：在 assemble 生命周期任务收尾时，把 build/outputs/apk/<variant>/ 下的
    // app-<abi>-<variant>.apk 改名为 AirMirror-<versionName>-<abi>-<variant>.apk。
    // 挂在 assemble* 上而非具体 package* 任务，可覆盖 abi splits + universal 的全部产出，
    // 且一定在所有打包动作结束、APK 落盘之后执行。
    fun renameAirMirrorApks(variantName: String) {
        val outDir = layout.buildDirectory.dir("outputs/apk/$variantName").get().asFile
        val files = outDir.listFiles()?.filter { it.isFile && it.extension == "apk" }.orEmpty()
        val rawApks = files.filter { it.name.startsWith("app-") }
        // package* 为 UP-TO-DATE 时不会再产出 app-*.apk（上一轮的名字已经改掉了），
        // 此时必须原样保留已改名的产物——先删后改会在重复构建时把唯一的产物清空。
        if (rawApks.isEmpty()) {
            println("[T05734] no app-*.apk under outputs/apk/$variantName (package task up-to-date), keep existing artifacts")
            return
        }
        val renamed = mutableListOf<String>()
        rawApks.forEach { f ->
            val tail = f.name.removePrefix("app-").removeSuffix(".apk")
            // 只出 universal 包时原始名不带 ABI 标记（app-debug.apk），补 universal 保持既有命名口径；
            // 将来恢复 per-ABI splits 时原始名自带 arm64-v8a / armeabi-v7a，原样保留。
            val abiTag = if (tail.startsWith("arm") || tail.contains("x86")) "" else "universal-"
            val target = f.parentFile.resolve("AirMirror-$apkVersionLabel-$abiTag$tail.apk")
            // 同名旧产物（上一轮构建留下的）先删掉再搬，Windows 上 renameTo 不会覆盖已存在文件。
            if (target.exists()) target.delete()
            renamed.add(if (f.renameTo(target)) target.name else f.name)
        }
        // 本轮没产出的旧式命名（如上一版不带版本号的 AirMirror-<abi>-<variant>.apk）一并清掉，
        // 否则 build.ps1 按 *.apk 汇总时会把过期包一起拷进 release/。
        val expected = renamed.toSet()
        files.filter { it.name.startsWith("AirMirror-") && it.name !in expected }.forEach { it.delete() }
        println("[T05734] APK packaged -> ${renamed.joinToString()}")
    }
    tasks.named("assembleDebug") { doLast { renameAirMirrorApks("debug") } }
    tasks.named("assembleRelease") { doLast { renameAirMirrorApks("release") } }
}

dependencies {
    // 待连接页二维码：zxing core 为 Apache-2.0 纯 Java 实现，符合 PRD §8.10 引入口径
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
