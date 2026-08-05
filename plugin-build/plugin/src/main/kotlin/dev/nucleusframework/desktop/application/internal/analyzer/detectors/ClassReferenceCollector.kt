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
 * Collects class types **used** by a class file — super/interfaces, field/method descriptors,
 * instruction owners/types, handles, and annotations — **excluding the declaring class**.
 *
 * Deliberately ignores catalog attributes (`InnerClasses`, nest host/members, permitted
 * subclasses): those list related types without being use-sites and would inflate the
 * "referenced" set, hiding true orphans (false negatives for #441).
 */
internal object ClassReferenceCollector {
    /**
     * @return FQCNs referenced by [classBytes], never including the declaring class.
     */
    fun collect(classBytes: ByteArray): Set<String> {
        val refs = mutableSetOf<String>()
        var selfInternal: String? = null
        try {
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
                0,
            )
        } catch (_: IllegalArgumentException) {
            // Unsupported class file version (e.g. JDK 25+) — same guard as BytecodeAnalyzer
            return emptySet()
        }
        val self = selfInternal?.replace('/', '.')
        if (self != null) refs.remove(self)
        return refs
    }

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
        if (internalName.startsWith("[")) {
            addDescriptor(internalName, refs)
            return
        }
        refs.add(internalName.replace('/', '.'))
    }

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
