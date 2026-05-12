package dev.nucleusframework.internal.utils

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JavaExecutableTest {
    @Test
    fun `java executable is resolved under bin directory`() {
        val path = javaExecutable("/fake/jdk")

        assertTrue(path.endsWith(File("bin", executableName("java")).path))
    }
}
