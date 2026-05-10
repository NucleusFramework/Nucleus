package dev.nucleusframework.nucleus.desktop.application.internal.analyzer.detectors

import dev.nucleusframework.nucleus.desktop.application.internal.analyzer.MethodSignature
import dev.nucleusframework.nucleus.desktop.application.internal.analyzer.ReflectionEntry
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Detects classes annotated with `@kotlinx.serialization.Serializable` and produces
 * reflection entries for the fields and methods that the serialization runtime accesses
 * reflectively (especially in GraalVM native-image where the generated serializer
 * needs to find `INSTANCE`, `serializer()`, and the `$serializer` companion).
 *
 * For sealed class hierarchies used in navigation (e.g. Compose Navigation),
 * each subclass needs reflection access for its `INSTANCE` field and `serializer()` method.
 */
internal object KotlinSerializableDetector {
    private const val SERIALIZABLE_DESC = "Lkotlinx/serialization/Serializable;"

    fun detect(classBytes: ByteArray): Set<ReflectionEntry> {
        val entries = mutableSetOf<ReflectionEntry>()
        val reader = ClassReader(classBytes)
        reader.accept(SerializableClassVisitor(entries), 0)
        return entries
    }

    private class SerializableClassVisitor(
        private val entries: MutableSet<ReflectionEntry>,
    ) : ClassVisitor(Opcodes.ASM9) {
        private var className = ""
        private var isSerializable = false
        private var hasInstanceField = false
        private var hasSerializerMethod = false
        private val fields = mutableSetOf<String>()
        private val methods = mutableSetOf<MethodSignature>()

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

        override fun visitAnnotation(
            descriptor: String,
            visible: Boolean,
        ): AnnotationVisitor? {
            if (descriptor == SERIALIZABLE_DESC) {
                isSerializable = true
            }
            return null
        }

        override fun visitField(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            value: Any?,
        ): FieldVisitor? {
            if (isSerializable) {
                if (name == "INSTANCE") {
                    hasInstanceField = true
                    fields.add("INSTANCE")
                } else if (name == "\$cachedSerializer\$delegate" || name == "Companion") {
                    fields.add(name)
                }
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
            if (isSerializable) {
                if (name == "serializer" && descriptor.startsWith("()")) {
                    hasSerializerMethod = true
                    methods.add(MethodSignature("serializer"))
                } else if (name == "<init>") {
                    val paramTypes =
                        org.objectweb.asm.Type
                            .getArgumentTypes(descriptor)
                            .map { asmTypeToJavaName(it) }
                    methods.add(MethodSignature("<init>", paramTypes))
                }
            }
            return null
        }

        override fun visitEnd() {
            if (isSerializable) {
                entries.add(
                    ReflectionEntry(
                        type = className,
                        fields = fields,
                        methods = methods,
                    ),
                )
                // Emit Companion.serializer() entry for navigation-compose and similar
                // frameworks that reflectively call serializer() on the Companion object
                if ("Companion" in fields) {
                    entries.add(
                        ReflectionEntry(
                            type = "$className\$Companion",
                            methods = setOf(MethodSignature("serializer")),
                        ),
                    )
                }
            }
        }
    }
}
