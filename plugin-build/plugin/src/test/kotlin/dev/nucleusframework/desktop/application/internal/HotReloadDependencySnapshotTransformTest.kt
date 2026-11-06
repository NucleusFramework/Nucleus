package dev.nucleusframework.desktop.application.internal

import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class HotReloadDependencySnapshotTransformTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `a lazy class can still load after an included build jar is replaced`() {
        val includedBuild = temporaryFolder.newFolder("Nucleus")
        val input = File(includedBuild, "decorated-window-tao.jar")
        val owner = "dev/nucleusframework/window/tao/TaoApplication"
        val helper = "$owner\$requestQuit\$\$inlined\$sortedByDescending\$1"
        writeJar(file = input, classes = listOf(owner, helper))

        val snapshot = transform(input = input, includedBuild = includedBuild)
        assertNotEquals(input, snapshot)

        URLClassLoader(arrayOf(snapshot.toURI().toURL()), null).use { loader ->
            loader.loadClass(owner.replace(oldChar = '/', newChar = '.'))
            writeJar(file = input, classes = listOf(owner))

            assertEquals(
                helper.replace(oldChar = '/', newChar = '.'),
                loader.loadClass(helper.replace(oldChar = '/', newChar = '.')).name,
            )
        }
    }

    @Test
    fun `external dependency jars keep their original path`() {
        val includedBuild = temporaryFolder.newFolder("Nucleus")
        val input = temporaryFolder.newFile("dependency.jar")

        assertEquals(input, transform(input = input, includedBuild = includedBuild))
    }

    private fun transform(
        input: File,
        includedBuild: File,
    ): File {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()
        val artifact = project.objects.fileProperty().fileValue(input)
        val parameters =
            object : HotReloadDependencySnapshotTransform.Parameters {
                override val includedBuildDirectories =
                    project.objects
                        .listProperty(String::class.java)
                        .value(listOf(includedBuild.absolutePath))
            }
        val transform =
            object : HotReloadDependencySnapshotTransform() {
                override val inputArtifact: Provider<FileSystemLocation> = artifact.map { it }

                override fun getParameters(): Parameters = parameters
            }
        val outputDirectory = temporaryFolder.newFolder()
        var result: File? = null
        val outputs =
            object : TransformOutputs {
                override fun file(path: Any): File {
                    val file =
                        when (path) {
                            is Provider<*> -> (path.get() as FileSystemLocation).asFile
                            else -> File(outputDirectory, path.toString())
                        }
                    result = file
                    return file
                }

                override fun dir(path: Any): File = error(message = "Unexpected directory output: $path")
            }

        transform.transform(outputs = outputs)
        return requireNotNull(value = result)
    }

    private fun writeJar(
        file: File,
        classes: List<String>,
    ) {
        JarOutputStream(file.outputStream()).use { output ->
            classes.forEach { name ->
                val writer = ClassWriter(0)
                writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
                writer.visitEnd()

                output.putNextEntry(JarEntry("$name.class"))
                output.write(writer.toByteArray())
                output.closeEntry()
            }
        }
    }
}
