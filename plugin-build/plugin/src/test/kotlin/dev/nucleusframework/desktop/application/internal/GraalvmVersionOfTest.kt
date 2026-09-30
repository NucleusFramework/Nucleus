package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GraalvmVersionOfTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun javaHome(release: String?) =
        tmp.newFolder().also { home ->
            release?.let { home.resolve("release").writeText(it) }
        }

    @Test
    fun `reads the quoted GRAALVM_VERSION entry`() {
        // Verbatim from Oracle GraalVM 25.4.4.1.1 for windows-x64: JAVA_VERSION is the JDK line
        // (25.0.4.1.1) and must not be mistaken for the GraalVM one.
        val home =
            javaHome(
                """
                IMPLEMENTOR="Oracle Corporation"
                JAVA_VERSION="25.0.4.1.1"
                GRAALVM_VERSION="25.4.4.1.1"
                """.trimIndent(),
            )
        assertEquals("25.4.4.1.1", graalvmVersionOf(home))
    }

    @Test
    fun `a plain JDK with no GraalVM entry resolves to null`() {
        assertNull(graalvmVersionOf(javaHome("""JAVA_VERSION="25.0.4"""")))
    }

    @Test
    fun `a missing release file resolves to null`() {
        assertNull(graalvmVersionOf(javaHome(release = null)))
    }

    @Test
    fun `a blank version resolves to null`() {
        assertNull(graalvmVersionOf(javaHome("""GRAALVM_VERSION=""""")))
    }
}
