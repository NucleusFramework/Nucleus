package dev.nucleusframework.desktop.application.internal

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * analyzeGraalvmStaticMetadata reads multi-release JARs for the JDK native-image runs on. That is
 * the resolved toolchain's own version, which `toolchain { version }`, a `GRAALVM_HOME` override
 * or the Liberica NIK fallback can set apart from `graalvm.javaLanguageVersion`.
 */
class StaticAnalysisTargetJdkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `the target JDK comes from the provisioned installation's release file`() {
        val home = graalvmHome(javaVersion = "26.0.1")
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder()).build()
        val launcher = ExternalJavaLauncher(javaBinary = File(home, "bin/java"), javaHome = home, objects = project.objects)

        assertEquals(26, javaFeatureVersion(launcher))
    }

    private fun graalvmHome(javaVersion: String): File {
        val home = tmp.newFolder("graalvm")
        File(home, "release").writeText("JAVA_VERSION=\"$javaVersion\"\nIMPLEMENTOR=\"GraalVM Community\"\n")
        return home
    }
}
