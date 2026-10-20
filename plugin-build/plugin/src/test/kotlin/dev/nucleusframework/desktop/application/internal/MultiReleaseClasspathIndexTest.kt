package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * The classpath class index feeds cleanupGraalvmMetadata's resolvability pass and the
 * `matchPackages` filter of filterGraalvmLibraryMetadata. A class that exists only in a
 * multi-release JAR's `META-INF/versions/26` is not on the classpath of a JDK 25 image, so
 * neither may count it as present.
 */
class MultiReleaseClasspathIndexTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a class only under a higher versions directory is absent for the target JDK`() {
        val jar = multiReleaseJar(tmp.newFile("multi-release.jar"))

        val jdk25 = buildClasspathClassIndex(listOf(jar), targetJavaVersion = 25)
        assertTrue(jdk25.contains("demo.Upcalls"))
        assertTrue(jdk25.contains("demo.ffm.UpcallBinder"))
        assertFalse(jdk25.contains("demo.next.FutureApi"))

        val jdk26 = buildClasspathClassIndex(listOf(jar), targetJavaVersion = 26)
        assertTrue(jdk26.contains("demo.next.FutureApi"))
    }

    @Test
    fun `a class only under a versions directory at or below the target JDK is present`() {
        val jar = multiReleaseJar(tmp.newFile("multi-release.jar"))

        assertTrue(buildClasspathClassIndex(listOf(jar), targetJavaVersion = 25).contains("demo.ffm.UpcallBinder"))
        assertFalse(buildClasspathClassIndex(listOf(jar), targetJavaVersion = 21).contains("demo.ffm.UpcallBinder"))
    }

    @Test
    fun `a package only under a higher versions directory does not match library metadata`() {
        val jar = multiReleaseJar(tmp.newFile("multi-release.jar"))

        assertFalse(buildClasspathPackageIndex(listOf(jar), targetJavaVersion = 25).contains("demo.next"))
        assertTrue(buildClasspathPackageIndex(listOf(jar), targetJavaVersion = 26).contains("demo.next"))
    }

    @Test
    fun `versions directories of a JAR that is not multi-release are not classes`() {
        val jar = tmp.newFile("plain.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            out.putClass("demo/Base")
            out.putClass("demo/Hidden", entryPrefix = "META-INF/versions/17/")
        }

        val index = buildClasspathClassIndex(listOf(jar), targetJavaVersion = 25)

        assertTrue(index.contains("demo.Base"))
        assertFalse(index.contains("demo.Hidden"))
    }
}

/**
 * A multi-release JAR: `demo/Upcalls` in the base and in `versions/25`, `demo/ffm/UpcallBinder`
 * only in `versions/25` (like LWJGL 3.4.3's FFM classes), and `demo/next/FutureApi` only in
 * `versions/26`.
 */
internal fun multiReleaseJar(file: File): File {
    val manifest =
        Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name("Multi-Release")] = "true"
        }
    JarOutputStream(file.outputStream(), manifest).use { out ->
        out.putClass("demo/Upcalls")
        out.putClass("demo/Upcalls", entryPrefix = "META-INF/versions/25/")
        out.putClass("demo/ffm/UpcallBinder", entryPrefix = "META-INF/versions/25/")
        out.putClass("demo/next/FutureApi", entryPrefix = "META-INF/versions/26/")
    }
    return file
}

private fun JarOutputStream.putClass(
    internalName: String,
    entryPrefix: String = "",
) {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null)
    writer.visitEnd()
    putNextEntry(JarEntry("$entryPrefix$internalName.class"))
    write(writer.toByteArray())
    closeEntry()
}
