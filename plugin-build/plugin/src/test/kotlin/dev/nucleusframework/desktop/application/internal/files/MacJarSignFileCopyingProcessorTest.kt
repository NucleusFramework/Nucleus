package dev.nucleusframework.desktop.application.internal.files

import dev.nucleusframework.desktop.application.internal.ExternalToolRunner
import dev.nucleusframework.desktop.application.internal.MacSigner
import dev.nucleusframework.desktop.application.internal.validation.ValidatedMacOSSigningSettings
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.process.ExecOperations
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * What copying a jar into the macOS app image is allowed to change.
 *
 * Re-zipping an archive rewrites every entry's timestamp, and a local file header sits immediately
 * before the data it describes — so a jar that nothing touched still comes out byte-different on the
 * next build, and a differential update has to refetch it whole. Only jars that actually carry a
 * native library have to be rewritten, and even those must keep their entry timestamps.
 */
class MacJarSignFileCopyingProcessorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val signed = mutableListOf<String>()

    private val project by lazy { ProjectBuilder.builder().withProjectDir(tmp.root).build() }

    /** Records what it was asked to sign; the real tool is never invoked. */
    private val signer by lazy {
        object : MacSigner(runner()) {
            override fun sign(
                file: File,
                entitlements: File?,
                forceEntitlements: Boolean,
            ) {
                signed += file.name
            }

            override val settings: ValidatedMacOSSigningSettings? = null
        }
    }

    private fun runner(): ExternalToolRunner =
        ExternalToolRunner(
            project.objects.property(Boolean::class.java).convention(false),
            project.objects.directoryProperty().convention(project.layout.buildDirectory.dir("logs")),
            (project as ProjectInternal).services.get(ExecOperations::class.java),
        )

    private fun processor() = MacJarSignFileCopyingProcessor(signer, tmp.newFolder(), JVM_RUNTIME_VERSION)

    @Test
    fun `a jar without native libraries is copied byte-for-byte`() {
        val source = jar("plain.jar", listOf("a/A.class" to "class A", "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0"))
        val target = File(tmp.newFolder("out"), source.name)

        processor().copy(source, target)

        assertTrue("no native library to sign", signed.isEmpty())
        assertTrue("the jar must be copied verbatim", source.readBytes().contentEquals(target.readBytes()))
    }

    @Test
    fun `copying the same jar twice yields the same bytes`() {
        // The jar is what a build produces once; the two copies stand for two consecutive builds.
        val source = jar("stable.jar", listOf("a/A.class" to "class A"))
        val first = File(tmp.newFolder("first"), source.name)
        val second = File(tmp.newFolder("second"), source.name)

        processor().copy(source, first)
        processor().copy(source, second)

        assertTrue("two builds must produce the same jar", first.readBytes().contentEquals(second.readBytes()))
    }

    @Test
    fun `a jar carrying a dylib is rewritten, signed, and keeps its entry timestamps`() {
        val source = jar("native.jar", listOf("a/A.class" to "class A", "lib/libfoo.dylib" to "MACH-O"))
        val target = File(tmp.newFolder("out"), source.name)

        processor().copy(source, target)

        assertEquals("the dylib must be signed", listOf("libfoo.dylib"), signed)
        assertNotEquals("the jar had to be rewritten to sign the dylib", source.length(), 0L)
        assertEquals("entry timestamps must survive the rewrite", timestamps(source), timestamps(target))
    }

    private fun jar(
        name: String,
        entries: List<Pair<String, String>>,
    ): File =
        File(tmp.newFolder(), name).also { file ->
            ZipOutputStream(file.outputStream().buffered()).use { zos ->
                entries.forEach { (path, content) ->
                    zos.putNextEntry(ZipEntry(path).apply { time = ENTRY_TIME })
                    zos.write(content.toByteArray())
                    zos.closeEntry()
                }
            }
        }

    private fun timestamps(jar: File): Map<String, Long> =
        ZipFile(jar).use { zip ->
            zip.entries().iterator().asSequence().associate { it.name to it.time }
        }

    private companion object {
        /** JDK 18+, where the JDK no longer needs dylibs unsigned before jpackage runs. */
        const val JVM_RUNTIME_VERSION = 21

        /** A fixed, non-current instant, so a rewritten entry cannot pass by coincidence. */
        const val ENTRY_TIME = 1_600_000_000_000L
    }
}
