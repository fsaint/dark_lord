import java.security.MessageDigest

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

    signingConfigs {
        val storeFilePath = providers.environmentVariable("DARK_LORD_RELEASE_STORE_FILE").orNull
        if (!storeFilePath.isNullOrBlank()) {
            create("prototypeRelease") {
                storeFile = file(storeFilePath)
                keyAlias = providers.environmentVariable("DARK_LORD_RELEASE_KEY_ALIAS").orNull
                    ?: error("DARK_LORD_RELEASE_KEY_ALIAS is required when DARK_LORD_RELEASE_STORE_FILE is set")
                storePassword = providers.environmentVariable("DARK_LORD_RELEASE_STORE_PASSWORD").orNull
                    ?: error("DARK_LORD_RELEASE_STORE_PASSWORD is required when DARK_LORD_RELEASE_STORE_FILE is set")
                keyPassword = providers.environmentVariable("DARK_LORD_RELEASE_KEY_PASSWORD").orNull
                    ?: error("DARK_LORD_RELEASE_KEY_PASSWORD is required when DARK_LORD_RELEASE_STORE_FILE is set")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("prototypeRelease") ?: signingConfigs.getByName("debug")
        }
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

val releaseApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
tasks.register("releaseSha256") {
    dependsOn("assembleRelease")
    doLast {
        val apk = releaseApk.get().asFile
        check(apk.isFile) { "Release APK was not produced: ${apk.path}" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(apk.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val output = layout.buildDirectory.file("outputs/apk/release/app-release.apk.sha256").get().asFile
        output.writeText("$digest  ${apk.name}\n")
        logger.lifecycle("Release artifact: ${apk.path}")
        logger.lifecycle("Release SHA-256: $digest")
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
    implementation(project(":capabilities:radios"))
    implementation(project(":capabilities:environment"))
    implementation(project(":oem:samsung-flip3"))

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
