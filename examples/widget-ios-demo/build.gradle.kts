// The iOS end-to-end bench of widget-ios: a static framework holding the widgets, linked into a
// minimal SwiftUI app and into its widget extension by build-app.sh (no Xcode project).
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinComposePlugin)
}

kotlin {
    compilerOptions {
        optIn.add("dev.nucleusframework.widget.InternalNucleusWidgetApi")
        optIn.add("dev.nucleusframework.widget.ExperimentalNucleusWidgetApi")
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "WidgetIosDemo"
            isStatic = true
            export(project(":widget-ios"))
        }
    }

    sourceSets {
        iosMain.dependencies {
            api(project(":widget-ios"))
        }
    }
}
