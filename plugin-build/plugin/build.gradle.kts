import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    jacoco
    alias(libs.plugins.lighthouse)
    alias(libs.plugins.pluginPublish)
}

dependencyLocking {
    lockAllConfigurations()
}

// === Sandbox runtime shim source set ===
// Declared before `dependencies` so the shim classes can be exposed to tests below.
// A tiny Java-only source set compiled into a standalone JAR embedded as a resource
// (nucleus/sandbox/nucleus-sandbox-shim.jar) inside the plugin artifact. The sandboxed packaging
// pipeline (AbstractStripNativeLibsFromJarsTask) extracts this JAR onto the packaged app's
// classpath at build time so rewritten System.load call sites can route through
// dev.nucleusframework.sandbox.NucleusSandboxLoader. Kept as a separate source set so the shim
// class never lands on the plugin's own classpath as a class — only the packaged JAR resource does.
val sandboxShimSourceSet = sourceSets.create("sandboxShim") {
    java.setSrcDirs(listOf("src/sandboxShim/java"))
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(gradleApi())

    compileOnly(localGroovy())
    compileOnly(kotlin("gradle-plugin"))
    compileOnly(kotlin("native-utils"))
    compileOnly(libs.agp)
    compileOnly(libs.agp.api)

    implementation(libs.download.task)
    implementation(libs.thumbnailator)
    implementation(libs.asm)

    // S3 auto-update manifest upload. Replaces shelling out to the `aws` CLI.
    // Force the lightweight JDK-HttpURLConnection client to avoid pulling Netty/Apache
    // onto the plugin classpath; we only do simple synchronous PutObject calls.
    implementation(libs.aws.s3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
    implementation(libs.aws.url.connection.client)

    testImplementation(libs.junit)
    // Expose the sandbox shim classes to tests so NucleusSandboxLoader.resolveBundled can be
    // exercised directly (the shim ships only as an embedded JAR resource, not on the main
    // plugin classpath, so it must be added explicitly here).
    testImplementation(sandboxShimSourceSet.output)
}

apply(from = "test-analysis-libraries.gradle.kts")
apply(from = "build-config.gradle.kts")

lighthouse {
    enableUnusedDependencyCheck.set(false)
}

// Java 17 is the floor imposed by AGP 9.x, which this plugin compiles against
// (compileOnly) — AGP 9 artifacts are built for 17 and can't be resolved by an
// 11-targeted consumer. Consequence for users: the plugin now needs a Gradle
// daemon on JVM 17+ (which Gradle 9 mandates anyway; Gradle 8 users on JVM 11
// are no longer supported).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// === Sandbox runtime shim jar + embedding ===
val sandboxShimJar by tasks.registering(Jar::class) {
    archiveFileName.set("nucleus-sandbox-shim.jar")
    // Nest under nucleus/sandbox/ so processResources places it at that path inside the
    // plugin JAR, resolvable via getResourceAsStream("/nucleus/sandbox/nucleus-sandbox-shim.jar").
    // Output must live OUTSIDE the sandboxShim source set's own resources dir, otherwise the
    // jar would re-include itself via `from(...)`.
    destinationDirectory.set(layout.buildDirectory.dir("sandboxShimEmbed/nucleus/sandbox"))
    // Package only the compiled classes — NOT `sourceSet.output` (which includes the resources
    // dir and would pull in stale/self-nested jars from prior runs).
    from(sandboxShimSourceSet.output.classesDirs)
    // Deterministic, reproducible resource (no timestamps from the build host).
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Make the embedded shim JAR part of the main resources so it ships inside the plugin
// artifact AND is on the test runtime classpath (strip-task unit tests read it as a resource).
// Wired through processResources' `from(...)` rather than a resources srcDir: this keeps the
// generated binary out of `main.allSource`, so it flows only into the runtime jar and never into
// sourcesJar. Using the task provider as the copy source also declares the dependency implicitly,
// avoiding the "uses output without declaring dependency" validation failure.
tasks.named<ProcessResources>("processResources") {
    from(sandboxShimJar) {
        into("nucleus/sandbox")
    }
}

// Apache-2.0 §4(a): this JAR is a binary distribution of source derived from the JetBrains Compose
// Multiplatform Gradle plugin, so the attribution notices and the license text must travel with it.
// Copied from the repo root (one level above this included build) so there is a single copy to
// maintain — see THIRD_PARTY_NOTICES.md §1.
tasks.named<Jar>("jar") {
    metaInf {
        from(rootProject.file("../THIRD_PARTY_NOTICES.md"))
        from(rootProject.file("../licenses")) {
            into("licenses")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=dev.nucleusframework.ExperimentalNucleusLibrary")
    }
}

gradlePlugin {
    plugins {
        create(property("ID").toString()) {
            id = property("ID").toString()
            implementationClass = property("IMPLEMENTATION_CLASS").toString()
            version = project.version.toString()
            description = property("DESCRIPTION").toString()
            displayName = property("DISPLAY_NAME").toString()
            tags.set(listOf("nucleus", "desktop", "jvm", "packaging"))
        }
    }
}

gradlePlugin {
    website.set(property("WEBSITE").toString())
    vcsUrl.set(property("VCS_URL").toString())
}

// Use Detekt with type resolution for check
tasks.named("check").configure {
    this.setDependsOn(
        this.dependsOn.filterNot {
            it is TaskProvider<*> && it.name == "detekt"
        } + tasks.named("detektMain"),
    )
}

tasks.register("setupPluginUploadFromEnvironment") {
    doLast {
        val key = System.getenv("GRADLE_PUBLISH_KEY")
        val secret = System.getenv("GRADLE_PUBLISH_SECRET")

        if (key == null || secret == null) {
            throw GradleException("gradlePublishKey and/or gradlePublishSecret are not defined environment variables")
        }

        System.setProperty("gradle.publish.key", key)
        System.setProperty("gradle.publish.secret", secret)
    }
}
