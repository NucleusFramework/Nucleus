package dev.nucleusframework.desktop.application.internal.analyzer

import dev.nucleusframework.desktop.application.internal.analyzer.detectors.ClassReferenceCollector
import dev.nucleusframework.desktop.application.internal.analyzer.detectors.OrphanProjectClassDetector
import dev.nucleusframework.desktop.application.internal.analyzer.detectors.ProjectClassFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files

/**
 * Synthetic-bytecode tests for the orphan project-class detector (#441).
 *
 * Mimics the Room pattern: `AppDatabase_Impl` extends `AppDatabase`, is never
 * referenced by any constant pool use-site, and is loaded via `Class.forName(name + "_Impl")`.
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
        val app =
            publicClass(
                "com/example/App",
                superName = "java/lang/Object",
                publicNoArgCtor = true,
                referencedTypes = listOf("com/example/AppDatabase"),
            )

        val facts = listOfNotNull(
            OrphanProjectClassDetector.inspect(base),
            OrphanProjectClassDetector.inspect(impl),
            OrphanProjectClassDetector.inspect(app),
        )
        val classpathRefs =
            ClassReferenceCollector.collect(base) +
                ClassReferenceCollector.collect(impl) +
                ClassReferenceCollector.collect(app)
        val appRefs = ClassReferenceCollector.collect(app)

        assertTrue("AppDatabase should be referenced by App", "com.example.AppDatabase" in classpathRefs)
        assertFalse(
            "AppDatabase_Impl must not appear in any reference set",
            "com.example.AppDatabase_Impl" in classpathRefs,
        )

        val orphans =
            OrphanProjectClassDetector.detect(
                projectFacts = facts,
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
        val user =
            publicClass(
                "com/example/User",
                superName = "java/lang/Object",
                publicNoArgCtor = true,
                referencedTypes = listOf("com/example/Repo", "com/example/Repo_Impl"),
            )

        val facts =
            listOf(base, impl, user).mapNotNull { OrphanProjectClassDetector.inspect(it) }
        val classpathRefs =
            listOf(base, impl, user).fold(emptySet<String>()) { acc, b ->
                acc + ClassReferenceCollector.collect(b)
            }
        val appRefs = ClassReferenceCollector.collect(user)

        val orphans =
            OrphanProjectClassDetector.detect(
                projectFacts = facts,
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

        val facts = listOf(dead, app).mapNotNull { OrphanProjectClassDetector.inspect(it) }
        val classpathRefs =
            ClassReferenceCollector.collect(dead) + ClassReferenceCollector.collect(app)
        val appRefs = ClassReferenceCollector.collect(app)

        val orphans =
            OrphanProjectClassDetector.detect(
                projectFacts = facts,
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

        val bytes = listOf(base, abstractOrphan, privateCtor, app)
        val facts = bytes.mapNotNull { OrphanProjectClassDetector.inspect(it) }
        val classpathRefs =
            bytes.fold(emptySet<String>()) { acc, b -> acc + ClassReferenceCollector.collect(b) }
        val appRefs = ClassReferenceCollector.collect(app)

        val orphans =
            OrphanProjectClassDetector.detect(
                projectFacts = facts,
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

        val bytes = listOf(iface, factory, app)
        val facts = bytes.mapNotNull { OrphanProjectClassDetector.inspect(it) }
        val classpathRefs =
            bytes.fold(emptySet<String>()) { acc, b -> acc + ClassReferenceCollector.collect(b) }
        val appRefs = ClassReferenceCollector.collect(app)

        val orphans =
            OrphanProjectClassDetector.detect(
                projectFacts = facts,
                classpathReferencedTypes = classpathRefs,
                appReferencedTypes = appRefs,
            )

        assertEquals(setOf("com.example.Widget_Factory"), orphans.map { it.type }.toSet())
    }

    @Test
    fun `detectAll registers every public no-arg concrete class`() {
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

        val facts =
            listOf(a, b, abstractC).mapNotNull { OrphanProjectClassDetector.inspect(it) }
        val all = OrphanProjectClassDetector.detectAll(facts)

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

    @Test
    fun `InnerClasses catalog attribute does not count as a use-site reference`() {
        // Class A lists Outer$Hidden in its InnerClasses attribute but never uses it.
        // That must NOT mark Outer$Hidden as referenced (would hide a real orphan).
        val hidden =
            publicClass(
                "com/example/Outer\$Hidden",
                superName = "com/example/Base",
                publicNoArgCtor = true,
            )
        val base =
            publicClass("com/example/Base", superName = "java/lang/Object", abstract = true)
        val app =
            publicClass(
                "com/example/App",
                superName = "java/lang/Object",
                publicNoArgCtor = true,
                referencedTypes = listOf("com/example/Base"),
                // Catalog-only: InnerClasses entry without field/method/type use
                innerClasses = listOf("com/example/Outer\$Hidden" to "com/example/Outer"),
            )

        val refs = ClassReferenceCollector.collect(app)
        assertFalse(
            "InnerClasses must not count as a use-site: $refs",
            "com.example.Outer\$Hidden" in refs,
        )
        assertTrue("com.example.Base" in refs)

        val facts =
            listOf(base, hidden, app).mapNotNull { OrphanProjectClassDetector.inspect(it) }
        val classpathRefs =
            listOf(base, hidden, app).fold(emptySet<String>()) { acc, b ->
                acc + ClassReferenceCollector.collect(b)
            }
        val orphans =
            OrphanProjectClassDetector.detect(
                projectFacts = facts,
                classpathReferencedTypes = classpathRefs,
                appReferencedTypes = ClassReferenceCollector.collect(app),
            )
        assertTrue(
            "Outer\$Hidden should remain an orphan despite InnerClasses on App: $orphans",
            orphans.any { it.type == "com.example.Outer\$Hidden" },
        )
    }

    @Test
    fun `analyzeClasspath single-pass finds Room-shaped orphan on disk`() {
        val root = Files.createTempDirectory("orphan-e2e").toFile()
        try {
            val projectDir = File(root, "project").also { it.mkdirs() }
            writeClass(projectDir, "com/example/AppDatabase", publicClass("com/example/AppDatabase", abstract = true))
            writeClass(
                projectDir,
                "com/example/AppDatabase_Impl",
                publicClass(
                    "com/example/AppDatabase_Impl",
                    superName = "com/example/AppDatabase",
                    publicNoArgCtor = true,
                ),
            )
            writeClass(
                projectDir,
                "com/example/App",
                publicClass(
                    "com/example/App",
                    publicNoArgCtor = true,
                    referencedTypes = listOf("com/example/AppDatabase"),
                ),
            )

            // Dependency JAR that does Class.forName-style concat is not needed —
            // the orphan rule is reference-absence, not call-site recovery.
            val result =
                BytecodeAnalyzer.analyzeClasspath(
                    files = listOf(projectDir),
                    projectClassDirs = listOf(projectDir),
                    detectOrphanProjectClasses = true,
                    reflectionForProjectClasses = false,
                )

            assertEquals(
                setOf("com.example.AppDatabase_Impl"),
                result.projectClassEntries.map { it.type }.toSet(),
            )
            assertTrue(
                result.allReflectionEntries.any { it.type == "com.example.AppDatabase_Impl" },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `inspect returns concretePublicNoArg for valid class`() {
        val bytes =
            publicClass("com/example/Ok", superName = "java/lang/Object", publicNoArgCtor = true)
        val fact = OrphanProjectClassDetector.inspect(bytes)
        assertEquals(
            ProjectClassFact(
                type = "com.example.Ok",
                concretePublicNoArg = true,
                superName = "java.lang.Object",
                interfaces = emptyList(),
            ),
            fact,
        )
    }

    // ── bytecode builders ────────────────────────────────────────────────

    private fun writeClass(
        root: File,
        internalName: String,
        bytes: ByteArray,
    ) {
        val file = File(root, "$internalName.class")
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
    }

    private fun publicClass(
        internalName: String,
        superName: String = "java/lang/Object",
        interfaces: List<String> = emptyList(),
        abstract: Boolean = false,
        publicNoArgCtor: Boolean = true,
        referencedTypes: List<String> = emptyList(),
        innerClasses: List<Pair<String, String?>> = emptyList(),
    ): ByteArray {
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

        for ((inner, outer) in innerClasses) {
            val simple = inner.substringAfterLast('$').substringAfterLast('/')
            cw.visitInnerClass(inner, outer, simple, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)
        }

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
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
            mv.visitCode()
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(1, 1)
            mv.visitEnd()
        }

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
