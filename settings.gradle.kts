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
    id("com.gradle.develocity") version "4.4.1"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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
include(":decorated-window-jewel")
include(":decorated-window-material2")
include(":decorated-window-material3")
include(":graalvm-runtime")
include(":energy-manager")
include(":taskbar-progress")
include(":taskbar-progress-tao")
include(":notification-macos")
include(":service-management-macos")
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
include(":fs-watcher")

// Demo / sample applications (consolidated under examples/)
include(":examples:nucleus-demo")
include(":examples:compose-demo")
include(":examples:tao-demo")
include(":examples:swing-tao-demo")
include(":examples:zstd-demo")
include(":examples:jni-demo")
include(":examples:shared")
include(":examples:jewel-demo")
include(":examples:cmp-demo")
include(":examples:scheduler-demo")
include(":examples:service-management-demo")
include(":examples:system-info-demo")
include(":examples:fs-watcher-smoke")
include(":examples:extra-launcher-demo")
include(":examples:benchmark-demo")
include(":examples:gstreamer-demo")
include(":examples:tao-native-test")
include(":examples:window-scaffold-demo")
includeBuild("plugin-build")
