plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.fsaint.androidagent.capabilities.device"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
    }
}

dependencies {
    implementation(project(":core:model"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
