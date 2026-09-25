plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    js {
        browser {
            commonWebpackConfig { outputFileName = "share-web-demo.js" }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":share"))
            implementation(libs.coroutines.core)
        }
    }
}
