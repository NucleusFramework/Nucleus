package dev.nucleusframework.desktop.application.internal

import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExternalJavaLauncherTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val objects by lazy {
        ProjectBuilder
            .builder()
            .withProjectDir(tmp.newFolder("project"))
            .build()
            .objects
    }

    @Test
    fun `metadata is read from the installation's release file`() {
        val home = javaHome("graalvm")
        home.writeReleaseFile(
            "JAVA_VERSION" to "25.0.1",
            "JAVA_RUNTIME_VERSION" to "25.0.1+9-jvmci-b01",
            "IMPLEMENTOR" to "GraalVM Community",
        )

        val metadata = launcherFor(home).metadata

        assertEquals(JavaLanguageVersion.of(25), metadata.languageVersion)
        assertEquals("25.0.1+9-jvmci-b01", metadata.javaRuntimeVersion)
        assertEquals("25.0.1", metadata.jvmVersion)
        assertEquals("GraalVM Community", metadata.vendor)
        assertEquals(home, metadata.installationPath.asFile)
    }

    // JavaExec forks `javaLauncher.get().executablePath.toString()` — not `.asFile` — so the
    // RegularFile must stringify to the absolute path of the binary.
    @Test
    fun `the executable path points at the binary and stringifies to its absolute path`() {
        val home = javaHome("graalvm")
        home.writeReleaseFile("JAVA_VERSION" to "25")
        val binary = File(home, "bin/java")

        val executablePath = launcherFor(home).executablePath

        assertEquals(binary, executablePath.asFile)
        assertEquals(binary.absolutePath, executablePath.toString())
    }

    // Gradle probes the language version before running the task; an installation without a
    // `release` file (a partial copy, an unusual layout) must degrade instead of failing there.
    @Test
    fun `a missing release file yields a probeable language version`() {
        val metadata = launcherFor(javaHome("no-release")).metadata

        assertEquals(JavaLanguageVersion.of(1), metadata.languageVersion)
        assertEquals("Unknown", metadata.vendor)
    }

    // The patched-JVM path points the launcher at a copy of the JDK that carries no `release`
    // file of its own, so the metadata has to come from the JDK it was copied from.
    @Test
    fun `metadata can be read from a home other than the launcher's`() {
        val patched = javaHome("patched")
        val source = javaHome("source")
        source.writeReleaseFile("JAVA_VERSION" to "21.0.4", "IMPLEMENTOR" to "JetBrains s.r.o.")

        val metadata =
            ExternalJavaLauncher(
                javaBinary = File(patched, "bin/java"),
                javaHome = patched,
                objects = objects,
                metadataJavaHome = source,
            ).metadata

        assertEquals(JavaLanguageVersion.of(21), metadata.languageVersion)
        assertEquals("JetBrains s.r.o.", metadata.vendor)
        assertEquals(patched, metadata.installationPath.asFile)
    }

    private fun launcherFor(home: File) =
        ExternalJavaLauncher(
            javaBinary = File(home, "bin/java"),
            javaHome = home,
            objects = objects,
        )

    private fun javaHome(name: String): File =
        tmp.newFolder(name).also {
            File(it, "bin").mkdirs()
            File(it, "bin/java").writeText("")
        }

    private fun File.writeReleaseFile(vararg entries: Pair<String, String>) {
        File(this, "release").writeText(
            entries.joinToString("\n") { (key, value) -> "$key=\"$value\"" },
        )
    }
}
