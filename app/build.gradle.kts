plugins {
    id("com.android.application")
}

android {
    namespace = "com.murong.prioritylocker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.murong.prioritylocker"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "v10.0-lsposed"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Xposed API（compileOnly — 不打包进 APK，由 LSPosed 框架提供）
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("de.robv.android.xposed:api:82:sources")
}
