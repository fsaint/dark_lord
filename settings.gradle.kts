pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android-agent"
include(
    ":app",
    ":core:model",
    ":core:data",
    ":core:runtime",
    ":core:policy",
    ":core:mcp",
    ":core:skills",
    ":test-support",
    ":capabilities:accessibility",
    ":capabilities:apps",
    ":capabilities:screen",
    ":capabilities:camera",
    ":capabilities:audio",
    ":capabilities:device",
    ":capabilities:sms",
    ":capabilities:telephony",
    ":capabilities:notifications",
    ":capabilities:radios",
    ":oem:samsung-flip3",
)
