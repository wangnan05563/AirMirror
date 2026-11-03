plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.wangnan05563.airmirror"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.wangnan05563.airmirror"
        // Q-5 已裁：Android 4.4 (API 19) 为接收端硬下限，不得静默抬高
        minSdk = 19
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 待连接页二维码：zxing core 为 Apache-2.0 纯 Java 实现，符合 PRD §8.10 引入口径
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
