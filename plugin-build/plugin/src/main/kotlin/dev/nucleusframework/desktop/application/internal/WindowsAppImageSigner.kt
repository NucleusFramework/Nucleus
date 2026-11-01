package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.SigningAlgorithm
import dev.nucleusframework.desktop.application.dsl.WindowsSigningSettings
import dev.nucleusframework.internal.utils.Arch
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.zip.ZipFile

/**
 * Signs the Windows binaries of an app image with the app's own certificate, before
 * electron-builder packages it.
 *
 * electron-builder runs with `--prepackaged`, which skips its own app signing (`signApp` lives in
 * `doPack`): it only signs the installer, the uninstaller and the AppX/MSI it produces. Without this
 * step the launcher and every DLL inside the installer ship unsigned, and Smart App Control refuses
 * to load them (#748).
 *
 * The launcher executables are always signed. Unless [WindowsSigningSettings.signNativeLibraries] is
 * turned off the DLLs are too — the runtime's, Skiko's, the Nucleus natives, and those packed inside JARs that a
 * library extracts at run time (signed JARs excepted: rewriting an entry would break their digest).
 * A binary that already carries a signature (a JDK vendor's, Microsoft's) keeps it.
 *
 * The JDK's AOT cache records each classpath JAR's size and modification time and is refused when
 * either changes, so an image with a cache has its JAR libraries signed by the AOT task before the
 * training run ([Scope.JarLibraries]); by the time the package task signs the rest, those JARs hold
 * nothing unsigned and are left untouched.
 *
 * The certificate is resolved the way electron-builder resolves it for the installer, so one
 * configuration signs both: `certificateFile` / `certificateSha1` / `certificateSubjectName`, then
 * `WIN_CSC_LINK` / `CSC_LINK`, or Azure Artifact Signing when `azureTenantId` is set.
 */
internal class WindowsAppImageSigner(
    private val settings: WindowsSigningSettings,
    private val description: String,
    private val architecture: Arch,
    private val workDir: File,
    private val runTool: ExternalToolRunner,
    private val logger: Logger,
) {
    /** What [sign] covers. */
    enum class Scope {
        /** The launchers, the loose DLLs and the DLLs inside JARs. */
        AppImage,

        /** Only the DLLs inside JARs: what must be settled before an AOT cache is trained. */
        JarLibraries,
    }

    internal class EmbeddedLibrary(
        val jar: File,
        val entry: String,
        val extracted: File,
    )

    fun sign(
        appDir: File,
        scope: Scope = Scope.AppImage,
    ) {
        val loose =
            if (scope == Scope.JarLibraries) {
                emptyList()
            } else {
                appDir
                    .walk()
                    .filter { it.isFile && it.isLooseCandidate() && PeSignature.isUnsignedPe(it) }
                    .toList()
            }
        workDir.deleteRecursively()
        try {
            val embedded = if (settings.signNativeLibraries) extractUnsignedJarLibraries(appDir) else emptyList()
            val files = loose + embedded.map { it.extracted }
            if (files.isEmpty()) {
                logger.info("No unsigned Windows binary in the app image")
                return
            }
            // jpackage ships the launcher read-only, and signtool rewrites the file in place.
            (loose + embedded.map { it.jar }).forEach { it.setWritable(true) }
            if (!signFiles(files)) return
            embedded.groupBy { it.jar }.forEach { (jar, libraries) -> writeBack(jar, libraries) }
            logger.lifecycle(
                "Signed ${files.size} Windows binaries of the app image" +
                    if (embedded.isEmpty()) "" else " (${embedded.size} inside JARs)",
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun File.isLooseCandidate(): Boolean =
        when (extension.lowercase()) {
            "exe" -> true
            "dll" -> settings.signNativeLibraries
            else -> false
        }

    private fun extractUnsignedJarLibraries(appDir: File): List<EmbeddedLibrary> =
        appDir
            .walk()
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .flatMapIndexed { index, jar -> extractUnsignedLibraries(jar, File(workDir, "jars/$index"), logger) }
            .toList()

    private fun writeBack(
        jar: File,
        libraries: List<EmbeddedLibrary>,
    ) {
        FileSystems.newFileSystem(jar.toPath(), null as ClassLoader?).use { fs ->
            libraries.forEach {
                Files.copy(it.extracted.toPath(), fs.getPath(it.entry), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /** Returns `false` when no certificate is configured, after warning that nothing was signed. */
    private fun signFiles(files: List<File>): Boolean {
        if (settings.azureTenantId != null) {
            signWithArtifactSigning(files)
            return true
        }
        val certificate = resolveCertificate()
        if (certificate == null) {
            logger.warn(
                "Windows signing is enabled but no certificate is configured " +
                    "(certificateFile, certificateSha1, certificateSubjectName, WIN_CSC_LINK); " +
                    "the app image's ${files.size} binaries are left unsigned",
            )
            return false
        }
        signWithSignTool(files, certificate)
        return true
    }

    // --- signtool ---

    private class Certificate(
        val args: List<String>,
        val password: String?,
    )

    private fun resolveCertificate(): Certificate? {
        val password =
            settings.certificatePassword
                ?: System.getenv("WIN_CSC_KEY_PASSWORD")
                ?: System.getenv("CSC_KEY_PASSWORD")
        settings.certificateFile.orNull?.asFile?.let {
            return Certificate(listOf("/f", it.absolutePath), password)
        }
        val sha1 = settings.certificateSha1
        val subject = settings.certificateSubjectName
        if (sha1 != null || subject != null) return Certificate(findStoreCertificate(sha1, subject), null)
        val link = System.getenv("WIN_CSC_LINK")?.takeIf { it.isNotBlank() } ?: System.getenv("CSC_LINK")
        if (link.isNullOrBlank()) return null
        return Certificate(listOf("/f", materializeCscLink(link).absolutePath), password)
    }

    /** Locates a store certificate the way electron-builder does, so `/sha1` gets the right store. */
    private fun findStoreCertificate(
        sha1: String?,
        subject: String?,
    ): List<String> {
        var output = ""
        runTool(
            powershell(),
            powershellArgs(
                "Get-ChildItem -Recurse Cert: -CodeSigningCert | ForEach-Object { " +
                    "'{0}|{1}|{2}' -f \$_.Thumbprint, \$_.PSParentPath, \$_.Subject }",
            ),
            processStdout = { output = it },
        )
        val match =
            output
                .lineSequence()
                .mapNotNull { line -> line.trim().split('|', limit = STORE_FIELDS).takeIf { it.size == STORE_FIELDS } }
                .firstOrNull { (thumbprint, _, certSubject) ->
                    (sha1 == null || thumbprint.equals(sha1, ignoreCase = true)) &&
                        (subject == null || certSubject.contains(subject))
                }
                ?: throw GradleException(
                    "Cannot find the code signing certificate ${subject ?: sha1} in the certificate stores",
                )
        val (thumbprint, parentPath, _) = match
        return buildList {
            add("/sha1")
            add(thumbprint)
            add("/s")
            add(parentPath.substringAfterLast('\\'))
            if ("Certificate::LocalMachine" in parentPath) add("/sm")
        }
    }

    /**
     * `WIN_CSC_LINK` holds a path, a `file:` URL, an `https:` URL or the base64-encoded certificate,
     * as for electron-builder.
     */
    private fun materializeCscLink(link: String): File {
        val path =
            when {
                link.startsWith("file:") -> File(URI(link))
                link.startsWith("~/") -> File(System.getProperty("user.home"), link.substring(2))
                else -> File(link)
            }
        if (path.isFile) return path
        val bytes =
            when {
                link.startsWith("https://") -> URI(link).toURL().openStream().use { it.readBytes() }
                link.startsWith("http://") ->
                    throw GradleException("WIN_CSC_LINK must not download the certificate over plain http")
                else -> decodeBase64Certificate(link)
            }
        return File(workDir, "certificate.p12").apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }

    private fun signWithSignTool(
        files: List<File>,
        certificate: Certificate,
    ) {
        val tool = resolveSignTool()
        val baseArgs =
            signToolArgs(
                algorithm = settings.algorithm,
                timestampServer = settings.timestampServer,
                certificateArgs = certificate.args,
                description = description,
                password = certificate.password,
            )
        chunked(files, baseArgs).forEach { chunk ->
            withRetries("signtool") {
                runUnlogged(
                    tool = tool,
                    args = baseArgs + chunk.map { it.absolutePath },
                    secrets = setOfNotNull(certificate.password),
                )
            }
        }
    }

    /**
     * Runs [tool] outside Gradle's exec, which logs every command line it starts at INFO: `/p`
     * carries the PFX password, which `--info` on CI would print. electron-builder avoids it the
     * same way, by starting signtool itself. The output is logged with [secrets] masked.
     */
    private fun runUnlogged(
        tool: File,
        args: List<String>,
        secrets: Set<String>,
    ): Int {
        val process =
            ProcessBuilder(listOf(tool.absolutePath) + args)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        val masked = secrets.filter { it.isNotEmpty() }.fold(output) { text, secret -> text.replace(secret, "****") }
        if (exitCode == 0) logger.info(masked) else logger.warn("${tool.name} exited with $exitCode:\n$masked")
        return exitCode
    }

    private fun resolveSignTool(): File =
        listOf("SIGNTOOL_PATH", "WINDOWS_SIGNTOOL_PATH")
            .firstNotNullOfOrNull { name -> System.getenv(name)?.takeIf { it.isNotBlank() }?.let(::File) }
            ?: WindowsKitsLocator.locateSignTool(
                when (architecture) {
                    Arch.X64 -> "x64"
                    Arch.Arm64 -> "arm64"
                },
            )
            ?: throw GradleException(
                "Windows signing is enabled but signtool.exe was not found: install the Windows SDK " +
                    "or point SIGNTOOL_PATH at it",
            )

    // --- Azure Artifact Signing ---

    private fun signWithArtifactSigning(files: List<File>) {
        val ps = powershell()
        // Same module bootstrap as electron-builder's WindowsSignAzureManager.
        runTool(
            ps,
            powershellArgs("Install-PackageProvider -Name NuGet -MinimumVersion 2.8.5.201 -Force -Scope CurrentUser"),
            checkExitCodeIsNormal = false,
        )
        runTool(
            ps,
            powershellArgs(
                "Install-Module -Name TrustedSigning -MinimumVersion 0.5.0 -Force -Repository PSGallery " +
                    "-Scope CurrentUser",
            ),
        )
        // -Files is a comma-separated list, so a path holding a comma is signed on its own.
        val (plain, withComma) = files.partition { ',' !in it.absolutePath }
        val batches =
            chunked(plain, emptyList()).map { chunk -> chunk.joinToString(",") { it.absolutePath } } +
                withComma.map { it.absolutePath }
        batches.forEach { batch ->
            val params =
                listOf(
                    "Endpoint" to settings.azureEndpoint,
                    "CertificateProfileName" to settings.azureCertificateProfileName,
                    "CodeSigningAccountName" to settings.azureCodeSigningAccountName,
                    "TimestampRfc3161" to "http://timestamp.acs.microsoft.com",
                    "TimestampDigest" to "SHA256",
                    "FileDigest" to "SHA256",
                    "Files" to batch,
                ).mapNotNull { (name, value) -> value?.let { "-$name '${it.replace("'", "''")}'" } }
                    .joinToString(" ")
            withRetries("Invoke-TrustedSigning") {
                runTool(ps, powershellArgs("Invoke-TrustedSigning $params"), checkExitCodeIsNormal = false).exitValue
            }
        }
    }

    // --- helpers ---

    private fun powershell(): File {
        val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
        return File(systemRoot, "System32\\WindowsPowerShell\\v1.0\\powershell.exe")
    }

    private fun powershellArgs(command: String) = listOf("-NoProfile", "-NonInteractive", "-Command", command)

    /** Runs [action] up to [MAX_ATTEMPTS] times: timestamp servers fail transiently. */
    private fun withRetries(
        label: String,
        action: () -> Int,
    ) {
        repeat(MAX_ATTEMPTS) { attempt ->
            val exitCode = action()
            if (exitCode == 0) return
            if (attempt < MAX_ATTEMPTS - 1) {
                logger.warn("$label failed with exit code $exitCode, retrying in ${RETRY_DELAY_MS}ms")
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
        throw GradleException("$label failed to sign the app image binaries; see its output above")
    }

    internal companion object {
        /**
         * Extracts the unsigned DLLs of [jar] into [targetDir], or none when the JAR is signed:
         * rewriting one of its entries would break its digest.
         */
        fun extractUnsignedLibraries(
            jar: File,
            targetDir: File,
            logger: Logger,
        ): List<EmbeddedLibrary> =
            ZipFile(jar).use { zip ->
                val entries = zip.entries().toList()
                val libraries = entries.filter { !it.isDirectory && it.name.endsWith(".dll", ignoreCase = true) }
                if (libraries.isEmpty()) return@use emptyList()
                if (entries.any { it.name.isJarSignatureFile() }) {
                    logger.info("Leaving the DLLs of signed JAR ${jar.name} as they are")
                    return@use emptyList()
                }
                libraries.mapIndexedNotNull { index, entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    if (!PeSignature.isUnsignedPe(bytes)) return@mapIndexedNotNull null
                    // Named by index, never by the entry name: `../` or `..\` in an entry would
                    // otherwise write outside the work directory (zip slip).
                    val extracted = File(targetDir, "$index.dll").apply { parentFile.mkdirs() }
                    extracted.writeBytes(bytes)
                    EmbeddedLibrary(jar, entry.name, extracted)
                }
            }

        private fun String.isJarSignatureFile(): Boolean {
            if (!startsWith("META-INF/", ignoreCase = true) || indexOf('/', "META-INF/".length) >= 0) return false
            return JAR_SIGNATURE_EXTENSIONS.any { endsWith(it, ignoreCase = true) }
        }

        /**
         * Decodes a base64 certificate strictly: the MIME decoder skips any character outside the
         * alphabet, so a mistyped path or URL would decode into garbage instead of failing here.
         */
        fun decodeBase64Certificate(link: String): ByteArray =
            runCatching {
                // electron-builder also accepts a `data:<mime>;base64,` URL.
                val payload = if (link.startsWith("data:")) link.substringAfter(";base64,", "") else link
                Base64.getDecoder().decode(payload.filterNot { it.isWhitespace() })
            }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: throw GradleException(
                    "WIN_CSC_LINK / CSC_LINK is neither an existing file, an https URL " +
                        "nor a base64-encoded certificate",
                )

        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 15_000L
        private const val MAX_COMMAND_LINE = 30_000

        /** Room for the executable path and quoting on top of the arguments. */
        private const val COMMAND_LINE_OVERHEAD = 300

        /** Quotes and separator around each argument. */
        private const val ARGUMENT_OVERHEAD = 3
        private const val STORE_FIELDS = 3
        private const val DEFAULT_TIMESTAMP_SERVER = "http://timestamp.digicert.com"
        private val JAR_SIGNATURE_EXTENSIONS = listOf(".SF", ".RSA", ".DSA", ".EC")

        /**
         * The `signtool sign` arguments preceding the files, mirroring electron-builder's
         * `computeWindowsSignArgs` for a single hash on the `winCodeSign` 1.0.0 toolset.
         */
        fun signToolArgs(
            algorithm: SigningAlgorithm,
            timestampServer: String?,
            certificateArgs: List<String>,
            description: String,
            password: String?,
            offline: Boolean = System.getenv("ELECTRON_BUILDER_OFFLINE") == "true",
        ): List<String> =
            buildList {
                add("sign")
                // RFC 3161 for SHA-256, the legacy Authenticode protocol otherwise. The configured
                // server is handed to electron-builder as its RFC 3161 one only, so the legacy
                // protocol keeps electron-builder's default server.
                val rfc3161 = algorithm == SigningAlgorithm.Sha256
                if (!offline) {
                    add(if (rfc3161) "/tr" else "/t")
                    add(timestampServer?.takeIf { rfc3161 } ?: DEFAULT_TIMESTAMP_SERVER)
                }
                addAll(certificateArgs)
                add("/fd")
                add(algorithm.id)
                if (!offline && rfc3161) {
                    add("/td")
                    add("sha256")
                }
                add("/d")
                add(description)
                if (!password.isNullOrEmpty()) {
                    add("/p")
                    add(password)
                }
            }

        /** Splits [files] so that no command line exceeds Windows' 32 767-character limit. */
        fun chunked(
            files: List<File>,
            baseArgs: List<String>,
        ): List<List<File>> {
            val baseLength = baseArgs.sumOf { it.length + ARGUMENT_OVERHEAD } + COMMAND_LINE_OVERHEAD
            val chunks = mutableListOf<MutableList<File>>()
            var length = baseLength
            for (file in files) {
                val fileLength = file.absolutePath.length + ARGUMENT_OVERHEAD
                if (chunks.isEmpty() || length + fileLength > MAX_COMMAND_LINE) {
                    chunks += mutableListOf<File>()
                    length = baseLength
                }
                chunks.last() += file
                length += fileLength
            }
            return chunks
        }
    }
}
