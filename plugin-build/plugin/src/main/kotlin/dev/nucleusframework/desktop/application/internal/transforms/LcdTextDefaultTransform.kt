package dev.nucleusframework.desktop.application.internal.transforms

import org.gradle.api.Project
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.LibraryElements
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
 * Enables LCD / ClearType text on Windows (Compose issue #875) by patching
 * `FontRasterizationSettings.PlatformDefault` in `ui-text-desktop` at build
 * time.
 *
 * Compose hardcodes grayscale (`FontSmoothing.AntiAlias`) as the Windows
 * default — its own source comments it wants ClearType but cannot query the
 * OS. There is no runtime hook (no CompositionLocal, no system property), so
 * this artifact transform rewrites the single choke point every paragraph
 * falls back to: `FontRasterizationSettings.Companion.getPlatformDefault()`.
 * The original getter is kept (renamed) and a wrapper is generated that, on
 * Windows, returns `SubpixelAntiAlias` settings unless the app opts out with
 * `-Dnucleus.text.lcd=false`; every other OS delegates to the original.
 *
 * The subpixel request alone never causes fringes: Skia only rasterizes LCD
 * glyphs on surfaces whose `SurfaceProps` carry a known pixel geometry, and
 * the Tao backend attaches geometry only to opaque Windows window surfaces
 * (queried from the OS ClearType settings — see `decorated-window-tao`
 * `LcdText.kt`). Transparent windows, popups, and offscreen surfaces keep an
 * unknown geometry and Skia falls back to grayscale there.
 *
 * Because the patch is plain bytecode on the classpath, it needs no runtime
 * reflection and works identically under HotSpot, ProGuard, and GraalVM
 * native-image.
 */
@CacheableTransform
internal abstract class LcdTextDefaultTransform : TransformAction<TransformParameters.None> {
    /** The jar being transformed; only `ui-text-desktop-*.jar` is rewritten. */
    @get:Classpath
    @get:InputArtifact
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        if (!input.name.startsWith(UI_TEXT_ARTIFACT_PREFIX) || input.extension != "jar") {
            // Identity: hand the original artifact through without copying.
            outputs.file(inputArtifact)
            return
        }
        val output = outputs.file("${input.nameWithoutExtension}$PATCHED_JAR_SUFFIX.jar")
        LcdTextClassPatcher.patchJar(input, output)
    }
}

private const val UI_TEXT_ARTIFACT_PREFIX = "ui-text-desktop"
private const val PATCHED_JAR_SUFFIX = "-nucleus-lcd"

/**
 * Marks jars whose `FontRasterizationSettings.PlatformDefault` has been
 * patched by [LcdTextDefaultTransform]. Runtime classpaths request `true`,
 * plain jars default to `false`, and the transform bridges the two.
 */
private val LCD_TEXT_PATCHED: Attribute<Boolean> =
    Attribute.of("dev.nucleusframework.lcd-text-default", Boolean::class.javaObjectType)

/** Gradle property that skips the whole build-time patch when set to `false`. */
private const val PATCH_OPT_OUT_PROPERTY = "nucleus.text.lcd.patch"

/**
 * Registers [LcdTextDefaultTransform] and requests the patched variant on
 * every non-test runtime classpath of [project] (`runtimeClasspath`,
 * `jvmRuntimeClasspath`, …) — which is what `run`, packaging, ProGuard, and
 * the GraalVM native-image classpath all resolve.
 *
 * Configuration exclusions and the KMP jar-variant pinning mirror
 * `registerCleanNativeLibsTransform`, which needed them for exactly this
 * attribute-on-runtimeClasspath pattern: Android configurations resolve
 * dexing directory variants, and the Compose Hot Reload dev classpaths
 * consume custom-usage project variants — both fail resolution when an
 * extra requested attribute is added.
 *
 * Build-time opt-out: `-Pnucleus.text.lcd.patch=false` (the runtime
 * `-Dnucleus.text.lcd=false` only disables the already-patched default).
 */
internal fun configureLcdTextDefaultTransform(project: Project) {
    val enabled =
        project.providers
            .gradleProperty(PATCH_OPT_OUT_PROPERTY)
            .map { it != "false" }
            .getOrElse(true)
    if (!enabled) return

    project.dependencies.registerTransform(LcdTextDefaultTransform::class.java) { spec ->
        spec.from.attribute(LCD_TEXT_PATCHED, false)
        spec.to.attribute(LCD_TEXT_PATCHED, true)
    }

    // KMP desktop runtime classpaths resolve project dependencies to their
    // `classes`/`resources` directory sub-variants, which carry no LCD
    // attribute — requesting it would make artifact selection ambiguous.
    // Pinning the jar LibraryElements restores plain-JVM resolution (same
    // reasoning as registerCleanNativeLibsTransform).
    val isMultiplatform = project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")
    val jarLibraryElements =
        project.objects.named(LibraryElements::class.java, LibraryElements.JAR)

    project.configurations.configureEach { configuration ->
        val name = configuration.name
        if (name.endsWith("RuntimeClasspath", ignoreCase = true) && !name.contains("Test", ignoreCase = true)) {
            val isAndroid = configuration.attributes.keySet().any { it.name.startsWith("com.android") }
            val isHotReload = name.contains("HotReload", ignoreCase = true)
            if (!isAndroid && !isHotReload) {
                configuration.attributes.attribute(LCD_TEXT_PATCHED, true)
                if (isMultiplatform) {
                    configuration.attributes.attribute(
                        LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                        jarLibraryElements,
                    )
                }
            }
        }
    }

    project.dependencies.artifactTypes.configureEach { artifactType ->
        if (artifactType.name == "jar") {
            artifactType.attributes.attribute(LCD_TEXT_PATCHED, false)
        }
    }
}

/**
 * The ASM surgery for [LcdTextDefaultTransform]: renames the original
 * `getPlatformDefault()` and generates a caching wrapper in its place.
 */
internal object LcdTextClassPatcher {
    private const val FRS = "androidx/compose/ui/text/FontRasterizationSettings"
    private const val COMPANION = "$FRS\$Companion"
    private const val COMPANION_ENTRY = "$COMPANION.class"
    private const val GETTER = "getPlatformDefault"
    private const val GETTER_DESC = "()L$FRS;"
    private const val ORIGINAL = "nucleus\$originalPlatformDefault"
    private const val CACHE_FIELD = "nucleus\$lcdDefault"
    private const val CACHE_FIELD_DESC = "L$FRS;"
    private const val FONT_SMOOTHING = "androidx/compose/ui/text/FontSmoothing"
    private const val FONT_HINTING = "androidx/compose/ui/text/FontHinting"
    private const val CTOR_DESC = "(L$FONT_SMOOTHING;L$FONT_HINTING;ZZ)V"

    /** System property that disables the patched ClearType default at runtime. */
    private const val OPT_OUT_PROPERTY = "nucleus.text.lcd"

    /**
     * Rewrites [input] into [output], patching the Companion class and
     * verifying every member the generated wrapper references (constructor,
     * enum fields) still exists in the artifact — so a Compose layout change
     * fails the build instead of throwing `NoSuchMethodError` at the app's
     * first text layout.
     */
    fun patchJar(
        input: File,
        output: File,
    ) {
        var patched = false
        var ctorPresent = false
        var smoothingPresent = false
        var hintingPresent = false
        JarFile(input).use { jar ->
            JarOutputStream(output.outputStream().buffered()).use { out ->
                for (entry in jar.entries()) {
                    val bytes = jar.getInputStream(entry).use { it.readBytes() }
                    out.putNextEntry(ZipEntry(entry.name))
                    when (entry.name) {
                        COMPANION_ENTRY -> {
                            out.write(patchCompanion(bytes))
                            patched = true
                        }
                        "$FRS.class" -> {
                            ctorPresent = hasMethod(bytes, "<init>", CTOR_DESC)
                            out.write(bytes)
                        }
                        "$FONT_SMOOTHING.class" -> {
                            smoothingPresent = hasField(bytes, "SubpixelAntiAlias")
                            out.write(bytes)
                        }
                        "$FONT_HINTING.class" -> {
                            hintingPresent = hasField(bytes, "Normal")
                            out.write(bytes)
                        }
                        else -> out.write(bytes)
                    }
                    out.closeEntry()
                }
            }
        }
        val missing =
            buildList {
                if (!patched) add(COMPANION_ENTRY)
                if (!ctorPresent) add("FontRasterizationSettings.<init>$CTOR_DESC")
                if (!smoothingPresent) add("FontSmoothing.SubpixelAntiAlias")
                if (!hintingPresent) add("FontHinting.Normal")
            }
        check(missing.isEmpty()) {
            "Nucleus LCD text patch: ${missing.joinToString()} not found in ${input.name}. " +
                "The Compose ui-text layout changed — update LcdTextDefaultTransform " +
                "or disable the patch with -Pnucleus.text.lcd.patch=false."
        }
    }

    private fun hasMethod(
        classBytes: ByteArray,
        name: String,
        descriptor: String,
    ): Boolean {
        var found = false
        ClassReader(classBytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    methodName: String,
                    methodDescriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (methodName == name && methodDescriptor == descriptor) found = true
                    return null
                }
            },
            ClassReader.SKIP_CODE,
        )
        return found
    }

    private fun hasField(
        classBytes: ByteArray,
        name: String,
    ): Boolean {
        var found = false
        ClassReader(classBytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitField(
                    access: Int,
                    fieldName: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ): org.objectweb.asm.FieldVisitor? {
                    if (fieldName == name) found = true
                    return null
                }
            },
            ClassReader.SKIP_CODE,
        )
        return found
    }

    /** Patches the Companion class bytes; fails loudly if the getter is missing. */
    fun patchCompanion(classBytes: ByteArray): ByteArray {
        val reader = ClassReader(classBytes)
        val writer =
            object : ClassWriter(reader, COMPUTE_FRAMES) {
                // COMPUTE_FRAMES only merges identical reference types here; never
                // load application classes to compute a common supertype.
                override fun getCommonSuperClass(
                    type1: String,
                    type2: String,
                ): String = if (type1 == type2) type1 else "java/lang/Object"
            }
        var renamed = false
        val visitor =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    if (name == GETTER && descriptor == GETTER_DESC) {
                        renamed = true
                        return super.visitMethod(access, ORIGINAL, descriptor, signature, exceptions)
                    }
                    return super.visitMethod(access, name, descriptor, signature, exceptions)
                }

                override fun visitEnd() {
                    cv
                        .visitField(
                            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or
                                Opcodes.ACC_VOLATILE or Opcodes.ACC_SYNTHETIC,
                            CACHE_FIELD,
                            CACHE_FIELD_DESC,
                            null,
                            null,
                        ).visitEnd()
                    generateWrapper(cv)
                    super.visitEnd()
                }
            }
        reader.accept(visitor, 0)
        check(renamed) {
            "Nucleus LCD text patch: method $GETTER$GETTER_DESC not found in " +
                "FontRasterizationSettings\$Companion. The Compose ui-text API " +
                "changed — update LcdTextDefaultTransform or disable the patch " +
                "with -Pnucleus.text.lcd.patch=false."
        }
        return writer.toByteArray()
    }

    // Generates:
    //   public final FontRasterizationSettings getPlatformDefault() {
    //       FontRasterizationSettings v = nucleus$lcdDefault;
    //       if (v != null) return v;
    //       v = (os.name startsWith "Windows" && !"false".equals(getProperty("nucleus.text.lcd")))
    //           ? new FontRasterizationSettings(SubpixelAntiAlias, Normal, true, false)
    //           : nucleus$originalPlatformDefault();
    //       nucleus$lcdDefault = v;   // benign race: idempotent value
    //       return v;
    //   }
    @Suppress("LongMethod")
    private fun generateWrapper(cv: ClassVisitor) {
        val mv = cv.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, GETTER, GETTER_DESC, null, null)
        val compute = Label()
        val fallback = Label()
        val store = Label()
        mv.visitCode()
        mv.visitFieldInsn(Opcodes.GETSTATIC, COMPANION, CACHE_FIELD, CACHE_FIELD_DESC)
        mv.visitVarInsn(Opcodes.ASTORE, 1)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitJumpInsn(Opcodes.IFNULL, compute)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitLabel(compute)
        mv.visitLdcInsn("os.name")
        mv.visitLdcInsn("")
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/lang/System",
            "getProperty",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            false,
        )
        mv.visitLdcInsn("Windows")
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "startsWith",
            "(Ljava/lang/String;)Z",
            false,
        )
        mv.visitJumpInsn(Opcodes.IFEQ, fallback)
        mv.visitLdcInsn("false")
        mv.visitLdcInsn(OPT_OUT_PROPERTY)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/lang/System",
            "getProperty",
            "(Ljava/lang/String;)Ljava/lang/String;",
            false,
        )
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "equals",
            "(Ljava/lang/Object;)Z",
            false,
        )
        mv.visitJumpInsn(Opcodes.IFNE, fallback)
        mv.visitTypeInsn(Opcodes.NEW, FRS)
        mv.visitInsn(Opcodes.DUP)
        mv.visitFieldInsn(Opcodes.GETSTATIC, FONT_SMOOTHING, "SubpixelAntiAlias", "L$FONT_SMOOTHING;")
        mv.visitFieldInsn(Opcodes.GETSTATIC, FONT_HINTING, "Normal", "L$FONT_HINTING;")
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, FRS, "<init>", CTOR_DESC, false)
        mv.visitJumpInsn(Opcodes.GOTO, store)
        mv.visitLabel(fallback)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COMPANION, ORIGINAL, GETTER_DESC, false)
        mv.visitLabel(store)
        mv.visitInsn(Opcodes.DUP)
        mv.visitFieldInsn(Opcodes.PUTSTATIC, COMPANION, CACHE_FIELD, CACHE_FIELD_DESC)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }
}
