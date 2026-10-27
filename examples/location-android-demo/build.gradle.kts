plugins {
    alias(libs.plugins.androidApplication)
    kotlin("android")
}

android {
    namespace = "dev.nucleusframework.locationandroiddemo"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.nucleusframework.locationandroiddemo"
        minSdk = 24
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
    implementation(project(":location"))
    implementation(libs.coroutines.android)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
