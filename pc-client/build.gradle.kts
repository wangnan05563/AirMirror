plugins {
    kotlin("jvm")
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(project(":protocol-core"))
    implementation("org.jcodec:jcodec:0.2.5")
    implementation("org.jcodec:jcodec-javase:0.2.5")
    // 二维码编解码与 PC 摄像头取帧：ZXing core 为 Apache-2.0 纯 Java；webcam-capture 仅用于 PC 扫码预览。
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.github.sarxos:webcam-capture:0.3.12")
    // WASAPI 回环采集：Windows COM 接口通过 JNA 直接调用，不要求系统装“立体声混音”虚拟设备。
    implementation("net.java.dev.jna:jna:5.6.0")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("io.github.wangnan05563.airmirror.pc.Main")
}

tasks.named<Sync>("installDist") {
    // 未传 FFmpeg 目录时不应读取无值 provider：否则 installDist 直接失败，
    // 连 AWT + JCodec 的无 FFmpeg 兜底都打不出来。
    providers.gradleProperty("airmirrorFfmpegDir").orNull?.let { dir ->
        from(file(dir)) { into("ffmpeg") }
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()
    providers.gradleProperty("airmirrorFfmpeg").orNull?.let {
        systemProperty("airmirror.ffmpeg", it)
    }
}
