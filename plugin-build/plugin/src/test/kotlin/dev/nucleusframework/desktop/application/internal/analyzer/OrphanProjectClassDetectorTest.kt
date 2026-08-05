package dev.nucleusframework.desktop.application.internal.analyzer

import dev.nucleusframework.desktop.application.internal.analyzer.detectors.ClassReferenceCollector
import dev.nucleusframework.desktop.application.internal.analyzer.detectors.OrphanProjectClassDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Synthetic-bytecode tests for the orphan project-class detector (#441).
 *
 * Mimics the Room pattern: `AppDatabase_Impl` extends `AppDatabase`, is never
 * referenced by any constant pool, and is loaded via `Class.forName(name + "_Impl")`.
 */
class OrphanProjectClassDetectorTest {
    @Test
    fun `detects unreferenced concrete subclass with public no-arg ctor`() {
        val base = publicClass("com/example/AppDatabase", superName = "java/lang/Object", abstract = true)
        val impl =
            publicClass(
                "com/example/AppDatabase_Impl",
                superName = "com/example/AppDatabase",
                publicNoArgCtor = true,
            )
        // App code references the base type only (DI graph, field types, …)
        val app =
            publicClass(
                "com/example/App",
                superName = "java/lang/Object",
                publicNoArgCtor = true,
                referencedTypes = listOf("com/example/AppDatabase"),
            )

        val project =
            mapOf(
                "com/example/AppDatabase" to base,
                "com/example/AppDatabase_Impl" to impl,
                "com/example/App" to app,
            )
        val classpathRefs =
            ClassReferenceCollector.collect(base) +
                ClassReferenceCollector.collect(impl) +
                ClassReferenceCollector.collect(app)
        val appRefs = ClassReferenceCollector.collect(app)

        // AppDatabase is referenced by App → not an orphan.
        // AppDatabase_Impl is referenced by nobody → orphan.
        assertTrue(
            "AppDatabase should be referenced by App",
            "com.example.AppDatabase" in classpathRefs,
        )
        assertFalse(
            "AppDatabase_Impl must not appear in any reference set",
            "com.example.AppDatabase_Impl" in classpathRefs,
        )

        val orphans =
            OrphanProjectClassDetector.detect(
                projectClassBytes = project,
                classpathReferencedTypes = classpathRefs,
                appReferencedTypes = appRefs,
            )

        assertEquals(setOf("com.example.AppDatabase_Impl"), orphans.map { it.type }.toSet())
        val entry = orphans.single()
        assertEquals(setOf(MethodSignature("<init>", emptyList())), entry.methods)
        assertFalse(entry.allDeclaredMethods)
        assertFalse(entry.allDeclaredConstructors)
        assertFalse(entry.allDeclaredFields)
    }

    @Test
    fun `skips class that is referenced by other bytecode`() {
        val base = publicClass("com/example/Repo", superName = "java/lang/Object", abstract = true)
        val impl =
            publicClass(
                "com/example/Repo_Impl",
                superName = "com/example/Repo",
                publicNoArgCtor = true,
            )
        // Someone new's the impl directly — not orphaned
        val user =
            publicClass(
                "com/example/User",
                superName = "java/lang/Object",
                publicNoArgCtor = true,
                referencedTypes = listOf("com/example/Repo", "com/example/Repo_Impl"),
            )

        val project =
            mapOf(
                "com/example/Repo" to base,
                "com/example/Repo_Impl" to impl,
                "com/example/User" to user,
            )
        val classpathRefs =
            ClassReferenceCollector.collect(base) +
                ClassReferenceCollector.collect(impl) +
                ClassReferenceCollector.collect(user)
        val appRefs = ClassReferenceCollector.collect(user)

        val orphans =
            OrphanProjectClassDetector.detect(
                projectClassBytes = project,
                classpathReferencedTypes = classpathRefs,
                appReferencedTypes = appRefs,
            )

        assertTrue(
            "Referenced Repo_Impl must not be reported as orphan: $orphans",
            orphans.none { it.type == "com.example.Repo_Impl" },
        )
    }

    @Test
    fun `skips class whose only supertype is Object even if unreferenced`() {
        val dead =
            publicClass(
                "com/example/DeadLeaf",
                superName = "java/lang/Object",
                publicNoArgCtor = true,
            )
        val app =
            publicClass(
                "com/example/App",
                superName = "java/lang/Object",
                publicNoArgCtor = true,
            )

        val project =
            mapOf(
                "com/example/DeadLeaf" to dead,
                "com/example/App" to app,
            )
        val classpathRefs =
            ClassReferenceCollector.collect(dead) + ClassReferenceCollector.collect(app)
        val appRefs = ClassReferenceCollector.collect(app)

        val orphans =
            OrphanProjectClassDetector.detect(
                projectClassBytes = project,
                classpathReferencedTypes = classpathRefs,
                appReferencedTypes = appRefs,
            )

        assertTrue(
            "Bare Object subclass must be filtered by the supertype guard: $orphans",
            orphans.none { it.type == "com.example.DeadLeaf" },
        )
    }

    @Test
    fun `skips abstract class and class without public no-arg ctor`() {
        val base =
            publicClass("com/example/Service", superName = "java/lang/Object", abstract = true)
        val abstractOrphan =
            publicClass(
                "com/example/Service_Impl",
                superName = "com/example/Service",
                abstract = true,
                publicNoArgCtor = true,
            )
        val privateCtor =
            publicClass(
                "com/example/Service_Hidden",
                superName = "com/example/Service",
                publicNoArgCtor = false,
            )
        val app =
            publicClass(
                "com/example/App",
                superName = "java/lang/Object",
                publicNoArgCtor = true,
                referencedTypes = listOf("com/example/Service"),
            )

        val project =
            mapOf(
                "com/example/Service" to base,
                "com/example/Service_Impl" to abstractOrphan,
                "com/example/Service_Hidden" to privateCtor,
                "com/example/App" to app,
            )
        val classpathRefs =
            project.values.fold(emptySet<String>()) { acc, bytes ->
                acc + ClassReferenceCollector.collect(bytes)
            }
        val appRefs = ClassReferenceCollector.collect(app)

        val orphans =
            OrphanProjectClassDetector.detect(
                projectClassBytes = project,
                classpathReferencedTypes = classpathRefs,
                appReferencedTypes = appRefs,
            )

        assertTrue(orphans.isEmpty())
    }

    @Test
    fun `detects orphan that implements an app-referenced interface`() {
        val iface = publicInterface("com/example/Factory")
        val factory =
            publicClass(
                "com/example/Widget_Factory",
                superName = "java/lang/Object",
                interfaces = listOf("com/example/Factory"),
                publicNoArgCtor = true,
            )
        val app =
            publicClass(
                "com/example/App",
                superName = "java/lang/Object",
                publicNoArgCtor = true,
                referencedTypes = listOf("com/example/Factory"),
            )

        val project =
            mapOf(
                "com/example/Factory" to iface,
                "com/example/Widget_Factory" to factory,
                "com/example/App" to app,
            )
        val classpathRefs =
            project.values.fold(emptySet<String>()) { acc, bytes ->
                acc + ClassReferenceCollector.collect(bytes)
            }
        val appRefs = ClassReferenceCollector.collect(app)

        val orphans =
            OrphanProjectClassDetector.detect(
                projectClassBytes = project,
                classpathReferencedTypes = classpathRefs,
                appReferencedTypes = appRefs,
            )

        assertEquals(setOf("com.example.Widget_Factory"), orphans.map { it.type }.toSet())
    }

    @Test
    fun `detectAllProjectClasses registers every public no-arg concrete class`() {
        val a =
            publicClass("com/example/A", superName = "java/lang/Object", publicNoArgCtor = true)
        val b =
            publicClass("com/example/B", superName = "java/lang/Object", publicNoArgCtor = true)
        val abstractC =
            publicClass(
                "com/example/C",
                superName = "java/lang/Object",
                abstract = true,
                publicNoArgCtor = true,
            )

        val all =
            OrphanProjectClassDetector.detectAllProjectClasses(
                mapOf(
                    "com/example/A" to a,
                    "com/example/B" to b,
                    "com/example/C" to abstractC,
                ),
            )

        assertEquals(setOf("com.example.A", "com.example.B"), all.map { it.type }.toSet())
    }

    @Test
    fun `collector excludes self-reference`() {
        val bytes =
            publicClass(
                "com/example/Self",
                superName = "java/lang/Object",
                publicNoArgCtor = true,
            )
        val refs = ClassReferenceCollector.collect(bytes)
        assertFalse(refs.contains("com.example.Self"))
        assertTrue(refs.contains("java.lang.Object"))
    }

    // ── bytecode builders ────────────────────────────────────────────────

    private fun publicClass(
        internalName: String,
        superName: String = "java/lang/Object",
        interfaces: List<String> = emptyList(),
        abstract: Boolean = false,
        publicNoArgCtor: Boolean = true,
        referencedTypes: List<String> = emptyList(),
    ): ByteArray {
        // COMPUTE_FRAMES would try to load our synthetic superclasses — use plain maxs.
        val cw = ClassWriter(0)
        var access = Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER
        if (abstract) access = access or Opcodes.ACC_ABSTRACT
        cw.visit(
            Opcodes.V11,
            access,
            internalName,
            null,
            superName,
            interfaces.toTypedArray().ifEmpty { null },
        )

        if (!abstract) {
            val ctorAccess = if (publicNoArgCtor) Opcodes.ACC_PUBLIC else Opcodes.ACC_PRIVATE
            val mv = cw.visitMethod(ctorAccess, "<init>", "()V", null, null)
            mv.visitCode()
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(1, 1)
            mv.visitEnd()
        } else if (publicNoArgCtor) {
            // Abstract classes may still declare a public ctor for subclasses
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
            mv.visitCode()
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(1, 1)
            mv.visitEnd()
        }

        // Field descriptors count as references (DI-style `val db: AppDatabase`)
        for ((i, ref) in referencedTypes.withIndex()) {
            cw.visitField(
                Opcodes.ACC_PRIVATE,
                "ref$i",
                "L$ref;",
                null,
                null,
            ).visitEnd()
        }

        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun publicInterface(internalName: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            internalName,
            null,
            "java/lang/Object",
            null,
        )
        cw.visitEnd()
        return cw.toByteArray()
    }
}
