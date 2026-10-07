// Updater probes (the real engine against a fault-injecting loopback feed, a local
// directory, GitHub or a simulation) and network probes (OS trust store, HTTP clients).
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
    implementation(project(":updater-runtime"))
    implementation(project(":updater-testing"))
    implementation(project(":native-ssl"))
    implementation(project(":native-http"))
    implementation(project(":native-http-okhttp"))
    implementation(project(":native-http-ktor"))
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.java)

    testImplementation(kotlin("test"))
}
