package dev.nucleusframework.nucleus.desktop.application.internal.analyzer.detectors

import dev.nucleusframework.nucleus.desktop.application.internal.analyzer.MethodSignature
import dev.nucleusframework.nucleus.desktop.application.internal.analyzer.ReflectionEntry
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Detects `MethodHandles.Lookup.findVirtual/findStatic/findGetter/findSetter` calls
 * with traceable target class and string literal method/field names.
 *
 * Typical bytecode pattern:
 * ```
 * LDC Type com/example/Foo     // target class
 * LDC "methodName"             // member name
 * ... MethodType args ...
 * INVOKEVIRTUAL Lookup.findVirtual
 * ```
 *
 * The detector tracks both the most recent class type (from `LDC Type`) and string
 * constant to resolve the target class and member name.
 */
internal object MethodHandleDetector {
    private val FIND_METHOD_NAMES =
        setOf(
            "findVirtual",
            "findStatic",
            "findSpecial",
        )

    private val FIND_FIELD_NAMES =
        setOf(
            "findGetter",
            "findSetter",
            "findStaticGetter",
            "findStaticSetter",
        )

    fun detect(classBytes: ByteArray): Set<ReflectionEntry> {
        val entries = mutableSetOf<ReflectionEntry>()
        val reader = ClassReader(classBytes)
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor =
                    object : MethodVisitor(Opcodes.ASM9) {
                        // Track the most recent class type from LDC (e.g., LDC Type com/example/Foo)
                        private var lastClassType: String? = null

                        // Track local variable slots that hold class types
                        private val localClassTypes = mutableMapOf<Int, String>()
                        private var lastStringConstant: String? = null
                        private val localStrings = mutableMapOf<Int, String>()

                        override fun visitCode() {
                            lastClassType = null
                            localClassTypes.clear()
                            lastStringConstant = null
                            localStrings.clear()
                        }

                        override fun visitLdcInsn(value: Any?) {
                            when (value) {
                                is Type -> {
                                    if (value.sort == Type.OBJECT) {
                                        lastClassType = value.className
                                    }
                                    lastStringConstant = null
                                }
                                is String -> {
                                    lastStringConstant = value
                                }
                                else -> {
                                    lastStringConstant = null
                                }
                            }
                        }

                        override fun visitVarInsn(
                            opcode: Int,
                            varIndex: Int,
                        ) {
                            when (opcode) {
                                Opcodes.ASTORE -> {
                                    if (lastClassType != null) {
                                        localClassTypes[varIndex] = lastClassType!!
                                    }
                                    if (lastStringConstant != null) {
                                        localStrings[varIndex] = lastStringConstant!!
                                    }
                                    lastClassType = null
                                    lastStringConstant = null
                                }
                                Opcodes.ALOAD -> {
                                    lastClassType = localClassTypes[varIndex] ?: lastClassType
                                    lastStringConstant = localStrings[varIndex]
                                }
                                else -> {
                                    lastStringConstant = null
                                }
                            }
                        }

                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            if (opcode == Opcodes.INVOKEVIRTUAL &&
                                owner == "java/lang/invoke/MethodHandles\$Lookup"
                            ) {
                                val targetClass = lastClassType
                                val memberName = lastStringConstant

                                if (targetClass != null) {
                                    when {
                                        name in FIND_METHOD_NAMES && memberName != null -> {
                                            entries.add(
                                                ReflectionEntry(
                                                    type = targetClass,
                                                    methods = setOf(MethodSignature(memberName)),
                                                ),
                                            )
                                        }
                                        name in FIND_FIELD_NAMES && memberName != null -> {
                                            entries.add(
                                                ReflectionEntry(
                                                    type = targetClass,
                                                    fields = setOf(memberName),
                                                ),
                                            )
                                        }
                                        name == "findConstructor" -> {
                                            entries.add(
                                                ReflectionEntry(
                                                    type = targetClass,
                                                    methods = setOf(MethodSignature("<init>")),
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                            lastClassType = null
                            lastStringConstant = null
                        }

                        override fun visitInsn(opcode: Int) {
                            if (opcode != Opcodes.DUP && opcode != Opcodes.DUP2) {
                                lastStringConstant = null
                            }
                        }

                        override fun visitFieldInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                        ) {
                            lastStringConstant = null
                        }

                        override fun visitIntInsn(
                            opcode: Int,
                            operand: Int,
                        ) {
                            lastStringConstant = null
                        }

                        override fun visitTypeInsn(
                            opcode: Int,
                            type: String,
                        ) {
                            lastStringConstant = null
                        }

                        override fun visitJumpInsn(
                            opcode: Int,
                            label: org.objectweb.asm.Label,
                        ) {
                            // Don't clear — conditional patterns
                        }
                    }
            },
            0,
        )

        return entries
    }
}
