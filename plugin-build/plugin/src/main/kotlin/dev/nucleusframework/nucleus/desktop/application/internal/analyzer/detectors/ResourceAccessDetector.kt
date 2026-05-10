package dev.nucleusframework.nucleus.desktop.application.internal.analyzer.detectors

import dev.nucleusframework.nucleus.desktop.application.internal.analyzer.ResourcePattern
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Detects `getResource("literal")` and `getResourceAsStream("literal")` calls
 * and produces resource glob entries.
 *
 * Tracks string constants through local variable ASTORE/ALOAD pairs to handle:
 * ```java
 * String path = "/config/app.properties";
 * this.getClass().getResourceAsStream(path);
 * ```
 */
internal object ResourceAccessDetector {
    private val RESOURCE_METHODS = setOf("getResource", "getResourceAsStream")

    private val RESOURCE_OWNERS =
        setOf(
            "java/lang/Class",
            "java/lang/ClassLoader",
        )

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
                            if (opcode == Opcodes.INVOKEVIRTUAL &&
                                owner in RESOURCE_OWNERS &&
                                name in RESOURCE_METHODS
                            ) {
                                stackString?.let { s ->
                                    val path = normalizeResourcePath(s)
                                    if (path.isNotEmpty()) {
                                        patterns.add(ResourcePattern(glob = path))
                                    }
                                }
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
                            // Don't clear — conditional resource access patterns
                        }
                    }
            },
            0,
        )
        return patterns
    }

    /**
     * Removes leading slash from absolute resource paths.
     */
    private fun normalizeResourcePath(path: String): String = path.removePrefix("/")
}
