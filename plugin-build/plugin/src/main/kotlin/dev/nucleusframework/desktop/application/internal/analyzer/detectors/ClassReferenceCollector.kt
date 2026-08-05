package dev.nucleusframework.desktop.application.internal.analyzer.detectors

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.TypePath

/**
 * Collects every class type referenced by a class file's constant pool, descriptors,
 * instructions, and annotations — **excluding the class itself**.
 *
 * Used by [OrphanProjectClassDetector]: a project class that never appears in this set
 * across the whole classpath can only be reached reflectively.
 */
internal object ClassReferenceCollector {
    /**
     * @return FQCNs referenced by [classBytes], never including the declaring class.
     */
    fun collect(classBytes: ByteArray): Set<String> {
        val refs = mutableSetOf<String>()
        var selfInternal: String? = null
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
                    selfInternal = name
                    superName?.let { addInternal(it, refs) }
                    interfaces?.forEach { addInternal(it, refs) }
                    parseSignature(signature, refs)
                }

                override fun visitAnnotation(
                    descriptor: String?,
                    visible: Boolean,
                ): AnnotationVisitor? {
                    addDescriptor(descriptor, refs)
                    return AnnotationRefVisitor(refs)
                }

                override fun visitTypeAnnotation(
                    typeRef: Int,
                    typePath: TypePath?,
                    descriptor: String?,
                    visible: Boolean,
                ): AnnotationVisitor? {
                    addDescriptor(descriptor, refs)
                    return AnnotationRefVisitor(refs)
                }

                override fun visitOuterClass(
                    owner: String?,
                    name: String?,
                    descriptor: String?,
                ) {
                    owner?.let { addInternal(it, refs) }
                    addDescriptor(descriptor, refs)
                }

                override fun visitInnerClass(
                    name: String?,
                    outerName: String?,
                    innerName: String?,
                    access: Int,
                ) {
                    name?.let { addInternal(it, refs) }
                    outerName?.let { addInternal(it, refs) }
                }

                override fun visitNestHost(nestHost: String?) {
                    nestHost?.let { addInternal(it, refs) }
                }

                override fun visitNestMember(nestMember: String?) {
                    nestMember?.let { addInternal(it, refs) }
                }

                override fun visitPermittedSubclass(permittedSubclass: String?) {
                    permittedSubclass?.let { addInternal(it, refs) }
                }

                override fun visitField(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor {
                    addDescriptor(descriptor, refs)
                    parseSignature(signature, refs)
                    if (value is Type) addType(value, refs)
                    return object : FieldVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(
                            descriptor: String?,
                            visible: Boolean,
                        ): AnnotationVisitor? {
                            addDescriptor(descriptor, refs)
                            return AnnotationRefVisitor(refs)
                        }

                        override fun visitTypeAnnotation(
                            typeRef: Int,
                            typePath: TypePath?,
                            descriptor: String?,
                            visible: Boolean,
                        ): AnnotationVisitor? {
                            addDescriptor(descriptor, refs)
                            return AnnotationRefVisitor(refs)
                        }
                    }
                }

                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    addDescriptor(descriptor, refs)
                    parseSignature(signature, refs)
                    exceptions?.forEach { addInternal(it, refs) }
                    return MethodRefVisitor(refs)
                }
            },
            // Full scan: code, frames, debug (local-variable types matter for some generators).
            0,
        )
        val self = selfInternal?.replace('/', '.')
        if (self != null) {
            refs.remove(self)
        }
        return refs
    }

    /**
     * Lightweight pass: only the class header (super + interfaces). Used when the detector
     * only needs the immediate supertypes of a candidate orphan.
     */
    fun collectSupertypes(classBytes: ByteArray): Supertypes {
        var superName: String? = null
        var interfaces: List<String> = emptyList()
        var access = 0
        var internalName = ""
        val reader = ClassReader(classBytes)
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    accessFlags: Int,
                    name: String,
                    signature: String?,
                    superN: String?,
                    ifaces: Array<out String>?,
                ) {
                    access = accessFlags
                    internalName = name
                    superName = superN?.replace('/', '.')
                    interfaces = ifaces?.map { it.replace('/', '.') }.orEmpty()
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return Supertypes(
            internalName = internalName,
            access = access,
            superName = superName,
            interfaces = interfaces,
        )
    }

    /**
     * True when the class is concrete (not interface/abstract/annotation/module/enum-style)
     * and declares a public no-arg constructor.
     */
    fun isConcreteWithPublicNoArgCtor(classBytes: ByteArray): Boolean {
        var access = 0
        var hasPublicNoArgCtor = false
        val reader = ClassReader(classBytes)
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    accessFlags: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    access = accessFlags
                }

                override fun visitMethod(
                    methodAccess: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (name == "<init>" &&
                        descriptor == "()V" &&
                        methodAccess and Opcodes.ACC_PUBLIC != 0
                    ) {
                        hasPublicNoArgCtor = true
                    }
                    return null
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        if (access and Opcodes.ACC_INTERFACE != 0) return false
        if (access and Opcodes.ACC_ABSTRACT != 0) return false
        if (access and Opcodes.ACC_ANNOTATION != 0) return false
        if (access and Opcodes.ACC_MODULE != 0) return false
        // Synthetic / Kotlin file facades are almost never loaded by name-convention frameworks
        if (access and Opcodes.ACC_SYNTHETIC != 0) return false
        // Public top-level (or public nested) only — package-private generated types are rare
        // for Class.forName patterns and explode false positives on Kotlin internals.
        if (access and Opcodes.ACC_PUBLIC == 0) return false
        return hasPublicNoArgCtor
    }

    data class Supertypes(
        val internalName: String,
        val access: Int,
        val superName: String?,
        val interfaces: List<String>,
    )

    private class MethodRefVisitor(
        private val refs: MutableSet<String>,
    ) : MethodVisitor(Opcodes.ASM9) {
        override fun visitAnnotation(
            descriptor: String?,
            visible: Boolean,
        ): AnnotationVisitor? {
            addDescriptor(descriptor, refs)
            return AnnotationRefVisitor(refs)
        }

        override fun visitTypeAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String?,
            visible: Boolean,
        ): AnnotationVisitor? {
            addDescriptor(descriptor, refs)
            return AnnotationRefVisitor(refs)
        }

        override fun visitParameterAnnotation(
            parameter: Int,
            descriptor: String?,
            visible: Boolean,
        ): AnnotationVisitor? {
            addDescriptor(descriptor, refs)
            return AnnotationRefVisitor(refs)
        }

        override fun visitAnnotationDefault(): AnnotationVisitor = AnnotationRefVisitor(refs)

        override fun visitTypeInsn(
            opcode: Int,
            type: String?,
        ) {
            type?.let { addInternal(it, refs) }
        }

        override fun visitFieldInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?,
        ) {
            owner?.let { addInternal(it, refs) }
            addDescriptor(descriptor, refs)
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?,
            isInterface: Boolean,
        ) {
            owner?.let { addInternal(it, refs) }
            addDescriptor(descriptor, refs)
        }

        override fun visitInvokeDynamicInsn(
            name: String?,
            descriptor: String?,
            bootstrapMethodHandle: Handle?,
            vararg bootstrapMethodArguments: Any?,
        ) {
            addDescriptor(descriptor, refs)
            bootstrapMethodHandle?.let { addHandle(it, refs) }
            for (arg in bootstrapMethodArguments) {
                when (arg) {
                    is Type -> addType(arg, refs)
                    is Handle -> addHandle(arg, refs)
                }
            }
        }

        override fun visitLdcInsn(value: Any?) {
            when (value) {
                is Type -> addType(value, refs)
                is Handle -> addHandle(value, refs)
            }
        }

        override fun visitMultiANewArrayInsn(
            descriptor: String?,
            numDimensions: Int,
        ) {
            addDescriptor(descriptor, refs)
        }

        override fun visitTryCatchBlock(
            start: Label?,
            end: Label?,
            handler: Label?,
            type: String?,
        ) {
            type?.let { addInternal(it, refs) }
        }

        override fun visitLocalVariable(
            name: String?,
            descriptor: String?,
            signature: String?,
            start: Label?,
            end: Label?,
            index: Int,
        ) {
            addDescriptor(descriptor, refs)
            parseSignature(signature, refs)
        }
    }

    private class AnnotationRefVisitor(
        private val refs: MutableSet<String>,
    ) : AnnotationVisitor(Opcodes.ASM9) {
        override fun visit(
            name: String?,
            value: Any?,
        ) {
            when (value) {
                is Type -> addType(value, refs)
                is Array<*> -> value.forEach { if (it is Type) addType(it, refs) }
            }
        }

        override fun visitEnum(
            name: String?,
            descriptor: String?,
            value: String?,
        ) {
            addDescriptor(descriptor, refs)
        }

        override fun visitAnnotation(
            name: String?,
            descriptor: String?,
        ): AnnotationVisitor {
            addDescriptor(descriptor, refs)
            return this
        }

        override fun visitArray(name: String?): AnnotationVisitor = this
    }

    private fun addHandle(
        handle: Handle,
        refs: MutableSet<String>,
    ) {
        addInternal(handle.owner, refs)
        addDescriptor(handle.desc, refs)
    }

    private fun addType(
        type: Type,
        refs: MutableSet<String>,
    ) {
        when (type.sort) {
            Type.OBJECT -> addInternal(type.internalName, refs)
            Type.ARRAY -> addType(type.elementType, refs)
            Type.METHOD -> {
                addType(type.returnType, refs)
                type.argumentTypes.forEach { addType(it, refs) }
            }
        }
    }

    private fun addDescriptor(
        descriptor: String?,
        refs: MutableSet<String>,
    ) {
        if (descriptor.isNullOrEmpty()) return
        try {
            when {
                descriptor.startsWith("(") -> addType(Type.getMethodType(descriptor), refs)
                else -> addType(Type.getType(descriptor), refs)
            }
        } catch (_: IllegalArgumentException) {
            // Malformed descriptor — ignore
        }
    }

    private fun addInternal(
        internalName: String,
        refs: MutableSet<String>,
    ) {
        if (internalName.isEmpty()) return
        // Array types as type-insn operand: "[Ljava/lang/String;"
        if (internalName.startsWith("[")) {
            addDescriptor(internalName, refs)
            return
        }
        refs.add(internalName.replace('/', '.'))
    }

    /**
     * Best-effort extraction of class names from a generic signature string.
     * Covers cases where erasure hides a type that still appears in the signature.
     */
    private fun parseSignature(
        signature: String?,
        refs: MutableSet<String>,
    ) {
        if (signature.isNullOrEmpty()) return
        var i = 0
        while (i < signature.length) {
            if (signature[i] == 'L') {
                val end = signature.indexOf(';', i)
                if (end < 0) break
                val raw = signature.substring(i + 1, end)
                // Drop type arguments: "java/util/List<Ljava/lang/String>"
                val base = raw.substringBefore('<')
                if (base.isNotEmpty() && !base.startsWith("T")) {
                    refs.add(base.replace('/', '.'))
                }
                i = end + 1
            } else {
                i++
            }
        }
    }
}
