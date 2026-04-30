import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation(project(":core-runtime"))
    implementation(project(":webview-macos"))
    implementation(project(":decorated-window-jni"))
    implementation(project(":decorated-window-material3"))
    implementation(project(":darkmode-detector"))
}

application {
    mainClass.set("io.github.kdroidfilter.nucleus.webview.macos.demo.MainKt")
}
