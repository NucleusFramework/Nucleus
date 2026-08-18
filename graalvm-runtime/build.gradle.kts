import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    kotlin("jvm")
    id("nucleus.native-module")
    alias(libs.plugins.vanniktechMavenPublish)
}

val publishVersion =
    providers
        .environmentVariable("GITHUB_REF")
        .orNull
        ?.removePrefix("refs/tags/v")
        ?: "1.0.0"

dependencies {
    compileOnly("org.graalvm.nativeimage:svm:25.0.0")
    implementation(project(":core-runtime"))
    implementation(project(":linux-hidpi"))
    testImplementation(kotlin("test"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

tasks.test {
    useJUnitPlatform()
}

nucleusNative {
    macos("nucleus_locale", "Compiles the CoreFoundation locale JNI bridge into macOS dylibs (arm64 + x64)")
}

// All Java sources are package-private SVM substitutions (Target_* classes), so there is
// nothing for javadoc to document. Disable the task to avoid the "No public or protected
// classes found to document" error, and publish an empty javadoc jar instead.
tasks.named<Javadoc>("javadoc") {
    enabled = false
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))

    coordinates("dev.nucleusframework", "nucleus.graalvm-runtime", publishVersion)

    pom {
        name.set("Nucleus GraalVM Runtime")
        description.set("GraalVM native-image runtime initialization and font manager substitutions")
        url.set("https://github.com/NucleusFramework/Nucleus")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("nucleusframework")
                name.set("NucleusFramework")
                url.set("https://github.com/NucleusFramework")
            }
        }

        scm {
            url.set("https://github.com/NucleusFramework/Nucleus")
            connection.set("scm:git:git://github.com/NucleusFramework/Nucleus.git")
            developerConnection.set("scm:git:ssh://git@github.com/NucleusFramework/Nucleus.git")
        }
    }

    publishToMavenCentral()
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
}
