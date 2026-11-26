package dev.nucleusframework.desktop.application.internal.transforms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Files

/**
 * Regression canary for the LCD/ClearType bytecode patch: runs
 * [LcdTextClassPatcher] against the *real* `ui-text-desktop` artifacts — the
 * Compose version the plugin ships with AND the one the main repo's
 * consumers resolve (see `test-analysis-libraries.gradle.kts`) — loads each
 * patched jar, and checks all three runtime paths of the generated
 * `getPlatformDefault()` wrapper. If a Compose bump changes the class
 * layout, `patchJar` throws and this test fails loudly.
 */
class LcdTextDefaultTransformTest {
    @Test
    fun `patched default is SubpixelAntiAlias on Windows`() {
        forEachPatchedJar { loader ->
            withSystemProperties(osName = "Windows 11", lcdProperty = null) {
                assertEquals("SubpixelAntiAlias", loader.platformDefaultSmoothing())
            }
        }
    }

    @Test
    fun `patched default caches and stays stable across calls`() {
        forEachPatchedJar { loader ->
            withSystemProperties(osName = "Windows 11", lcdProperty = null) {
                assertEquals("SubpixelAntiAlias", loader.platformDefaultSmoothing())
                assertEquals("SubpixelAntiAlias", loader.platformDefaultSmoothing())
            }
        }
    }

    @Test
    fun `opt-out property falls back to the original grayscale default`() {
        forEachPatchedJar { loader ->
            withSystemProperties(osName = "Windows 11", lcdProperty = "false") {
                assertEquals("AntiAlias", loader.platformDefaultSmoothing())
            }
        }
    }

    @Test
    fun `non-Windows platforms delegate to the original default`() {
        forEachPatchedJar { loader ->
            withSystemProperties(osName = "Linux", lcdProperty = null) {
                assertEquals("AntiAlias", loader.platformDefaultSmoothing())
            }
        }
    }

    /** Runs [block] with a fresh classloader over every patched jar. */
    private fun forEachPatchedJar(block: (URLClassLoader) -> Unit) {
        for (jar in patchedJars) {
            val urls = (listOf(jar) + supportJars).map { it.toURI().toURL() }.toTypedArray()
            URLClassLoader(urls, javaClass.classLoader).use { loader ->
                block(loader)
            }
        }
    }

    private fun URLClassLoader.platformDefaultSmoothing(): String {
        val frsClass = loadClass("androidx.compose.ui.text.FontRasterizationSettings")
        registerGrayscaleTextBackend(frsClass)
        val companion = frsClass.getField("Companion").get(null)
        val settings = companion.javaClass.getMethod("getPlatformDefault").invoke(companion)
        return settings.javaClass.getMethod("getSmoothing").invoke(settings).toString()
    }

    /**
     * From Compose 1.13 the original default comes from the registered text
     * backend (`PlatformTextRegistry`), which a real app registers at startup.
     * Registers a stub reporting Skiko's grayscale default; a no-op on older
     * Compose versions, which compute the default themselves.
     */
    private fun URLClassLoader.registerGrayscaleTextBackend(frsClass: Class<*>) {
        val registryClass =
            runCatching { loadClass("androidx.compose.ui.text.platform.PlatformTextRegistry") }.getOrNull() ?: return
        val platformText = loadClass("androidx.compose.ui.text.platform.PlatformText")
        val smoothing = loadClass("androidx.compose.ui.text.FontSmoothing")
        val hinting = loadClass("androidx.compose.ui.text.FontHinting")
        val grayscale =
            frsClass
                .getConstructor(smoothing, hinting, Boolean::class.java, Boolean::class.java)
                .newInstance(enumValue(smoothing, "AntiAlias"), enumValue(hinting, "Normal"), true, false)
        val stub =
            Proxy.newProxyInstance(this, arrayOf(platformText)) { _, method, _ ->
                check(method.name == "getDefaultFontRasterizationSettings") { "unexpected call: ${method.name}" }
                grayscale
            }
        val registry = registryClass.getField("INSTANCE").get(null)
        registryClass.getMethod("clear").invoke(registry)
        registryClass.getMethod("register", platformText).invoke(registry, stub)
    }

    private fun enumValue(
        type: Class<*>,
        name: String,
    ): Any = type.enumConstants.single { (it as Enum<*>).name == name }

    private fun <T> withSystemProperties(
        osName: String,
        lcdProperty: String?,
        block: () -> T,
    ): T {
        val previousOs = System.getProperty("os.name")
        val previousLcd = System.getProperty("nucleus.text.lcd")
        System.setProperty("os.name", osName)
        if (lcdProperty != null) System.setProperty("nucleus.text.lcd", lcdProperty)
        try {
            return block()
        } finally {
            System.setProperty("os.name", previousOs)
            if (previousLcd != null) {
                System.setProperty("nucleus.text.lcd", previousLcd)
            } else {
                System.clearProperty("nucleus.text.lcd")
            }
        }
    }

    private companion object {
        val supportJars: List<File> by lazy {
            System
                .getProperty("test.lcd.support.jars")
                .orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .map(::File)
        }

        val patchedJars: List<File> by lazy {
            val sourceJars =
                checkNotNull(System.getProperty("test.lcd.uitext.jars")) {
                    "test.lcd.uitext.jars system property not set (see test-analysis-libraries.gradle.kts)"
                }.split(File.pathSeparator).map(::File)
            assertTrue("no ui-text-desktop jars resolved", sourceJars.isNotEmpty())
            sourceJars.map { sourceJar ->
                assertTrue("ui-text-desktop jar missing: $sourceJar", sourceJar.isFile)
                val output = Files.createTempFile("ui-text-desktop-patched", ".jar").toFile()
                output.deleteOnExit()
                LcdTextClassPatcher.patchJar(sourceJar, output)
                output
            }
        }
    }
}
