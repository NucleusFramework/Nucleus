package dev.nucleusframework.desktop.application.internal.analyzer

import dev.nucleusframework.desktop.application.internal.analyzer.detectors.NativeMethodDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `a mixed object class keeps its nested struct and C natives out of JNI`() {
        // A Kotlin `object` (self-typed INSTANCE field) with one JNI native, a public @CFunction
        // native and a nested @CStruct: the INSTANCE field makes the class a callback candidate,
        // whose nested-class expansion and member enrichment must not bring the C side back.
        val jar =
            jarOf(
                "demo/MixedLibC" to mixedObjectClass(),
                "demo/MixedLibC\$termios" to wordInterface("demo/MixedLibC\$termios", "org/graalvm/word/PointerBase"),
            )

        val entries = BytecodeAnalyzer.analyzeJar(jar).jniEntries

        assertEquals(listOf("demo.MixedLibC"), entries.map { it.type })
        val methods = entries.single().methods.map { it.name }
        assertTrue("jniCall must stay registered: $methods", "jniCall" in methods)
        assertFalse("@CFunction tcgetattr must not be registered: $methods", "tcgetattr" in methods)
    }

    @Test
    fun `class-retention and function-pointer C interop natives are skipped`() {
        val entries = NativeMethodDetector.detect(cInteropOnlyClass())

        assertEquals(listOf("demo.FnPtr" to listOf("jniCall")), entries.map { it.type to it.methods.map { m -> m.name }.sorted() })
    }

    @Test
    fun `a word type reached through an interface chain is not registered`() {
        val jar =
            jarOf(
                "demo/Native" to jniClassTaking("demo/Native", "demo/Derived"),
                "demo/Base" to wordInterface("demo/Base", "org/graalvm/word/PointerBase"),
                "demo/Derived" to wordInterface("demo/Derived", "demo/Base"),
            )

        val types = BytecodeAnalyzer.analyzeJar(jar).jniEntries.map { it.type }

        assertEquals(listOf("demo.Native"), types)
    }

    private fun jarOf(vararg classes: Pair<String, ByteArray>): java.io.File {
        val jar = tmp.newFile("classes-${System.nanoTime()}.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            for ((name, bytes) in classes) {
                out.putNextEntry(JarEntry("$name.class"))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return jar
    }

    /** `object MixedLibC` with INSTANCE, a JNI native and a public @CFunction native. */
    private fun mixedObjectClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, "demo/MixedLibC", null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "INSTANCE", "Ldemo/MixedLibC;", null, null).visitEnd()
        writer
            .visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_NATIVE, "tcgetattr", "(ILdemo/MixedLibC\$termios;)I", null, null)
            .apply {
                visitAnnotation("Lorg/graalvm/nativeimage/c/function/CFunction;", true).visitEnd()
                visitEnd()
            }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_NATIVE, "jniCall", "()V", null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** A CLASS-retention (invisible) @CFunction, an @InvokeCFunctionPointer and one JNI native. */
    private fun cInteropOnlyClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, "demo/FnPtr", null, "java/lang/Object", null)
        writer
            .visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_NATIVE, "invisible", "()I", null, null)
            .apply {
                visitAnnotation("Lorg/graalvm/nativeimage/c/function/CFunction;", false).visitEnd()
                visitEnd()
            }
        writer
            .visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_NATIVE, "invoke", "(J)I", null, null)
            .apply {
                visitAnnotation("Lorg/graalvm/nativeimage/c/function/InvokeCFunctionPointer;", true).visitEnd()
                visitEnd()
            }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_NATIVE, "jniCall", "()V", null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** A plain JNI class whose native takes [parameterType]. */
    private fun jniClassTaking(
        internalName: String,
        parameterType: String,
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_NATIVE, "use", "(L$parameterType;)V", null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** An interface named [internalName] extending [superInterface], with one accessor. */
    private fun wordInterface(
        internalName: String,
        superInterface: String,
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
            internalName,
            null,
            "java/lang/Object",
            arrayOf(superInterface),
        )
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "getValue", "()I", null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
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
