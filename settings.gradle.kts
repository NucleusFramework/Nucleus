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
include(":spellcheck")
include(":system-color")
include(":decorated-window-core")
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
include(":autolaunch")
include(":scheduler")
include(":scheduler-testing")
include(":updater-testing")
include(":fs-watcher")
include(":share")

// Nucleus Lab: the single test bench app (examples/lab). One module per probe domain;
// each contributes its probes to the Metro graph assembled by :examples:lab:app.
include(":examples:lab:core")
include(":examples:lab:designsystem")
include(":examples:lab:probes:window")
include(":examples:lab:probes:workspace")
include(":examples:lab:probes:input")
include(":examples:lab:probes:rendering")
include(":examples:lab:probes:shell")
include(":examples:lab:probes:notifications")
include(":examples:lab:probes:lifecycle")
include(":examples:lab:probes:updater")
include(":examples:lab:probes:system")
include(":examples:lab:probes:fixtures")
include(":examples:lab:app")

// Apps beside the Lab: fixtures driven by CI or scripts, and samples with a build shape of their
// own (KMP/Android, JS, appex signing, the benchmark matrix, headless native-image smokes).
include(":examples:cmp-demo")
include(":examples:fs-watcher-smoke")
include(":examples:orphan-reflect-smoke")
include(":examples:benchmark-demo")
include(":examples:tao-native-test")
include(":examples:macos-appex-demo")
include(":examples:hot-update-demo")
include(":examples:share-web-demo")
includeBuild("plugin-build")
