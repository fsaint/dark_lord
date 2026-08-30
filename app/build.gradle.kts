plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:runtime"))
    implementation(project(":core:policy"))
    implementation(project(":core:mcp"))
    implementation(project(":core:skills"))
    implementation(project(":capabilities:accessibility"))
    implementation(project(":capabilities:apps"))
    implementation(project(":capabilities:screen"))
    implementation(project(":capabilities:camera"))
    implementation(project(":capabilities:audio"))
    implementation(project(":capabilities:device"))
    implementation(project(":capabilities:sms"))
    implementation(project(":capabilities:telephony"))
    implementation(project(":capabilities:notifications"))
    implementation(project(":oem:samsung-flip3"))

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
