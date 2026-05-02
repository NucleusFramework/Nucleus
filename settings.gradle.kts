pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://www.jetbrains.com/intellij-repository/releases")
        maven("https://www.jetbrains.com/intellij-repository/snapshots")
    }
}

plugins {
    id("com.gradle.develocity") version "4.3.2"
}

develocity {
    buildScan.termsOfUseUrl = "https://gradle.com/terms-of-service"
    buildScan.termsOfUseAgree = "yes"
    buildScan.publishing.onlyIf {
        System.getenv("GITHUB_ACTIONS") == "true" &&
            it.buildResult.failures.isNotEmpty()
    }
}

rootProject.name = "Nucleus"

include(":example")
include(":core-runtime")
include(":aot-runtime")
include(":updater-runtime")
include(":darkmode-detector")
include(":native-ssl")
include(":native-http")
include(":native-http-okhttp")
include(":native-http-ktor")
include(":linux-hidpi")
include(":system-color")
include(":decorated-window-core")
include(":decorated-window-awt")
include(":decorated-window-jbr")
include(":decorated-window-jni")
include(":decorated-window-tao")
include(":nucleus-application")
include(":sample-shared")
include(":sample-tao")
include(":sample-jni")
include(":decorated-window-jewel")
include(":decorated-window-material2")
include(":decorated-window-material3")
include(":graalvm-runtime")
include(":energy-manager")
include(":taskbar-progress")
include(":notification-macos")
include(":service-management-macos")
include(":service-management-demo")
include(":notification-linux")
include(":notification-windows")
include(":notification-common")
include(":launcher-windows")
include(":launcher-linux")
include(":global-hotkey")
include(":media-control")
include(":launcher-macos")
include(":menu-macos")
include(":freedesktop-icons")
include(":sf-symbols")
include(":system-info")
include(":system-info")
include(":autolaunch")
include(":scheduler")
include(":scheduler-testing")
include(":scheduler-demo")
include(":system-info-demo")
include(":jewel-sample")
include(":sample-cmp")
includeBuild("plugin-build")
