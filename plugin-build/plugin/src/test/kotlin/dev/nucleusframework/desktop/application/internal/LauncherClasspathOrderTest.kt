package dev.nucleusframework.desktop.application.internal

import org.gradle.api.logging.Logging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LauncherClasspathOrderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fork = "kotlinx-coroutines-core-jvm-1.10.2-intellij-2-7b70.jar"
    private val real = "kotlinx-coroutines-core-jvm-1.11.0-41a5.jar"

    private fun cfg(
        separator: String,
        vararg jars: String,
    ) = (
        listOf("[Application]") +
            jars.map { "app.classpath=\$APPDIR$separator$it" } +
            listOf("app.mainclass=demo.MainKt", "", "[JavaOptions]", "java-options=-Dx=1", "")
    ).joinToString("\r\n")

    @Test
    fun `the jpackage name order is replaced by the classpath order`() {
        val text = cfg("\\", "app.jar", fork, real, "zzz.jar")
        val out = LauncherClasspathOrder.reorder(text, listOf("app.jar", "zzz.jar", real, fork))!!
        assertEquals(cfg("\\", "app.jar", "zzz.jar", real, fork), out)
    }

    @Test
    fun `unknown entries keep their place after the known ones`() {
        val text = cfg("/", "app.jar", "b.jar", "x.jar", "a.jar", "y.jar")
        val out = LauncherClasspathOrder.reorder(text, listOf("app.jar", "a.jar", "b.jar"))!!
        assertEquals(cfg("/", "app.jar", "a.jar", "b.jar", "x.jar", "y.jar"), out)
    }

    @Test
    fun `an ordered or single-entry classpath is left alone`() {
        assertNull(LauncherClasspathOrder.reorder(cfg("/", "app.jar", real, fork), listOf("app.jar", real, fork)))
        assertNull(LauncherClasspathOrder.reorder(cfg("/", "app.jar"), listOf("app.jar")))
    }

    @Test
    fun `line endings and every other line survive`() {
        val lf = cfg("/", "app.jar", fork, real).replace("\r\n", "\n")
        val out = LauncherClasspathOrder.reorder(lf, listOf("app.jar", real, fork))!!
        assertEquals(cfg("/", "app.jar", real, fork).replace("\r\n", "\n"), out)
    }

    @Test
    fun `every launcher cfg of the app image is rewritten, jvm cfg untouched`() {
        val appDir = tmp.newFolder("App", "app")
        val main = appDir.resolve("App.cfg").apply { writeText(cfg("\\", "app.jar", fork, real)) }
        val extra = appDir.resolve("Tool.cfg").apply { writeText(cfg("\\", "app.jar", fork, real)) }
        val jvm = tmp.newFolder("App", "runtime", "lib").resolve("jvm.cfg").apply { writeText("-server KNOWN\n") }
        val count = LauncherClasspathOrder.apply(tmp.root, listOf("app.jar", real, fork), Logging.getLogger("test"))
        assertEquals(2, count)
        assertEquals(cfg("\\", "app.jar", real, fork), main.readText())
        assertEquals(cfg("\\", "app.jar", real, fork), extra.readText())
        assertEquals("-server KNOWN\n", jvm.readText())
    }
}
