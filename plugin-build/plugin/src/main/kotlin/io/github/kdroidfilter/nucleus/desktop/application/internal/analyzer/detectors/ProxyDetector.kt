package dev.nucleusframework.nucleus.desktop.application.internal.analyzer.detectors

import dev.nucleusframework.nucleus.desktop.application.internal.analyzer.ReflectionEntry
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Detects `Proxy.newProxyInstance(...)` calls and attempts to trace the interface types
 * passed as the second argument.
 *
 * When the interfaces are loaded via `new Class[]{SomeInterface.class}`, the detector
 * can extract the concrete interface names.
 */
internal object ProxyDetector {
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
                        private val recentClasses = mutableListOf<String>()

                        override fun visitCode() {
                            recentClasses.clear()
                        }

                        override fun visitLdcInsn(value: Any?) {
                            if (value is Type && value.sort == Type.OBJECT) {
                                recentClasses.add(value.className)
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
                                owner == "java/lang/reflect/Proxy" &&
                                name == "newProxyInstance"
                            ) {
                                for (cls in recentClasses) {
                                    entries.add(ReflectionEntry(type = cls))
                                }
                            }
                            recentClasses.clear()
                        }
                    }
            },
            0,
        )
        return entries
    }
}
