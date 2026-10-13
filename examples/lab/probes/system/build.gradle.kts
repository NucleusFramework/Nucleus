// System probes: appearance, system info, energy, filesystem watcher, share sheet, native library extraction.
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
    implementation(project(":darkmode-detector"))
    implementation(project(":system-color"))
    implementation(project(":system-info"))
    implementation(project(":energy-manager"))
    implementation(project(":fs-watcher"))
    implementation(project(":share"))
    // Extract-and-load JNI library (#317): the call site the sandboxed pipeline rewrites.
    implementation(libs.zstd.kmp.jvm)

    testImplementation(kotlin("test"))
}
