package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class GraalvmMetadataResolvabilityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `classNameFromClassPath handles nested and multi-release paths`() {
        assertEquals(
            "com.example.Foo\$Companion",
            classNameFromClassPath("com/example/Foo\$Companion.class"),
        )
        assertEquals(
            "com.example.Bar",
            classNameFromClassPath("META-INF/versions/11/com/example/Bar.class"),
        )
        assertNull(classNameFromClassPath("module-info.class"))
        assertNull(classNameFromClassPath("META-INF/versions/11/module-info.class"))
        assertNull(classNameFromClassPath("readme.txt"))
    }

    @Test
    fun `normalizeTypeForLookup strips Java and JVM array forms`() {
        assertEquals("java.lang.String", normalizeTypeForLookup("java.lang.String[]"))
        assertEquals(
            "java.lang.foreign.Linker\$Option",
            normalizeTypeForLookup("java.lang.foreign.Linker\$Option[]"),
        )
        assertEquals("com.example.Foo", normalizeTypeForLookup("com.example.Foo[][]"))
        assertNull(normalizeTypeForLookup("[B"))
        assertNull(normalizeTypeForLookup("[[I"))
        assertEquals("java.lang.String", normalizeTypeForLookup("[Ljava.lang.String;"))
        assertEquals("java.lang.String", normalizeTypeForLookup("[[Ljava.lang.String;"))
    }

    @Test
    fun `JDK prefixes and primitives are always resolvable`() {
        val empty = emptySet<String>()
        assertTrue(isTypeResolvable("java.lang.String", empty))
        assertTrue(isTypeResolvable("javax.swing.JFrame", empty))
        assertTrue(isTypeResolvable("jdk.internal.misc.Unsafe", empty))
        assertTrue(isTypeResolvable("sun.misc.Unsafe", empty))
        assertTrue(isTypeResolvable("com.sun.media.sound.PortMixerProvider", empty))
        assertTrue(isTypeResolvable("java.lang.String[]", empty))
        assertTrue(isTypeResolvable("[B", empty))
        assertTrue(isTypeResolvable("int", empty))
    }

    @Test
    fun `mapped kotlin types are unresolvable without a class file`() {
        val empty = emptySet<String>()
        for (type in listOf(
            "kotlin.Any",
            "kotlin.Int",
            "kotlin.Boolean",
            "kotlin.Long",
            "kotlin.Cloneable",
            "kotlin.Throwable",
            "kotlin.Function2",
            "kotlin.Function3",
            "kotlin.collections.List",
            "kotlin.collections.Map",
        )) {
            assertFalse("$type should be unresolvable", isTypeResolvable(type, empty))
        }
    }

    @Test
    fun `buildClasspathClassIndex reads jars and class dirs`() {
        val classDir = tmp.newFolder("classes")
        val packageDir = File(classDir, "com/example")
        packageDir.mkdirs()
        File(packageDir, "App.class").writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
        File(packageDir, "App\$Companion.class").writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))

        val jarFile = tmp.newFile("lib.jar")
        JarOutputStream(jarFile.outputStream()).use { jos ->
            jos.putNextEntry(JarEntry("org/lib/Helper.class"))
            jos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
            jos.closeEntry()
            jos.putNextEntry(JarEntry("META-INF/versions/17/org/lib/Helper.class"))
            jos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
            jos.closeEntry()
        }

        val index = buildClasspathClassIndex(listOf(classDir, jarFile))
        assertTrue(index.contains("com.example.App"))
        assertTrue(index.contains("com.example.App\$Companion"))
        assertTrue(index.contains("org.lib.Helper"))
        assertFalse(isTypeResolvable("kotlin.Int", index))
        assertTrue(isTypeResolvable("com.example.App", index))

        val packages = buildClasspathPackageIndex(listOf(classDir, jarFile))
        assertTrue(packages.contains("com.example"))
        assertTrue(packages.contains("org.lib"))
    }

    @Test
    fun `exact package protection matches prefix semantics`() {
        val packages = listOf("com.example.app", "io.acme")
        assertTrue(isUnderExactReachabilityPackages("com.example.app.Optional", packages))
        assertTrue(isUnderExactReachabilityPackages("com.example.app", packages))
        assertTrue(isUnderExactReachabilityPackages("io.acme.dep.Missing", packages))
        assertFalse(isUnderExactReachabilityPackages("com.example.other.Foo", packages))
        assertFalse(isUnderExactReachabilityPackages("kotlin.Int", packages))
        assertFalse(isUnderExactReachabilityPackages("kotlin.Int", emptyList()))
    }

    @Test
    fun `proxy entry resolvability requires every interface`() {
        val index = setOf("com.example.A", "com.example.B")
        val full =
            mapOf<String, Any?>(
                "type" to mapOf("proxy" to listOf("com.example.A", "com.example.B")),
            )
        val partial =
            mapOf<String, Any?>(
                "type" to mapOf("proxy" to listOf("com.example.A", "com.example.Missing")),
            )
        assertTrue(isEntryResolvable(full, index))
        assertFalse(isEntryResolvable(partial, index))
        assertEquals("com.example.A,com.example.B", proxyKey(full))
        assertEquals(
            "proxy[com.example.A,com.example.B]",
            entryDisplayName(full),
        )
    }

    @Test
    fun `classifyUnresolvableEntry pure policy matrix`() {
        val index = setOf("acme.app.Real")
        val real = mapOf<String, Any?>("type" to "acme.app.Real")
        val noise = mapOf<String, Any?>("type" to "kotlin.Int")
        val probe = mapOf<String, Any?>("type" to "acme.app.Optional")
        val proxyMissing =
            mapOf<String, Any?>(
                "type" to mapOf("proxy" to listOf("acme.app.Real", "acme.app.Missing")),
            )
        val proxyExact =
            mapOf<String, Any?>(
                "type" to mapOf("proxy" to listOf("kotlin.Function2", "acme.app.Optional")),
            )

        assertEquals(
            UnresolvableDisposition.RESOLVABLE,
            classifyUnresolvableEntry(real, index, emptyList(), removeUnresolvable = true),
        )
        assertEquals(
            UnresolvableDisposition.REPORT,
            classifyUnresolvableEntry(noise, index, emptyList(), removeUnresolvable = false),
        )
        assertEquals(
            UnresolvableDisposition.REMOVE,
            classifyUnresolvableEntry(noise, index, emptyList(), removeUnresolvable = true),
        )
        assertEquals(
            UnresolvableDisposition.PROTECT,
            classifyUnresolvableEntry(probe, index, listOf("acme.app"), removeUnresolvable = true),
        )
        assertEquals(
            UnresolvableDisposition.REMOVE,
            classifyUnresolvableEntry(probe, index, emptyList(), removeUnresolvable = true),
        )
        assertEquals(
            UnresolvableDisposition.REMOVE,
            classifyUnresolvableEntry(proxyMissing, index, emptyList(), removeUnresolvable = true),
        )
        assertEquals(
            UnresolvableDisposition.PROTECT,
            classifyUnresolvableEntry(proxyExact, index, listOf("acme.app"), removeUnresolvable = true),
        )
    }

    @Test
    fun `formatDispositionLine is single-tag stable`() {
        val entry = mapOf<String, Any?>("type" to "kotlin.Int")
        assertEquals(
            "  [unresolvable/kept] [reflection] kotlin.Int",
            formatDispositionLine("unresolvable/kept", "reflection", entry),
        )
    }
}
