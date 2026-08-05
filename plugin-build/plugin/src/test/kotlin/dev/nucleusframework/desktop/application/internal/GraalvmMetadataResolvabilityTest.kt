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
        assertEquals(
            "proxy[com.example.A,com.example.B]",
            entryDisplayName(full),
        )
    }

    @Test
    fun `entry protected when any proxy interface is under exact packages`() {
        val packages = listOf("com.example")
        val entry =
            mapOf<String, Any?>(
                "type" to mapOf("proxy" to listOf("kotlin.Function2", "com.example.Optional")),
            )
        assertTrue(isEntryProtectedByExactReachability(entry, packages))
        assertFalse(
            isEntryProtectedByExactReachability(
                mapOf("type" to "kotlin.Int"),
                packages,
            ),
        )
        assertTrue(
            isEntryProtectedByExactReachability(
                mapOf("type" to "com.example.OptionalFeature"),
                packages,
            ),
        )
    }
}
