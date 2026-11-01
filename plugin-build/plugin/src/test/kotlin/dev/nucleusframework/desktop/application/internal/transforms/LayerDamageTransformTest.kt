package dev.nucleusframework.desktop.application.internal.transforms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarFile

/**
 * Regression canary for the partial-redraw layer patch (#755): runs
 * [LayerDamageClassPatcher] against the *real* `ui-desktop` artifacts, loads
 * the patched classes with their real dependencies — so the JVM verifier
 * checks the rewritten methods — and drives the counter and the
 * `GraphicsLayer` hooks.
 *
 * Two Compose versions: the one the main repo's consumers resolve, which must
 * be patched (a bump that changes the layer class fails here), and the one the
 * plugin itself ships with, which predates `GraphicsLayerOwnerLayer` and must
 * come through as an untouched copy.
 */
class LayerDamageTransformTest {
    @Test
    fun `patched layers count invalidations and repaints`() {
        withPatchedClasspath { loader ->
            val layerClass = Class.forName(LAYER, true, loader)
            val access = Class.forName(ACCESS, true, loader)
            val contentVersion = access.getMethod("contentVersion", Any::class.java)
            // Allocated without a constructor: the counter is bumped before the
            // method body touches the (absent) layer manager.
            val layer = allocate(layerClass)
            assertEquals(0, contentVersion.invoke(null, layer))
            invokeIgnoringBody(layerClass, layer, "invalidate")
            assertEquals(1, contentVersion.invoke(null, layer))
            invokeIgnoringBody(layerClass, layer, "triggerRepaint")
            assertEquals(2, contentVersion.invoke(null, layer))
            assertEquals(-1, contentVersion.invoke(null, "not a layer"))
        }
    }

    @Test
    fun `patched graphics layers report their recordings and where they are drawn`() {
        withPatchedClasspath { loader ->
            val layerClass = Class.forName(GRAPHICS_LAYER, true, loader)
            val hooks = Class.forName(HOOKS, true, loader)
            val events = mutableListOf<String>()
            hooks.getField("recordStart").set(null, java.util.function.Consumer<Any?> { events += "start" })
            hooks.getField("draw").set(null, java.util.function.BiConsumer<Any?, Any?> { _, parent -> events += "draw:${parent != null}" })
            val layer = allocate(layerClass)
            val parent = allocate(layerClass)
            val record = layerClass.methods.single { it.name.startsWith("record") && it.parameterCount == 4 }
            try {
                record.invoke(layer, null, null, 0L, null)
            } catch (_: InvocationTargetException) {
                // The body runs against an object no constructor set up.
            }
            invokeIgnoringBody(layerClass, layer, "draw\$ui_graphics", null, parent)
            assertEquals(listOf("start", "draw:true"), events)
        }
    }

    @Test
    fun `an already patched jar is handed through unchanged`() {
        val once = Files.createTempFile("ui-desktop-once", ".jar").toFile().apply { deleteOnExit() }
        val twice = Files.createTempFile("ui-desktop-twice", ".jar").toFile().apply { deleteOnExit() }
        assertTrue(LayerDamageClassPatcher.patchJar(consumer.uiDesktop, once))
        assertFalse(LayerDamageClassPatcher.patchJar(once, twice))
        assertEquals(entryNames(once), entryNames(twice))
    }

    @Test
    fun `a jar without the layer class is copied untouched`() {
        val text = consumer.jars.first { it.name.startsWith("ui-text-desktop") }
        for (input in listOf(text, plugin.uiDesktop)) {
            val output = Files.createTempFile("ui-copy", ".jar").toFile().apply { deleteOnExit() }
            assertFalse(LayerDamageClassPatcher.patchJar(input, output))
            assertEquals(entryNames(input), entryNames(output))
        }
    }

    private fun withPatchedClasspath(block: (URLClassLoader) -> Unit) {
        val patched = Files.createTempFile("ui-desktop-patched", ".jar").toFile().apply { deleteOnExit() }
        assertTrue(
            "the layer patch did not apply to ${consumer.uiDesktop.name}",
            LayerDamageClassPatcher.patchJar(consumer.uiDesktop, patched),
        )
        val graphics = consumer.jars.first { it.name.startsWith("ui-graphics-desktop-") }
        val patchedGraphics = Files.createTempFile("ui-graphics-patched", ".jar").toFile().apply { deleteOnExit() }
        assertTrue(
            "the graphics layer patch did not apply to ${graphics.name}",
            GraphicsLayerHooksPatcher.patchJar(graphics, patchedGraphics),
        )
        val urls =
            consumer.jars
                .map {
                    when (it) {
                        consumer.uiDesktop -> patched
                        graphics -> patchedGraphics
                        else -> it
                    }
                }.map { it.toURI().toURL() }
        // Platform parent: the plugin's own test classpath must not leak classes in.
        URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader()).use(block)
    }

    private fun allocate(type: Class<*>): Any {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type)
    }

    private fun invokeIgnoringBody(
        type: Class<*>,
        target: Any,
        name: String,
        vararg args: Any?,
    ) {
        val method = type.declaredMethods.single { it.name == name }
        method.isAccessible = true
        try {
            method.invoke(target, *args)
        } catch (_: InvocationTargetException) {
            // The original body runs against an object no constructor set up.
        }
    }

    private fun entryNames(jar: File): List<String> = JarFile(jar).use { file -> file.entries().toList().map { it.name } }

    private class Classpath(
        val jars: List<File>,
    ) {
        val uiDesktop: File = jars.first { it.name.startsWith("ui-desktop-") && it.extension == "jar" }
    }

    private companion object {
        const val LAYER = "androidx.compose.ui.node.GraphicsLayerOwnerLayer"
        const val ACCESS = "androidx.compose.ui.node.NucleusLayerDamage"
        const val GRAPHICS_LAYER = "androidx.compose.ui.graphics.layer.GraphicsLayer"
        const val HOOKS = "androidx.compose.ui.graphics.layer.NucleusGraphicsLayerHooks"

        val consumer: Classpath by lazy { classpath("test.layerdamage.classpath.consumer") }
        val plugin: Classpath by lazy { classpath("test.layerdamage.classpath.plugin") }

        fun classpath(key: String): Classpath {
            val path =
                checkNotNull(System.getProperty(key)) {
                    "$key system property not set (see test-analysis-libraries.gradle.kts)"
                }
            return Classpath(path.split(File.pathSeparator).filter { it.isNotBlank() }.map(::File))
        }
    }
}
