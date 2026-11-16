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
