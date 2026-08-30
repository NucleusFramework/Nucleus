import dev.nucleusframework.desktop.application.dsl.GarbageCollector
import dev.nucleusframework.desktop.application.dsl.GraalvmDistribution
import dev.nucleusframework.desktop.application.dsl.NativeImageGarbageCollector
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("dev.nucleusframework")
}

dependencies {
    implementation(project(":decorated-window-tao"))
    implementation(project(":nucleus-application"))
    implementation(project(":core-runtime"))
    implementation(compose.desktop.currentOs)
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

val measureGc = providers.gradleProperty("gc").orNull?.lowercase()
val measureHeap = providers.gradleProperty("maxHeap").orNull ?: "256m"
val enableAot = providers.gradleProperty("enableAotCache").orNull == "true"
val enableProguard = providers.gradleProperty("proguard").orNull == "true"

nucleus.application {
    mainClass = "startupbench.MainKt"

    providers.gradleProperty("measureJavaHome").orNull?.let { javaHome = it }

    garbageCollector =
        when (measureGc) {
            "serial" -> GarbageCollector.SERIAL
            "parallel" -> GarbageCollector.PARALLEL
            "g1" -> GarbageCollector.G1
            "z" -> GarbageCollector.Z
            else -> null
        }

    jvmArgs("-Xmx$measureHeap")

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        imageName = "startup-bench"
        maxHeapSize.set(measureHeap)
        if (measureGc == "g1") {
            garbageCollector.set(NativeImageGarbageCollector.G1)
        }
        if (providers.gradleProperty("graalvmDistribution").orNull?.lowercase() == "oracle") {
            toolchain {
                distribution.set(GraalvmDistribution.ORACLE)
            }
        }
    }

    buildTypes {
        release {
            proguard {
                isEnabled = enableProguard
            }
        }
    }

    nativeDistributions {
        targetFormats(TargetFormat.Deb, TargetFormat.Nsis, TargetFormat.Dmg)
        packageName = "NucleusStartupBench"
        packageVersion = "1.0.0"
        enableAotCache = enableAot
        linux {
            debMaintainer = "KDroidFilter <dev@kdroidfilter.com>"
        }
    }
}

val startupClasspathFile = layout.buildDirectory.file("startup-classpath.txt")

tasks.register("writeStartupClasspath") {
    group = "verification"
    description =
        "Writes a jar-only runtime classpath. Leyden/CDS rejects exploded class directories."
    dependsOn("jar")
    val runtime = sourceSets["main"].runtimeClasspath
    val jarFile = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.files(runtime)
    inputs.file(jarFile)
    outputs.file(startupClasspathFile)
    doLast {
        val projectJar = jarFile.get().asFile
        val deps = runtime.files.filter { it.isFile && it.absoluteFile != projectJar.absoluteFile }
        val classpath =
            (listOf(projectJar) + deps).joinToString(System.getProperty("path.separator"))
        startupClasspathFile.get().asFile.writeText(classpath)
    }
}
