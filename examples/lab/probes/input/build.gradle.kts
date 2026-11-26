// Input probes: scroll & wheel, gestures, pointer, keyboard & IME, focus, accessibility,
// drag & drop, clipboard, spellcheck — plus the `a11y-surface` fixture the CI
// accessibility jobs (AT-SPI / UIA / AX) drive.
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
    implementation(project(":spellcheck"))
    implementation(libs.compose.ui)
    // Specimen only: the spellcheck probe's Material field (the integration is tested per field type).
    implementation(libs.compose.material3)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}
