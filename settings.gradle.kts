pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Xposed API 仓库（由 Appodeal Maven 镜像托管）
        maven { url = uri("https://artifactory.appodeal.com/appodeal-public") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Xposed API 仓库（由 Appodeal Maven 镜像托管）
        maven { url = uri("https://artifactory.appodeal.com/appodeal-public") }
    }
}

rootProject.name = "WeChatQQKiller_LSPosed"
include(":app")
