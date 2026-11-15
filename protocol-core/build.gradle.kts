plugins {
    // 版本由根项目 org.jetbrains.kotlin.android 2.0.21 带入的 classpath 提供，此处不重复声明
    kotlin("jvm")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // 纯 JVM 协议核心不依赖 Android；测试只用 JUnit4，保持与 app 单测口径一致
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
