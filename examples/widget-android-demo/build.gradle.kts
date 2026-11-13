plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinComposePlugin)
    kotlin("android")
}

android {
    namespace = "dev.nucleusframework.widgetandroiddemo"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.nucleusframework.widgetandroiddemo"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":widget-runtime"))
    implementation(libs.coroutines.android)
}

kotlin {
    compilerOptions {
        optIn.add("dev.nucleusframework.widget.ExperimentalNucleusWidgetApi")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
