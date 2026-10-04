package dev.nucleusframework.desktop.application.internal.transforms

import org.gradle.api.Project
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * Gives Compose's render layers a content version, which is what the Tao
 * backend's partial redraw (#755) reads to tell which layers changed since
 * the last frame.
 *
 * Compose keeps a layer's "needs re-recording" flag private
 * (`GraphicsLayerOwnerLayer.isDirty`) and offers no hook into it, so this
 * artifact transform adds a counter to `GraphicsLayerOwnerLayer` in
 * `ui-desktop`, bumped at the entry of `invalidate()` (content) and
 * `triggerRepaint()` (properties and placement), plus a generated
 * `androidx.compose.ui.node.NucleusLayerDamage.contentVersion(Object)` the
 * runtime reads it through.
 *
 * In `ui-graphics`, `GraphicsLayer.record` and `GraphicsLayer.draw` call
 * hooks (`androidx.compose.ui.graphics.layer.NucleusGraphicsLayerHooks`):
 * that is how the runtime learns which layer draws an explicit graphics
 * layer — `rememberGraphicsLayer()` recorded and drawn with `drawLayer` —
 * and whether it was recorded by that same layer, the case it can attribute.
 *
 * Unlike the LCD patch this one is optional: a Compose layout it does not
 * recognise is handed through untouched, and the runtime — which then finds
 * no `NucleusLayerDamage` — keeps repainting whole frames. Plain bytecode, so
 * HotSpot, ProGuard and GraalVM native-image all see the same thing.
 */
@CacheableTransform
internal abstract class LayerDamageTransform : TransformAction<TransformParameters.None> {
    /** The jar being transformed; only `ui-desktop-*.jar` and `ui-graphics-desktop-*.jar` are rewritten. */
    @get:Classpath
    @get:InputArtifact
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        val isJar = input.extension == "jar"
        when {
            isJar && input.name.startsWith(UI_ARTIFACT_PREFIX) && LayerDamageClassPatcher.hasLayerClass(input) ->
                // A layer class whose methods moved comes out as an identical copy.
                LayerDamageClassPatcher.patchJar(input, patchedOutput(outputs, input))
            isJar && input.name.startsWith(UI_GRAPHICS_ARTIFACT_PREFIX) &&
                GraphicsLayerHooksPatcher.hasGraphicsLayerClass(input) ->
                GraphicsLayerHooksPatcher.patchJar(input, patchedOutput(outputs, input))
            // Identity: hand the original artifact through without copying.
            else -> outputs.file(inputArtifact)
        }
    }

    private fun patchedOutput(
        outputs: TransformOutputs,
        input: File,
    ): File = outputs.file("${input.nameWithoutExtension}$PATCHED_JAR_SUFFIX.jar")
}

private const val UI_ARTIFACT_PREFIX = "ui-desktop-"
private const val UI_GRAPHICS_ARTIFACT_PREFIX = "ui-graphics-desktop-"
private const val PATCHED_JAR_SUFFIX = "-nucleus-damage"

/**
 * Marks jars run through [LayerDamageTransform]. Runtime classpaths request
 * `true`, plain jars default to `false`, and the transform bridges the two.
 */
private val LAYER_DAMAGE_PATCHED: Attribute<Boolean> =
    Attribute.of("dev.nucleusframework.layer-damage", Boolean::class.javaObjectType)

/** Gradle property that skips the build-time patch when set to `false`. */
private const val PATCH_OPT_OUT_PROPERTY = "nucleus.tao.partialRedraw.patch"

/**
 * Registers [LayerDamageTransform] and requests the patched variant on every
 * non-test runtime classpath of [project] — the same classpaths, with the same
 * exclusions, as [configureLcdTextDefaultTransform].
 *
 * Build-time opt-out: `-Pnucleus.tao.partialRedraw.patch=false` (the runtime
 * `-Dnucleus.tao.partialRedraw=false` disables partial redraw on a patched
 * classpath).
 */
internal fun configureLayerDamageTransform(project: Project) {
    val enabled =
        project.providers
            .gradleProperty(PATCH_OPT_OUT_PROPERTY)
            .map { it != "false" }
            .getOrElse(true)
    if (!enabled) return

    project.dependencies.registerTransform(LayerDamageTransform::class.java) { spec ->
        spec.from.attribute(LAYER_DAMAGE_PATCHED, false)
        spec.to.attribute(LAYER_DAMAGE_PATCHED, true)
    }
    requestPatchedRuntimeVariant(project, LAYER_DAMAGE_PATCHED)
}

/**
 * The ASM surgery for [LayerDamageTransform]: a counter on
 * `GraphicsLayerOwnerLayer` and the static reader the runtime calls.
 */
internal object LayerDamageClassPatcher {
    private const val LAYER = "androidx/compose/ui/node/GraphicsLayerOwnerLayer"
    private const val LAYER_ENTRY = "$LAYER.class"
    private const val ACCESS = "androidx/compose/ui/node/NucleusLayerDamage"
    private const val ACCESS_ENTRY = "$ACCESS.class"
    private const val VERSION_FIELD = "nucleus\$contentVersion"
    private val BUMPED_METHODS = setOf("invalidate", "triggerRepaint")

    /** Whether [jar] holds an unpatched `GraphicsLayerOwnerLayer`. */
    fun hasLayerClass(jar: File): Boolean =
        JarFile(jar).use { it.getJarEntry(LAYER_ENTRY) != null && it.getJarEntry(ACCESS_ENTRY) == null }

    /**
     * Rewrites [input] into [output]. Returns `false` — with [output] an
     * unmodified copy — when the layer class or one of the bumped methods is
     * missing, or the jar was already patched.
     */
    fun patchJar(
        input: File,
        output: File,
    ): Boolean {
        val patchedLayer =
            JarFile(input).use { jar ->
                val entry = jar.getJarEntry(LAYER_ENTRY) ?: return@use null
                if (jar.getJarEntry(ACCESS_ENTRY) != null) return@use null
                patchLayer(jar.getInputStream(entry).use { it.readBytes() })
            }
        JarFile(input).use { jar ->
            JarOutputStream(output.outputStream().buffered()).use { out ->
                for (entry in jar.entries()) {
                    val bytes = jar.getInputStream(entry).use { it.readBytes() }
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(if (entry.name == LAYER_ENTRY && patchedLayer != null) patchedLayer else bytes)
                    out.closeEntry()
                }
                if (patchedLayer != null) {
                    out.putNextEntry(ZipEntry(ACCESS_ENTRY))
                    out.write(generateAccess())
                    out.closeEntry()
                }
            }
        }
        return patchedLayer != null
    }

    /**
     * Adds the counter and bumps it at the entry of every method in
     * [BUMPED_METHODS]. Returns null when one of them is missing.
     */
    fun patchLayer(classBytes: ByteArray): ByteArray? {
        val reader = ClassReader(classBytes)
        val writer = ClassWriter(reader, 0)
        val bumped = mutableSetOf<String>()
        val visitor =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                    if (name !in BUMPED_METHODS || descriptor != "()V" || access and Opcodes.ACC_STATIC != 0) {
                        return mv
                    }
                    bumped += name
                    return object : MethodVisitor(Opcodes.ASM9, mv) {
                        override fun visitCode() {
                            super.visitCode()
                            // this.nucleus$contentVersion++ — needs three stack
                            // slots, reserved in visitMaxs below.
                            visitVarInsn(Opcodes.ALOAD, 0)
                            visitInsn(Opcodes.DUP)
                            visitFieldInsn(Opcodes.GETFIELD, LAYER, VERSION_FIELD, "I")
                            visitInsn(Opcodes.ICONST_1)
                            visitInsn(Opcodes.IADD)
                            visitFieldInsn(Opcodes.PUTFIELD, LAYER, VERSION_FIELD, "I")
                        }

                        override fun visitMaxs(
                            maxStack: Int,
                            maxLocals: Int,
                        ) {
                            super.visitMaxs(maxOf(maxStack, 3), maxLocals)
                        }
                    }
                }

                override fun visitEnd() {
                    cv
                        .visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC, VERSION_FIELD, "I", null, null)
                        .visitEnd()
                    super.visitEnd()
                }
            }
        reader.accept(visitor, 0)
        return if (bumped == BUMPED_METHODS) writer.toByteArray() else null
    }

    // Generates:
    //   public final class NucleusLayerDamage {
    //       private NucleusLayerDamage() {}
    //       public static int contentVersion(Object layer) {
    //           return layer instanceof GraphicsLayerOwnerLayer
    //               ? ((GraphicsLayerOwnerLayer) layer).nucleus$contentVersion : -1;
    //       }
    //   }
    private fun generateAccess(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            ACCESS,
            null,
            "java/lang/Object",
            null,
        )
        cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "contentVersion",
            "(Ljava/lang/Object;)I",
            null,
            null,
        ).apply {
            val notALayer = Label()
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitTypeInsn(Opcodes.INSTANCEOF, LAYER)
            visitJumpInsn(Opcodes.IFEQ, notALayer)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitTypeInsn(Opcodes.CHECKCAST, LAYER)
            visitFieldInsn(Opcodes.GETFIELD, LAYER, VERSION_FIELD, "I")
            visitInsn(Opcodes.IRETURN)
            visitLabel(notALayer)
            visitInsn(Opcodes.ICONST_M1)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }
}

/**
 * The ASM surgery for the `ui-graphics` half of [LayerDamageTransform]:
 * `GraphicsLayer.record` reports its start and end, `GraphicsLayer.draw` the
 * layer it is drawn into, through static hooks the runtime installs.
 */
internal object GraphicsLayerHooksPatcher {
    private const val GRAPHICS_LAYER = "androidx/compose/ui/graphics/layer/GraphicsLayer"
    private const val GRAPHICS_LAYER_ENTRY = "$GRAPHICS_LAYER.class"
    private const val HOOKS = "androidx/compose/ui/graphics/layer/NucleusGraphicsLayerHooks"
    private const val HOOKS_ENTRY = "$HOOKS.class"
    private const val RECORD_PREFIX = "record"
    private const val RECORD_DESC_SUFFIX = "Lkotlin/jvm/functions/Function1;)V"
    private const val DRAW = "draw\$ui_graphics"
    private const val DRAW_DESC = "(Landroidx/compose/ui/graphics/Canvas;L$GRAPHICS_LAYER;)V"
    private const val CONSUMER = "java/util/function/Consumer"
    private const val BI_CONSUMER = "java/util/function/BiConsumer"

    /** Whether [jar] holds an unpatched `GraphicsLayer`. */
    fun hasGraphicsLayerClass(jar: File): Boolean =
        JarFile(jar).use { it.getJarEntry(GRAPHICS_LAYER_ENTRY) != null && it.getJarEntry(HOOKS_ENTRY) == null }

    /**
     * Rewrites [input] into [output]. Returns `false` — with [output] an
     * unmodified copy — when `record` or `draw` cannot be found.
     */
    fun patchJar(
        input: File,
        output: File,
    ): Boolean {
        val patched =
            JarFile(input).use { jar ->
                val entry = jar.getJarEntry(GRAPHICS_LAYER_ENTRY) ?: return@use null
                patchGraphicsLayer(jar.getInputStream(entry).use { it.readBytes() })
            }
        JarFile(input).use { jar ->
            JarOutputStream(output.outputStream().buffered()).use { out ->
                for (entry in jar.entries()) {
                    val bytes = jar.getInputStream(entry).use { it.readBytes() }
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(if (entry.name == GRAPHICS_LAYER_ENTRY && patched != null) patched else bytes)
                    out.closeEntry()
                }
                if (patched != null) {
                    out.putNextEntry(ZipEntry(HOOKS_ENTRY))
                    out.write(generateHooks())
                    out.closeEntry()
                }
            }
        }
        return patched != null
    }

    /**
     * `record…(Density, LayoutDirection, IntSize, block)` calls
     * `onRecordStart(this)` first and `onRecordEnd(this)` before returning;
     * `draw$ui_graphics(canvas, parentLayer)` calls `onDraw(this, parentLayer)`
     * first. Returns null when either method is missing.
     */
    fun patchGraphicsLayer(classBytes: ByteArray): ByteArray? {
        val reader = ClassReader(classBytes)
        val writer = ClassWriter(reader, 0)
        var record = false
        var draw = false
        val visitor =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                    val instance = access and Opcodes.ACC_STATIC == 0
                    return when {
                        instance && access and Opcodes.ACC_PUBLIC != 0 && name.startsWith(RECORD_PREFIX) &&
                            descriptor.endsWith(RECORD_DESC_SUFFIX) -> {
                            record = true
                            RecordHooks(mv)
                        }
                        instance && name == DRAW && descriptor == DRAW_DESC -> {
                            draw = true
                            DrawHook(mv)
                        }
                        else -> mv
                    }
                }
            }
        reader.accept(visitor, 0)
        return if (record && draw) writer.toByteArray() else null
    }

    private class RecordHooks(
        mv: MethodVisitor,
    ) : MethodVisitor(Opcodes.ASM9, mv) {
        override fun visitCode() {
            super.visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "onRecordStart", "(Ljava/lang/Object;)V", false)
        }

        override fun visitInsn(opcode: Int) {
            if (opcode == Opcodes.RETURN) {
                super.visitVarInsn(Opcodes.ALOAD, 0)
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "onRecordEnd", "(Ljava/lang/Object;)V", false)
            }
            super.visitInsn(opcode)
        }

        override fun visitMaxs(
            maxStack: Int,
            maxLocals: Int,
        ) {
            super.visitMaxs(maxOf(maxStack, 1), maxLocals)
        }
    }

    private class DrawHook(
        mv: MethodVisitor,
    ) : MethodVisitor(Opcodes.ASM9, mv) {
        override fun visitCode() {
            super.visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "onDraw", "(Ljava/lang/Object;Ljava/lang/Object;)V", false)
        }

        override fun visitMaxs(
            maxStack: Int,
            maxLocals: Int,
        ) {
            super.visitMaxs(maxOf(maxStack, 2), maxLocals)
        }
    }

    // Generates:
    //   public final class NucleusGraphicsLayerHooks {
    //       public static volatile Consumer recordStart, recordEnd;
    //       public static volatile BiConsumer draw;
    //       public static void onRecordStart(Object layer) { Consumer c = recordStart; if (c != null) c.accept(layer); }
    //       public static void onRecordEnd(Object layer) { …recordEnd… }
    //       public static void onDraw(Object layer, Object parent) { BiConsumer c = draw; if (c != null) c.accept(layer, parent); }
    //   }
    private fun generateHooks(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER, HOOKS, null, "java/lang/Object", null)
        val fieldAccess = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_VOLATILE
        cw.visitField(fieldAccess, "recordStart", "L$CONSUMER;", null, null).visitEnd()
        cw.visitField(fieldAccess, "recordEnd", "L$CONSUMER;", null, null).visitEnd()
        cw.visitField(fieldAccess, "draw", "L$BI_CONSUMER;", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        generateRelay(cw, "onRecordStart", "recordStart", arity = 1)
        generateRelay(cw, "onRecordEnd", "recordEnd", arity = 1)
        generateRelay(cw, "onDraw", "draw", arity = 2)
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun generateRelay(
        cw: ClassWriter,
        method: String,
        field: String,
        arity: Int,
    ) {
        val type = if (arity == 1) CONSUMER else BI_CONSUMER
        val args = "Ljava/lang/Object;".repeat(arity)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, method, "($args)V", null, null).apply {
            val none = Label()
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, HOOKS, field, "L$type;")
            visitVarInsn(Opcodes.ASTORE, arity)
            visitVarInsn(Opcodes.ALOAD, arity)
            visitJumpInsn(Opcodes.IFNULL, none)
            visitVarInsn(Opcodes.ALOAD, arity)
            for (i in 0 until arity) visitVarInsn(Opcodes.ALOAD, i)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, type, "accept", "($args)V", true)
            visitLabel(none)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }
}
