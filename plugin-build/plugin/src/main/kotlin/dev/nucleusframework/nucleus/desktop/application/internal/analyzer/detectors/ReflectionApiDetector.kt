package dev.nucleusframework.nucleus.desktop.application.internal.analyzer.detectors

import dev.nucleusframework.nucleus.desktop.application.internal.analyzer.MethodSignature
import dev.nucleusframework.nucleus.desktop.application.internal.analyzer.ReflectionEntry
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Detects reflective access via:
 * - `cls.getMethod("name")`, `cls.getDeclaredField("name")`, etc.
 * - `.class` literal references (LDC of Type.OBJECT) when used before reflection APIs
 * - `Class.forName("name").newInstance()` and similar patterns
 *
 * Tracks both string constants and class constants through local variable stores/loads.
 */
@Suppress("CyclomaticComplexMethod")
internal object ReflectionApiDetector {
    private val METHOD_LOOKUPS = setOf("getMethod", "getDeclaredMethod")
    private val FIELD_LOOKUPS = setOf("getField", "getDeclaredField")
    private val CONSTRUCTOR_LOOKUPS = setOf("getConstructor", "getDeclaredConstructor")
    private val ALL_MEMBER_LOOKUPS =
        METHOD_LOOKUPS + FIELD_LOOKUPS + CONSTRUCTOR_LOOKUPS +
            setOf("getMethods", "getDeclaredMethods", "getFields", "getDeclaredFields")

    fun detect(classBytes: ByteArray): Set<ReflectionEntry> {
        val results = mutableSetOf<ReflectionEntry>()
        val reader = ClassReader(classBytes)
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor = MethodReflectionVisitor(results)
            },
            0,
        )
        return results
    }

    private class MethodReflectionVisitor(
        private val results: MutableSet<ReflectionEntry>,
    ) : MethodVisitor(Opcodes.ASM9) {
        // Track string constants in local variables
        private val localStrings = mutableMapOf<Int, String>()

        // Track class references (from .class literals or Class.forName results) in local variables
        private val localClasses = mutableMapOf<Int, String>()

        private var stackString: String? = null
        private var stackClass: String? = null

        override fun visitLdcInsn(value: Any?) {
            when (value) {
                is String -> {
                    stackString = value
                    stackClass = null
                }
                is Type -> {
                    if (value.sort == Type.OBJECT) {
                        // .class literal, e.g. MyClass.class → LDC Type("Lcom/example/MyClass;")
                        stackClass = value.className
                        stackString = null
                    }
                }
                else -> {
                    stackString = null
                    stackClass = null
                }
            }
        }

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) {
            when (opcode) {
                Opcodes.ASTORE -> {
                    stackString?.let { localStrings[varIndex] = it }
                    stackClass?.let { localClasses[varIndex] = it }
                    stackString = null
                    stackClass = null
                }
                Opcodes.ALOAD -> {
                    stackString = localStrings[varIndex]
                    stackClass = localClasses[varIndex]
                }
                else -> {
                    stackString = null
                    stackClass = null
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
            // Track Class.forName results
            if (opcode == Opcodes.INVOKESTATIC &&
                owner == "java/lang/Class" &&
                name == "forName"
            ) {
                val className = stackString
                if (className != null && isValidClassName(className)) {
                    stackClass = className
                } else {
                    stackClass = null
                }
                stackString = null
                return
            }

            // Track .newInstance() on a known class
            if (opcode == Opcodes.INVOKEVIRTUAL &&
                owner == "java/lang/Class" &&
                name == "newInstance"
            ) {
                val cls = stackClass
                if (cls != null) {
                    results.add(
                        ReflectionEntry(
                            type = cls,
                            methods = setOf(MethodSignature("<init>")),
                        ),
                    )
                }
                stackString = null
                stackClass = null
                return
            }

            // Check for reflection API calls on Class
            if (opcode == Opcodes.INVOKEVIRTUAL && owner == "java/lang/Class" && name in ALL_MEMBER_LOOKUPS) {
                val targetClass = stackClass
                val memberName = stackString

                if (targetClass != null) {
                    when {
                        name in METHOD_LOOKUPS && memberName != null ->
                            results.add(
                                ReflectionEntry(
                                    type = targetClass,
                                    methods = setOf(MethodSignature(memberName)),
                                ),
                            )
                        name in FIELD_LOOKUPS && memberName != null ->
                            results.add(
                                ReflectionEntry(
                                    type = targetClass,
                                    fields = setOf(memberName),
                                ),
                            )
                        name in CONSTRUCTOR_LOOKUPS ->
                            results.add(
                                ReflectionEntry(
                                    type = targetClass,
                                    methods = setOf(MethodSignature("<init>")),
                                ),
                            )
                        name == "getMethods" || name == "getDeclaredMethods" ->
                            results.add(
                                ReflectionEntry(type = targetClass, allDeclaredMethods = true),
                            )
                        name == "getFields" || name == "getDeclaredFields" ->
                            results.add(
                                ReflectionEntry(type = targetClass, allDeclaredFields = true),
                            )
                    }
                }
            }

            stackString = null
            stackClass = null
        }

        override fun visitInsn(opcode: Int) {
            if (opcode != Opcodes.DUP && opcode != Opcodes.DUP2) {
                stackString = null
                stackClass = null
            }
        }

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) {
            stackString = null
            stackClass = null
        }

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) {
            stackString = null
            stackClass = null
        }

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) {
            stackString = null
            stackClass = null
        }

        override fun visitJumpInsn(
            opcode: Int,
            label: org.objectweb.asm.Label,
        ) {
            // Don't clear on jumps — reflection is often in branched code
        }
    }
}
