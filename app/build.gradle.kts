plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.fsaint.androidagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fsaint.androidagent"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}
