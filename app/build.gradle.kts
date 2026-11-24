import java.time.LocalDate
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 发布构建的当天日期+序号（YYYYMMDD-NNN）由 scripts/build.ps1 -Patch 生成并传入；
// 普通调试构建不传，versionName 保持固定的 0.1.0-m0，避免版本名随构建时间漂移；
// versionCode 仍需单调递增以保证覆盖安装，规则见下。
val versionPatch = providers.gradleProperty("versionPatch").orNull

// 在线更新覆盖安装要求 versionCode 单调递增：仅改 versionName 时系统会把同版本/低版本包
// 当成不可安装。把 patch 的 YYYYMMDD-NNN 压成 6 位日期 + 3 位序号（如 20261116-006
// -> 261116006），既保留日期递增，又保证不超过 Android 的 2,100,000,000 上限。
val versionCodeOfPatch: (String) -> Int? = { patch ->
    val parts = patch.split('-')
    if (parts.size != 2) null else {
        val date = parts[0].takeLast(6).toIntOrNull()
        val seq = parts[1].toIntOrNull()
        if (date != null && seq != null) date * 1000 + seq else null
    }
}
val versionCodeFromPatch = versionPatch?.let(versionCodeOfPatch)

// 普通 debug 构建也必须有能覆盖线上/本地已装包的 versionCode：固定为 1 时，
// 手机上装过 GitHub Release（如 261117006）或本地 -Patch 包（如 261118005），
// 再推本地包会被系统按降级拒绝（INSTALL_FAILED_VERSION_DOWNGRADE）。
// 取「本地 release tag」与「.build-state 最近 patch」中的最大版本码；
// 与已装包同码允许覆盖安装，后续发布包日期/序号增加后自然更高，两个方向都能覆盖。
val latestTaggedVersionCode: Int? = runCatching {
    val tag = providers.exec {
        commandLine("git", "tag", "--list", "v0.1.0-m0.*", "--sort=-v:refname")
        isIgnoreExitValue = true
    }.standardOutput.asText.get()
        .lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    tag?.substringAfterLast('.')?.let(versionCodeOfPatch)
}.getOrNull()

// -Patch 构建成功后会把最后一个 patch 写进 .build-state：它可能还没发布，
// 但手机可能已经装过，调试包必须能压过它。
val localPatchVersionCode: Int? = runCatching {
    rootProject.file(".build-state/patch.txt")
        .takeIf { it.isFile }
        ?.readText()?.trim()
        ?.let(versionCodeOfPatch)
}.getOrNull()

val plainVersionCode = listOfNotNull(latestTaggedVersionCode, localPatchVersionCode).maxOrNull()
    ?: (LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd")).toInt() * 1000 + 999)

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
        versionCode = versionCodeFromPatch ?: plainVersionCode
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
            // T06123：release 打开 R8 压 dex；资源裁剪先保持关闭，避免 aapt2 在旧缓存上做 shrink 的额外风险。
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 没传签名参数且 .env 无配置时为 null，产物仍是未签名包（与 T04396 的包体核对口径一致）
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric 需要合并后的 manifest/res，否则主题（AppCompat）无法解析，
        // MaterialButton 构造会直接抛「requires Theme.AppCompat」。
        unitTests.isIncludeAndroidResources = true
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
}

// T05734：构建产物 APK 文件名加 AirMirror 前缀 + 版本号（PRD 命名规范）。
// 命名口径 AirMirror-<versionName>-<abi|universal>-<variant>.apk：
//   debug            -> AirMirror-0.1.0-m0-universal-debug.apk（保留 debug 标记，便于与本机 release 包区分）
//   release 未签名   -> AirMirror-0.1.0-m0-universal-release-unsigned.apk（保留 AGP 的签名状态标识）
// 实现：assemble 收尾时把 outputs/apk/<variant>/ 下的 app-*.apk 改名为 AirMirror-*.apk。
//
// 2026-11-15 修复（真实事故）：AGP 的 package<Variant> 做增量打包时，会把「上一次的输出 APK」
// 当作基线来合并 dex。改名搬走后基线消失，下一次改代码再打包只写出 dex + 签名（实测 159 KB、
// 无 AndroidManifest.xml/resources.arsc），装到设备上直接 INSTALL_PARSE_FAILED。
// 因此每次打包前先把上一轮的成品复制回 AGP 认识的原始名当基线，再在收尾时改名——
// 打包基线与成品内容一致，增量和 clean 构建都能得到完整包。
val apkVersionLabel = android.defaultConfig.versionName ?: "0.1.0-m0"

afterEvaluate {
    fun outputsDir(variantName: String) =
        layout.buildDirectory.dir("outputs/apk/$variantName").get().asFile

    fun finalApkName(tail: String): String {
        val abiTag = if (tail.startsWith("arm") || tail.contains("x86")) "" else "universal-"
        return "AirMirror-$apkVersionLabel-$abiTag$tail.apk"
    }

    fun restorePackagingBase(variantName: String) {
        val dir = outputsDir(variantName)
        val raw = dir.resolve("app-$variantName.apk")
        if (raw.isFile) return
        // 成品名可能是 -release 或 -release-unsigned，按前缀 + 变体后缀匹配，避免漏捡。
        val finished = dir.listFiles()
            ?.firstOrNull { it.isFile && it.name.endsWith("-$variantName.apk") && it.name.startsWith("AirMirror-") }
            ?: return
        finished.copyTo(raw, overwrite = true)
        println("[T05734] restored packaging base ${raw.name} <- ${finished.name}")
    }

    fun renameAirMirrorApks(variantName: String) {
        val outDir = outputsDir(variantName)
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
            val target = f.parentFile.resolve(finalApkName(tail))
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

    tasks.matching { it.name == "packageDebug" || it.name == "packageRelease" }.configureEach {
        doFirst {
            restorePackagingBase(name.removePrefix("package").lowercase())
        }
    }
    tasks.named("assembleDebug") { doLast { renameAirMirrorApks("debug") } }
    tasks.named("assembleRelease") { doLast { renameAirMirrorApks("release") } }
}

dependencies {
    // 跨端协议核心：纯 JVM 的帧/握手/加密/版本协商，Android 与 PC 共用（T05760）
    implementation(project(":protocol-core"))
    // 待连接页二维码：zxing core 为 Apache-2.0 纯 Java 实现，符合 PRD §8.10 引入口径
    implementation("com.google.zxing:core:3.5.3")
    // 按钮统一走开源 UI 库（Material Components, Apache-2.0）：系统默认 Button 在各家 ROM 上
    // 造型/内边距都不一样，同一页面两个按钮都会出现「高低不齐、挨得太近」。
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
    // JVM 布局审计：不依赖模拟器就能把 Activity 量到真实像素，补触控目标/尺寸回归。
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}
