// Window & chrome probes: chrome, state and geometry, overlays, popups, secondary windows and
// dialogs, design-system theming. Jewel is a JVM 25 library, which the Lab already targets.
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
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
        // The Jewel showcase (theming gallery) is a verbatim copy of jewel-demo's, which uses
        // Jewel's experimental components throughout.
        optIn.add("org.jetbrains.jewel.foundation.ExperimentalJewelApi")
    }
}

dependencies {
    implementation(project(":examples:lab:designsystem"))
    implementation(project(":decorated-window-material2"))
    implementation(project(":decorated-window-material3"))
    implementation(project(":decorated-window-jewel"))
    implementation(project(":sf-symbols"))
    implementation(project(":darkmode-detector"))
    // Design-system galleries (theming probe specimens): Material 2, Material 3 + Expressive
    // with seed-derived schemes, and Material icons.
    implementation(libs.compose.material)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.materialkolor)
    implementation(libs.filekit.core)
    implementation(libs.filekit.dialogs)

    // Same Jewel set as jewel-demo: the standalone theme, the Markdown renderer and its extensions
    // (Coil for images), the icon manager IntUiTheme needs, and JNA for Jewel's cursor controller.
    // Skiko's AWT runtime must not come along.
    val jewelExclusions =
        Action<ExternalModuleDependency> {
            exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-all")
        }
    implementation(libs.jewel.int.ui.standalone, jewelExclusions)
    implementation(libs.jewel.markdown.int.ui.standalone.styling, jewelExclusions)
    implementation(libs.jewel.markdown.extensions.autolink, jewelExclusions)
    implementation(libs.jewel.markdown.extensions.gfm.alerts, jewelExclusions)
    implementation(libs.jewel.markdown.extensions.gfm.tables, jewelExclusions)
    implementation(libs.jewel.markdown.extensions.gfm.strikethrough, jewelExclusions)
    implementation(libs.jewel.markdown.extensions.images, jewelExclusions)
    implementation(libs.coil.compose)
    implementation(libs.intellij.icons)
    implementation(libs.intellij.icons.api)
    implementation(libs.intellij.icons.impl)
    implementation(libs.jna.jpms)

    testImplementation(kotlin("test"))
}
