pluginManagement {
    repositories {
        // Tencent mirror works from Iran without a VPN and is fast.
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        maven("https://repo.huaweicloud.com/repository/maven")
        // Official repos as fallback
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        maven("https://repo.huaweicloud.com/repository/maven")
        // Official repos as fallback
        google()
        mavenCentral()
    }
}
rootProject.name = "KUVAVpn"
include(":app")
