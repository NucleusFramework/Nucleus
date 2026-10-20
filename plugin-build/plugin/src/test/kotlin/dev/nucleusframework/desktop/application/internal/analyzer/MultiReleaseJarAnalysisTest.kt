package dev.nucleusframework.desktop.application.internal.analyzer

import org.junit.Assert.assertEquals
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
 * A multi-release JAR must be analyzed as the image's JDK reads it. LWJGL 3.4.3 is the case
 * that broke: its base `org.lwjgl.system.Upcalls` declares `native shutdown()`, the
 * `META-INF/versions/25` copy native-image compiles does not, and a JNI entry for the missing
 * method fails the image build ("Method org.lwjgl.system.Upcalls.shutdown() not found").
 */
class MultiReleaseJarAnalysisTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `classes come from the versioned entry for the target JDK`() {
        val jar = multiReleaseJar()

        val result = BytecodeAnalyzer.analyzeJar(jar, targetJavaVersion = 25)

        assertEquals(listOf("upcallFree"), nativeMethodsOf(result, "demo.Upcalls"))
    }

    @Test
    fun `a target JDK below the versioned entry still reads the base class`() {
        val jar = multiReleaseJar()

        val result = BytecodeAnalyzer.analyzeJar(jar, targetJavaVersion = 21)

        assertEquals(listOf("shutdown", "upcallFree"), nativeMethodsOf(result, "demo.Upcalls"))
    }

    @Test
    fun `a class that exists only under the versioned directory is analyzed for its JDK`() {
        val jar = multiReleaseJar()

        assertEquals(listOf("bind"), nativeMethodsOf(BytecodeAnalyzer.analyzeJar(jar, targetJavaVersion = 25), "demo.UpcallBinder"))
        assertEquals(emptyList<String>(), nativeMethodsOf(BytecodeAnalyzer.analyzeJar(jar, targetJavaVersion = 21), "demo.UpcallBinder"))
    }

    @Test
    fun `the classpath scan passes the target JDK down`() {
        val jar = multiReleaseJar()

        val result = BytecodeAnalyzer.analyzeClasspath(listOf(jar), targetJavaVersion = 25)

        assertEquals(listOf("upcallFree"), nativeMethodsOf(result, "demo.Upcalls"))
    }

    private fun nativeMethodsOf(
        result: AnalysisResult,
        type: String,
    ): List<String> =
        result.jniEntries
            .filter { it.type == type }
            .flatMap { entry -> entry.methods.map { it.name } }
            .filter { it != "<init>" }
            .sorted()

    /**
     * `demo/Upcalls` with a native `shutdown()` in the base entry and none in `versions/25`, plus
     * `demo/UpcallBinder`, which exists only in `versions/25` (like LWJGL's `ffm/UpcallBinder`).
     */
    private fun multiReleaseJar(): File {
        val file = tmp.newFile("multi-release.jar")
        val manifest =
            Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes[Attributes.Name("Multi-Release")] = "true"
            }
        JarOutputStream(file.outputStream(), manifest).use { out ->
            out.putNextEntry(JarEntry("demo/Upcalls.class"))
            out.write(upcallsClass(withNativeShutdown = true))
            out.closeEntry()
            out.putNextEntry(JarEntry("META-INF/versions/25/demo/Upcalls.class"))
            out.write(upcallsClass(withNativeShutdown = false))
            out.closeEntry()
            out.putNextEntry(JarEntry("META-INF/versions/25/demo/UpcallBinder.class"))
            out.write(nativeClass("demo/UpcallBinder", "bind"))
            out.closeEntry()
        }
        return file
    }

    private fun upcallsClass(withNativeShutdown: Boolean): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, "demo/Upcalls", null, "java/lang/Object", null)
        if (withNativeShutdown) {
            writer.visitMethod(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_NATIVE, "shutdown", "()V", null, null).visitEnd()
        }
        // A native that both copies keep, so the class is a JNI entry either way.
        writer.visitMethod(Opcodes.ACC_STATIC or Opcodes.ACC_NATIVE, "upcallFree", "(J)V", null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun nativeClass(
        internalName: String,
        nativeMethod: String,
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_STATIC or Opcodes.ACC_NATIVE, nativeMethod, "()V", null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }
}
