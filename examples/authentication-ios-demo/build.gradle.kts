// iOS E2E fixture for `authentication`: a bare UIKit app, driven on the simulator by
// scripts/ios-authentication-e2e.sh (Face ID enrolment and matches through notifyutil).
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    iosSimulatorArm64 {
        binaries.executable {
            entryPoint = "dev.nucleusframework.authenticationiosdemo.main"
        }
    }

    sourceSets {
        iosSimulatorArm64Main.dependencies {
            implementation(project(":authentication"))
            implementation(libs.coroutines.core)
        }
    }
}
