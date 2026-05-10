package dev.nucleusframework.nucleus.desktop.application.internal.analyzer.detectors

import dev.nucleusframework.nucleus.desktop.application.internal.analyzer.ResourcePattern
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Detects `ResourceBundle.getBundle("literal")` calls and produces resource bundle entries.
 *
 * Tracks string constants through local variable ASTORE/ALOAD pairs to handle
 * patterns where the bundle name is stored in a variable before the getBundle call.
 */
internal object ResourceBundleDetector {
    fun detect(classBytes: ByteArray): Set<ResourcePattern> {
        val patterns = mutableSetOf<ResourcePattern>()
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
                        private var stackString: String? = null
                        private val localStrings = mutableMapOf<Int, String>()

                        override fun visitLdcInsn(value: Any?) {
                            stackString = value as? String
                        }

                        override fun visitVarInsn(
                            opcode: Int,
                            varIndex: Int,
                        ) {
                            when (opcode) {
                                Opcodes.ASTORE -> {
                                    val str = stackString
                                    if (str != null) {
                                        localStrings[varIndex] = str
                                    }
                                    stackString = null
                                }
                                Opcodes.ALOAD -> {
                                    stackString = localStrings[varIndex]
                                }
                                else -> stackString = null
                            }
                        }

                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            if (opcode == Opcodes.INVOKESTATIC &&
                                owner == "java/util/ResourceBundle" &&
                                name == "getBundle" &&
                                stackString != null
                            ) {
                                patterns.add(ResourcePattern(bundle = stackString))
                            }
                            stackString = null
                        }

                        override fun visitInsn(opcode: Int) {
                            if (opcode != Opcodes.DUP && opcode != Opcodes.DUP2) {
                                stackString = null
                            }
                        }

                        override fun visitFieldInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                        ) {
                            stackString = null
                        }

                        override fun visitIntInsn(
                            opcode: Int,
                            operand: Int,
                        ) {
                            stackString = null
                        }

                        override fun visitTypeInsn(
                            opcode: Int,
                            type: String,
                        ) {
                            stackString = null
                        }

                        override fun visitJumpInsn(
                            opcode: Int,
                            label: org.objectweb.asm.Label,
                        ) {
                            // Don't clear — conditional getBundle patterns
                        }
                    }
            },
            0,
        )
        return patterns
    }
}
