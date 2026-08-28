pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // 备用仓库: 经典 Xposed API 工件
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "UniGlassBar"
include(":app")
