/*
 * Copyright 2020-2026 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Rewrites `System.load(String)` and `Runtime.load(String)` call sites in dependency JAR classes
 * to route through [SandboxMarkers.SHIM_OWNER]`NucleusSandboxLoader.load(String)`, so that
 * extract-and-load native libraries in sandboxed/store distributions transparently load the signed
 * bundled copy instead of a temp-extracted file (issue #317).
 *
 * - `INVOKESTATIC java/lang/System.load (Ljava/lang/String;)V` → `INVOKESTATIC <shim>.load (Ljava/lang/String;)V`
 * - `INVOKEVIRTUAL java/lang/Runtime.load (Ljava/lang/String;)V` → drop the `Runtime` receiver
 *   (`SWAP` + `POP`) then `INVOKESTATIC <shim>.load (Ljava/lang/String;)V`.
 *
 * `System.loadLibrary` / `Runtime.loadLibrary` are intentionally **not** rewritten —
 * `loadLibrary`-first loaders already work via `java.library.path` in the sandboxed pipeline.
 *
 * `COMPUTE_MAXS` is sufficient (only stack-neutral SWAP+POP inserted; no new locals, no control
 * flow or frame changes). Reflection-based `System.load` calls escape rewriting — documented gap.
 */
internal object SandboxBytecodeRewriter {
    /**
     * @return rewritten class bytes, or the original bytes if no `load` call site was found.
     */
    fun rewriteSystemLoadCalls(input: ByteArray): ByteArray {
        val reader = ClassReader(input)
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
        var changed = false
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val inner = super.visitMethod(access, name, descriptor, signature, exceptions)
                    return LoadMethodRewriter(inner) { changed = true }
                }
            },
            0,
        )
        return if (changed) writer.toByteArray() else input
    }

    /**
     * Returns true if the class references `System.load(String)` or `Runtime.load(String)`.
     * Used to skip the (rare) ASM pass on classes that have nothing to rewrite.
     */
    fun hasLoadCallSite(input: ByteArray): Boolean {
        val reader = ClassReader(input)
        var found = false
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    methodName: String?,
                    methodDescriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String?,
                            name: String?,
                            descriptor: String?,
                            isInterface: Boolean,
                        ) {
                            if (!isInterface && descriptor == SandboxMarkers.LOAD_DESC) {
                                if (opcode == Opcodes.INVOKESTATIC && owner == "java/lang/System" && name == "load") {
                                    found = true
                                } else if (
                                    opcode == Opcodes.INVOKEVIRTUAL && owner == "java/lang/Runtime" && name == "load"
                                ) {
                                    found = true
                                }
                            }
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                        }
                    }
                }
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return found
    }
}

/** MethodVisitor that performs the actual call-site rewrite. */
private class LoadMethodRewriter(
    inner: MethodVisitor,
    private val onChanged: () -> Unit,
) : MethodVisitor(Opcodes.ASM9, inner) {
    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean,
    ) {
        if (!isInterface && descriptor == SandboxMarkers.LOAD_DESC) {
            if (opcode == Opcodes.INVOKESTATIC && owner == "java/lang/System" && name == "load") {
                super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    SandboxMarkers.SHIM_OWNER,
                    "load",
                    SandboxMarkers.LOAD_DESC,
                    false,
                )
                onChanged()
                return
            }
            if (opcode == Opcodes.INVOKEVIRTUAL && owner == "java/lang/Runtime" && name == "load") {
                // Stack at the call site: [receiver, arg]. Drop the receiver while keeping arg:
                //   SWAP  -> [arg, receiver]
                //   POP   -> [arg]
                // then invoke the static shim.
                super.visitInsn(Opcodes.SWAP)
                super.visitInsn(Opcodes.POP)
                super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    SandboxMarkers.SHIM_OWNER,
                    "load",
                    SandboxMarkers.LOAD_DESC,
                    false,
                )
                onChanged()
                return
            }
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }
}
