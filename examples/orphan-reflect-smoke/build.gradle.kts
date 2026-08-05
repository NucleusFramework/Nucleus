import dev.nucleusframework.desktop.application.dsl.ExactReachabilityMetadata
import dev.nucleusframework.desktop.application.dsl.NativeImageMarch
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Minimal GraalVM smoke for #441: Room-shaped Class.forName(name + "_Impl") with no
// hand-written reachability entry. Build/run with:
//   ./gradlew :examples:orphan-reflect-smoke:runGraalvmNative
// Size matrix (three variants):
//   -Pnucleus.orphanDetect=false                         # baseline
//   (default)                                            # orphan rule
//   -Pnucleus.reflectAllProjectClasses=true              # sledgehammer

plugins {
    kotlin("jvm")
    id("dev.nucleusframework")
}

dependencies {
    implementation(project(":core-runtime"))
    implementation(project(":graalvm-runtime"))
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

nucleus.application {
    mainClass = "dev.nucleusframework.orphanreflectsmoke.MainKt"

    nativeDistributions {
        packageName = "orphan-reflect-smoke"
        packageVersion = "1.0.0"
    }

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        imageName = "orphan-reflect-smoke"
        march = NativeImageMarch.COMPATIBILITY
        // Fail loud on the dev loop when the _Impl is missing from reachability.
        exactReachabilityMetadata = ExactReachabilityMetadata.APP_PACKAGES
        // Headless, tiny image — no AWT/Compose.
        buildArgs.addAll(
            "-Djava.awt.headless=true",
        )

        providers.gradleProperty("nucleus.orphanDetect").orNull?.let {
            detectOrphanProjectClasses.set(it.toBoolean())
        }
        providers.gradleProperty("nucleus.reflectAllProjectClasses").orNull?.let {
            reflectionForProjectClasses.set(it.toBoolean())
        }
    }
}
