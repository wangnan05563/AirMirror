plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 发布构建的当天日期+序号（YYYYMMDD-NNN）由 scripts/build.ps1 -Patch 生成并传入；
// 普通调试构建不传，versionName 保持固定的 0.1.0-m0，保证同一 commit 的产物可复现。
val versionPatch = providers.gradleProperty("versionPatch").orNull

// 可选 release 签名：只有显式传入 keystore 参数时才生成签名配置。
// 不传就保持未签名（默认行为不变），避免把密钥路径与口令写进仓库。
val keystoreFile = providers.gradleProperty("keystoreFile").orNull
val keystorePassword = providers.gradleProperty("keystorePassword").orNull
val keyAliasName = providers.gradleProperty("keyAlias").orNull
val keyPasswordValue = providers.gradleProperty("keyPassword").orNull

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

    // PRD 1.1 安装包与 ABI 适配：发布侧产出 armeabi-v7a(32 位, 老盒子默认) + arm64-v8a(64 位)
    // 双 ABI 独立包；universal 仍含 x86_64，供开发机与模拟器(emulator-5554)安装验证。
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
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
        release {
            isMinifyEnabled = false
            // 没传签名参数时为 null，产物仍是未签名包（与 T04396 的包体核对口径一致）
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

// T05734：构建产物 APK 文件名加 AirMirror 前缀（PRD 命名规范）。
// abi splits 下每个 ABI 一个输出，默认名为 app-<abi>-<variant>.apk（universal 为 app-universal-<variant>.apk），
// 统一替换为 AirMirror- 前缀；已带前缀则跳过，避免重复前缀。
// 实现：在每个 variant 的 package* 任务收尾时，对 build/outputs/apk/<variant>/ 下的 app-*.apk
// 做纯文件重命名。这样绕开 variant.outputs 集合在 Kotlin DSL 下的 all/forEach 重载歧义，
// 且 push-apk.cmd / 配置里的 AirMirror-*.apk 命名能直接命中。
afterEvaluate {
    // T05734：在 assemble 生命周期任务收尾时，把 build/outputs/apk/<variant>/ 下的
    // app-<abi>-<variant>.apk 重命名为 AirMirror-<abi>-<variant>.apk（universal 同理）。
    // 挂在 assemble* 上而非具体 package* 任务，可覆盖 abi splits + universal 的全部产出，
    // 且一定在所有打包动作结束、APK 落盘之后执行。
    fun renameAirMirrorApks(variantName: String) {
        val outDir = layout.buildDirectory.dir("outputs/apk/$variantName").get().asFile
        val renamed = mutableListOf<String>()
        outDir.listFiles()?.filter { it.isFile && it.name.startsWith("app-") && it.extension == "apk" }?.forEach { f ->
            val newFile = f.parentFile.resolve("AirMirror-" + f.name.removePrefix("app-"))
            if (!newFile.exists() && f.renameTo(newFile)) {
                renamed.add(newFile.name)
            }
        }
        if (renamed.isNotEmpty()) {
            println("[T05734] renamed APKs -> ${renamed.joinToString()}")
        }
    }
    tasks.named("assembleDebug") { doLast { renameAirMirrorApks("debug") } }
    tasks.named("assembleRelease") { doLast { renameAirMirrorApks("release") } }
}

dependencies {
    // 待连接页二维码：zxing core 为 Apache-2.0 纯 Java 实现，符合 PRD §8.10 引入口径
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
