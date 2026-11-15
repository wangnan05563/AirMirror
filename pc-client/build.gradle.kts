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
    from(providers.gradleProperty("airmirrorFfmpegDir").map { file(it) }) {
        into("ffmpeg")
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()
    providers.gradleProperty("airmirrorFfmpeg").orNull?.let {
        systemProperty("airmirror.ffmpeg", it)
    }
}
