package dev.nucleusframework.desktop.application.internal.analyzer

import dev.nucleusframework.desktop.application.internal.analyzer.detectors.NativeMethodDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * `native` methods with a GraalVM C interop annotation are bound by native-image, not JNI.
 * Mordant's `terminalinterface.nativeimage.LinuxLibC` (via Clikt) is the case that broke:
 * registering its `@CFunction`/`@CConstant` natives, and through their signatures the
 * `@CStruct` word types they take, made native-image fail with "Should not reach here: This
 * method should never be called" in its points-to analysis.
 */
class CInteropNativeMethodTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `C interop natives are not JNI entries`() {
        val entries = NativeMethodDetector.detect(libCClass())

        assertEquals(listOf("demo.LibC" to listOf("jniCall")), entries.map { it.type to it.methods.map { m -> m.name }.sorted() })
    }

    @Test
    fun `struct types of C interop signatures are not referenced`() {
        val result = NativeMethodDetector.detectWithReferences(libCClass())

        assertFalse(result.referencedTypes.contains("demo.LibC\$winsize"))
    }

    @Test
    fun `a jar scan registers no struct type for JNI`() {
        val jar = tmp.newFile("libc.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("demo/LibC.class"))
            out.write(libCClass())
            out.closeEntry()
            out.putNextEntry(JarEntry("demo/LibC\$winsize.class"))
            out.write(structInterface())
            out.closeEntry()
        }

        val types = BytecodeAnalyzer.analyzeJar(jar).jniEntries.map { it.type }

        assertEquals(listOf("demo.LibC"), types)
    }

    /** Shaped like Mordant's LinuxLibC: `@CFunction` and `@CConstant` natives plus one JNI native. */
    private fun libCClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, "demo/LibC", null, "java/lang/Object", null)
        writer.visitAnnotation("Lorg/graalvm/nativeimage/c/CContext;", false).visitEnd()
        writer
            .visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_NATIVE, "ioctl", "(IILdemo/LibC\$winsize;)I", null, null)
            .apply {
                visitAnnotation("Lorg/graalvm/nativeimage/c/function/CFunction;", true).visitEnd()
                visitEnd()
            }
        writer
            .visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_NATIVE, "NCCS", "()I", null, null)
            .apply {
                visitAnnotation("Lorg/graalvm/nativeimage/c/constant/CConstant;", true).visitEnd()
                visitEnd()
            }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_NATIVE, "jniCall", "()V", null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** A `@CStruct`-style word type: an interface extending `PointerBase`. */
    private fun structInterface(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
            "demo/LibC\$winsize",
            null,
            "java/lang/Object",
            arrayOf("org/graalvm/word/PointerBase"),
        )
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "getWs_col", "()S", null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }
}
