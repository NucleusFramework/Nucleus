package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.application.internal.FlathubChecks
import dev.nucleusframework.desktop.application.internal.FlathubRepository
import dev.nucleusframework.desktop.application.internal.FlathubSource
import dev.nucleusframework.desktop.application.internal.FlathubSpec
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.Locale

/**
 * Writes the Flathub repository of the app (`linux { flathub { } }`) into [outputDir]: manifest,
 * metainfo, desktop entry, icon, MIME types and, for a subset of architectures, `flathub.json`.
 * [outputDir] may be a clone of the Flathub repository: only these files are replaced, nothing
 * else in it is touched.
 *
 * Each archive comes from `archiveUrl` (downloaded once to hash it), or, with [localArchive], from
 * a local `tar.gz` of the host architecture copied next to the manifest — the local repository
 * loop, where Flathub's sandboxed build reads nothing outside the manifest directory.
 */
// Untracked: outputDir may be a clone of the Flathub repository, holding anything, Flatpak build
// directories included (whose var/run links to /run), which Gradle must not fingerprint. The task
// only writes a few small files and always runs.
@UntrackedTask(because = "Writes into a directory it does not own, such as a clone of the Flathub repository")
abstract class AbstractGenerateFlathubRepositoryTask : DefaultTask() {
    @get:Input
    abstract val spec: Property<FlathubSpec>

    @get:Input
    @get:Optional
    abstract val archiveUrl: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localArchive: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val iconFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Checks the spec and the icon, resolves the archives, then writes the repository. */
    @TaskAction
    fun generate() {
        val spec = spec.get()
        val icon = iconFile.get().asFile
        val problems = FlathubChecks.problems(spec) + listOfNotNull(FlathubChecks.iconProblem(icon.readBytes()))
        if (problems.isNotEmpty()) {
            throw GradleException(
                "linux { flathub { } } is not ready for Flathub:\n" + problems.joinToString("\n") { "  - $it" },
            )
        }

        // Never wiped: outputDir may be a clone of github.com/flathub/<appId>, with its .git and
        // files of its own. Only the files this task writes are replaced.
        val dir = outputDir.get().asFile
        dir.mkdirs()

        val sources = sources(spec, dir)
        dir.resolve(spec.manifestFile).writeText(FlathubRepository.manifest(spec, sources))
        dir.resolve(spec.metainfoFile).writeText(FlathubRepository.metainfo(spec))
        dir.resolve(spec.desktopFile).writeText(FlathubRepository.desktopEntry(spec))
        dir.resolve(spec.iconFile).writeBytes(FlathubRepository.icon(icon.readBytes()))
        writeOrDelete(dir.resolve(spec.mimeFile), FlathubRepository.mimeInfo(spec))
        writeOrDelete(dir.resolve("flathub.json"), FlathubRepository.flathubJson(spec))
        logger.lifecycle("Flathub repository of ${spec.appId} ${spec.version}: $dir")
    }

    private fun sources(
        spec: FlathubSpec,
        dir: File,
    ): List<FlathubSource> {
        val local = localArchive.orNull?.asFile
        if (local != null) {
            // Only the host's architecture exists locally: the manifest builds that one.
            val arch = spec.arches.firstOrNull { it.flatpakArch == hostFlatpakArch() } ?: spec.arches.first()
            local.copyTo(dir.resolve(local.name), overwrite = true)
            return listOf(FlathubSource(arch, local.name, isUrl = false, sha256 = local.inputStream().use(::sha256)))
        }
        val template =
            archiveUrl.orNull
                ?: throw GradleException(
                    "linux { flathub { archiveUrl } } is required (or -Pnucleus.flathub.localArchive)",
                )
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        return spec.arches.map { arch ->
            val url = FlathubRepository.archiveUrl(template, spec.archiveVersion, arch)
            logger.lifecycle("Hashing $url")
            val request = HttpRequest.newBuilder(URI(url)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != HTTP_OK) {
                response.body().close()
                throw GradleException("$url answered ${response.statusCode()}: publish the release first")
            }
            FlathubSource(arch, url, isUrl = true, sha256 = response.body().use(::sha256))
        }
    }

    /** Writes [content], or deletes a file a previous run wrote when it no longer applies. */
    private fun writeOrDelete(
        file: File,
        content: String?,
    ) {
        if (content != null) file.writeText(content) else file.delete()
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun hostFlatpakArch(): String =
        when (System.getProperty("os.arch")) {
            "aarch64", "arm64" -> "aarch64"
            else -> "x86_64"
        }

    private companion object {
        const val HTTP_OK = 200
        const val BUFFER_SIZE = 64 * 1024
    }
}
