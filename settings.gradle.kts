pluginManagement {
    repositories {
        // Aliyun mirrors work from Iran without a VPN and are fast.
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        // Official repos as fallback (through VPN if needed)
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        // Official repos as fallback
        google()
        mavenCentral()
    }
}
rootProject.name = "KUVAVpn"
include(":app")
