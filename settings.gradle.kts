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
    ":capabilities:device",
    ":capabilities:sms",
    ":oem:samsung-flip3",
)
