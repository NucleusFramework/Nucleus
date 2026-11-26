// Shell integration probes: badge, taskbar progress, jump lists, Dock / menu bar, quicklist,
// tray, media controls, global hotkeys and Windows 11 widgets.
plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.metro)
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        optIn.add("dev.nucleusframework.window.ExperimentalNucleusApi")
        optIn.add("dev.nucleusframework.widget.ExperimentalNucleusWidgetApi")
    }
}

dependencies {
    implementation(project(":examples:lab:designsystem"))
    implementation(project(":launcher-windows"))
    implementation(project(":launcher-linux"))
    implementation(project(":launcher-macos"))
    implementation(project(":notification-macos"))
    implementation(project(":taskbar-progress"))
    implementation(project(":taskbar-progress-tao"))
    implementation(project(":menu-macos"))
    implementation(project(":sf-symbols"))
    implementation(project(":freedesktop-icons"))
    implementation(project(":media-control"))
    implementation(project(":global-hotkey"))
    implementation(project(":widget-windows"))
    implementation(project(":widget-macos"))
    implementation(project(":tray"))

    testImplementation(kotlin("test"))
}
