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

    testImplementation(libs.junit)
}

apply(from = "test-analysis-libraries.gradle.kts")
apply(from = "build-config.gradle.kts")

lighthouse {
    enableUnusedDependencyCheck.set(false)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
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
