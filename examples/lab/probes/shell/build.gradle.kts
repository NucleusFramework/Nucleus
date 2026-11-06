// Shell integration probes: badge, taskbar progress, jump lists, Dock / menu bar, quicklist,
// tray, media controls and global hotkeys.
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
    // Built against published Nucleus modules: the in-tree ones must win (duplicate classes otherwise).
    implementation(libs.composenativetray) {
        exclude(group = "dev.nucleusframework", module = "nucleus.core-runtime")
        exclude(group = "dev.nucleusframework", module = "nucleus.darkmode-detector")
    }

    testImplementation(kotlin("test"))
}
