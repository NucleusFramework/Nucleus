package dev.nucleusframework.nucleus.desktop.application.internal.analyzer.detectors

import dev.nucleusframework.nucleus.desktop.application.internal.analyzer.JniEntry
import dev.nucleusframework.nucleus.desktop.application.internal.analyzer.MethodSignature
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Detects native methods (ACC_NATIVE flag) in class files and produces JNI entries.
 *
 * Also extracts types referenced in native method signatures (parameters AND return types)
 * since GraalVM JNI configs need all types accessible from native code.
 */
internal object NativeMethodDetector {
    data class NativeMethodResult(
        val jniEntries: Set<JniEntry>,
        val referencedTypes: Set<String>,
        /** Field types of classes that have native methods (potential JNI callback types). */
        val jniClassFieldTypes: Set<String>,
        /** Superclass of classes that have native methods (native code often calls superclass methods). */
        val superclassType: String? = null,
    )

    fun detect(classBytes: ByteArray): Set<JniEntry> = detectWithReferences(classBytes).jniEntries

    fun detectWithReferences(classBytes: ByteArray): NativeMethodResult {
        val entries = mutableMapOf<String, MutableSet<MethodSignature>>()
        val referencedTypes = mutableSetOf<String>()
        val fieldTypes = mutableSetOf<String>()
        var hasNativeMethods = false
        var superClassName: String? = null
        val reader = ClassReader(classBytes)
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                private lateinit var className: String

                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    className = name.replace('/', '.')
                    if (superName != null && superName != "java/lang/Object") {
                        superClassName = superName.replace('/', '.')
                    }
                }

                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor? {
                    // Collect field types — we'll use them if this class has native methods
                    val fieldType = Type.getType(descriptor)
                    collectObjectTypes(fieldType, fieldTypes)
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (access and Opcodes.ACC_NATIVE != 0) {
                        hasNativeMethods = true
                        val argTypes = Type.getArgumentTypes(descriptor)
                        val retType = Type.getReturnType(descriptor)
                        val paramTypes = argTypes.map { asmTypeToJavaName(it) }

                        entries
                            .getOrPut(className) { mutableSetOf() }
                            .add(MethodSignature(name, paramTypes))

                        for (t in argTypes) {
                            collectObjectTypes(t, referencedTypes)
                        }
                        collectObjectTypes(retType, referencedTypes)
                    }
                    return null
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG,
        )

        // Only report field types and superclass if this class actually has native methods
        val jniFieldTypes = if (hasNativeMethods) fieldTypes else emptySet()
        val superType = if (hasNativeMethods) superClassName else null

        val jniEntries = entries.map { (type, methods) -> JniEntry(type = type, methods = methods) }.toSet()
        return NativeMethodResult(jniEntries, referencedTypes, jniFieldTypes, superType)
    }

    /**
     * Scans a class to extract all non-private methods and fields, producing a JNI entry
     * with `jniAccessible` semantics. Used for types referenced as fields in JNI classes
     * (these are callback types called from native code).
     */
    fun extractJniCallbackEntry(classBytes: ByteArray): JniEntry? {
        var className = ""
        val methods = mutableSetOf<MethodSignature>()
        val fields = mutableSetOf<String>()

        val reader = ClassReader(classBytes)
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    className = name.replace('/', '.')
                }

                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor? {
                    // Include non-private fields (native code accesses these via GetFieldID/GetStaticFieldID)
                    if (access and Opcodes.ACC_PRIVATE == 0) {
                        fields.add(name)
                    }
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    // Include non-private non-static methods (native code calls these via CallMethod)
                    // Also include <init> constructors
                    if (name == "<clinit>") return null
                    if (access and Opcodes.ACC_PRIVATE != 0 && name != "<init>") return null

                    val paramTypes = Type.getArgumentTypes(descriptor).map { asmTypeToJavaName(it) }
                    methods.add(MethodSignature(name, paramTypes))
                    return null
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG,
        )

        if (className.isEmpty()) return null
        return JniEntry(type = className, methods = methods, fields = fields, jniAccessible = true)
    }

    /**
     * Recursively collects all object types from an ASM Type (handles arrays).
     */
    private fun collectObjectTypes(
        type: Type,
        into: MutableSet<String>,
    ) {
        when (type.sort) {
            Type.OBJECT -> into.add(type.className)
            Type.ARRAY -> collectObjectTypes(type.elementType, into)
        }
    }
}

/**
 * Converts an ASM Type to a Java source-level type name as used in GraalVM configs.
 */
internal fun asmTypeToJavaName(type: Type): String =
    when (type.sort) {
        Type.BOOLEAN -> "boolean"
        Type.BYTE -> "byte"
        Type.CHAR -> "char"
        Type.SHORT -> "short"
        Type.INT -> "int"
        Type.LONG -> "long"
        Type.FLOAT -> "float"
        Type.DOUBLE -> "double"
        Type.VOID -> "void"
        Type.ARRAY -> {
            val elementType = asmTypeToJavaName(type.elementType)
            elementType + "[]".repeat(type.dimensions)
        }
        Type.OBJECT -> type.className
        else -> type.className
    }
