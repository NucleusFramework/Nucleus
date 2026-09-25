plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    js {
        browser {
            commonWebpackConfig { outputFileName = "location-web-demo.js" }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":location"))
            implementation(libs.coroutines.core)
        }
    }
}
