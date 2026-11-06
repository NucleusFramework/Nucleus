package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.FlathubArch
import dev.nucleusframework.desktop.application.dsl.FlathubScreenshot
import net.coobird.thumbnailator.Thumbnails
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Serializable
import java.nio.ByteBuffer
import javax.imageio.ImageIO

/** A file type the app opens, from `fileAssociation`. */
data class FlathubMimeType(
    val type: String,
    val extension: String,
    val description: String,
) : Serializable {
    /** Serialization constants. */
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Every value the Flathub repository is written from, resolved from the DSL (a task input, hence public). */
data class FlathubSpec(
    val appId: String,
    val name: String,
    /** The release the store shows (metainfo); Flathub's stable remote refuses pre-release words in it. */
    val version: String,
    /** The package version, the `{version}` of the archive URL. */
    val archiveVersion: String,
    val releaseDate: String,
    val runtime: String,
    val runtimeVersion: String,
    val sdk: String,
    val finishArgs: List<String>,
    /** Path of the executable inside the archive. */
    val executable: String,
    val summary: String,
    val description: String,
    val projectLicense: String,
    val developerId: String,
    val developerName: String,
    val homepage: String?,
    val bugtrackerUrl: String?,
    val vcsBrowserUrl: String?,
    val categories: List<String>,
    val keywords: List<String>,
    val brandingLight: String?,
    val brandingDark: String?,
    val screenshots: List<FlathubScreenshot>,
    val mimeTypes: List<FlathubMimeType>,
    val urlSchemes: List<String>,
    val arches: List<FlathubArch>,
) : Serializable {
    /** The command, `/app/bin/<command>`, and the directory under `/app/lib` holding the archive. */
    val command: String get() = executable.substringAfterLast('/')

    /** The manifest, the only file of the Flathub repository besides `flathub.json`. */
    val manifestFile: String get() = "$appId.yml"

    /** Paths of the metadata inside the archive, under its top directory: named after [appId] as Flathub expects. */
    val metainfoPath: String get() = "share/metainfo/$appId.metainfo.xml"
    val desktopPath: String get() = "share/applications/$appId.desktop"
    val iconPath: String
        get() = "share/icons/hicolor/${FlathubRepository.ICON_SIZE}x${FlathubRepository.ICON_SIZE}/apps/$appId.png"
    val mimePath: String get() = "share/mime/packages/$appId.xml"

    /** Serialization constants. */
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** One architecture's archive: a URL to download, or a file next to the manifest. */
internal data class FlathubSource(
    val arch: FlathubArch,
    val location: String,
    val isUrl: Boolean,
    val sha256: String,
)

/**
 * The content of a Flathub repository (`github.com/flathub/<appId>`) for an app Flathub repackages
 * from its released `tar.gz`. Flathub wants nothing but the manifest there: the AppStream metainfo,
 * the desktop entry, the icon and the shared-mime-info XML ship upstream, inside the archive under
 * `share/` ([archiveFiles], written by the `Tar` packaging), and the manifest installs them from it.
 * Pure functions, so the content is testable without Gradle.
 */
internal object FlathubRepository {
    /** The installed icon's size: the largest raster icon `flatpak build-export` accepts. */
    const val ICON_SIZE = 512

    /** The installed icon: [png] as is, or scaled down to [ICON_SIZE] when larger. */
    fun icon(png: ByteArray): ByteArray {
        val image = ImageIO.read(ByteArrayInputStream(png))
        if (image.width <= ICON_SIZE) return png
        val scaled =
            Thumbnails
                .of(image)
                .forceSize(ICON_SIZE, ICON_SIZE)
                .imageType(BufferedImage.TYPE_INT_ARGB)
                .asBufferedImage()
        return ByteArrayOutputStream().also { ImageIO.write(scaled, "png", it) }.toByteArray()
    }

    /** The metadata a `tar.gz` carries for Flathub, by path under its top directory. */
    fun archiveFiles(
        spec: FlathubSpec,
        iconPng: ByteArray,
    ): Map<String, ByteArray> =
        buildMap {
            put(spec.desktopPath, desktopEntry(spec).toByteArray())
            put(spec.metainfoPath, metainfo(spec).toByteArray())
            put(spec.iconPath, icon(iconPng))
            mimeInfo(spec)?.let { put(spec.mimePath, it.toByteArray()) }
        }

    private val PLAIN_YAML = Regex("[A-Za-z0-9_./:=+@-]+")

    /** `{version}` and `{arch}` of [template] replaced. */
    fun archiveUrl(
        template: String,
        version: String,
        arch: FlathubArch,
    ): String = template.replace("{version}", version).replace("{arch}", arch.archiveArch)

    fun manifest(
        spec: FlathubSpec,
        sources: List<FlathubSource>,
    ): String =
        buildString {
            val lib = "/app/lib/${spec.command}"
            appendLine("id: ${yaml(spec.appId)}")
            appendLine("runtime: ${yaml(spec.runtime)}")
            appendLine("runtime-version: '${spec.runtimeVersion}'")
            appendLine("sdk: ${yaml(spec.sdk)}")
            appendLine("command: ${yaml(spec.command)}")
            appendLine("separate-locales: false")
            appendLine()
            appendLine("finish-args:")
            spec.finishArgs.forEach { appendLine("  - ${yaml(it)}") }
            appendLine()
            appendLine("modules:")
            appendLine("  - name: ${yaml(spec.command)}")
            appendLine("    buildsystem: simple")
            appendLine("    build-commands:")
            appendLine("      - mkdir -p $lib /app/bin /app/share")
            appendLine("      - cp -a app/. $lib/")
            appendLine("      - ln -s $lib/${spec.executable} /app/bin/${spec.command}")
            // The archive's own metadata (archiveFiles), where Flatpak exports it from.
            appendLine("      - cp -a $lib/share/. /app/share/")
            appendLine("      - rm -r $lib/share")
            appendLine("    sources:")
            for (source in sources) {
                appendLine("      - type: archive")
                appendLine("        only-arches: [${source.arch.flatpakArch}]")
                appendLine("        ${if (source.isUrl) "url" else "path"}: ${yaml(source.location)}")
                appendLine("        sha256: ${source.sha256}")
                appendLine("        dest: app")
            }
        }

    fun desktopEntry(spec: FlathubSpec): String =
        buildString {
            appendLine("[Desktop Entry]")
            appendLine("Type=Application")
            appendLine("Name=${spec.name}")
            appendLine("Comment=${spec.summary}")
            appendLine("Exec=${spec.command} %U")
            appendLine("Icon=${spec.appId}")
            appendLine("Terminal=false")
            appendLine("Categories=${spec.categories.joinToString(";", postfix = ";")}")
            if (spec.keywords.isNotEmpty()) appendLine("Keywords=${spec.keywords.joinToString(";", postfix = ";")}")
            val mime = spec.mimeTypes.map { it.type } + spec.urlSchemes.map { "x-scheme-handler/$it" }
            if (mime.isNotEmpty()) appendLine("MimeType=${mime.joinToString(";", postfix = ";")}")
            appendLine("StartupNotify=true")
        }

    fun mimeInfo(spec: FlathubSpec): String? {
        if (spec.mimeTypes.isEmpty()) return null
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">""")
            for (mime in spec.mimeTypes) {
                appendLine("""  <mime-type type="${xml(mime.type)}">""")
                appendLine("""    <comment>${xml(mime.description)}</comment>""")
                appendLine("""    <glob pattern="*.${xml(mime.extension.removePrefix("."))}"/>""")
                appendLine("  </mime-type>")
            }
            appendLine("</mime-info>")
        }
    }

    /** `flathub.json`, only when the app is not built for every architecture Flathub builds. */
    fun flathubJson(spec: FlathubSpec): String? {
        if (spec.arches.toSet() == FlathubArch.entries.toSet()) return null
        val arches = spec.arches.joinToString(", ") { "\"${it.flatpakArch}\"" }
        return "{\n  \"only-arches\": [$arches]\n}\n"
    }

    @Suppress("LongMethod")
    fun metainfo(spec: FlathubSpec): String =
        buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<component type="desktop-application">""")
            appendLine("  <id>${xml(spec.appId)}</id>")
            appendLine("  <metadata_license>CC0-1.0</metadata_license>")
            appendLine("  <project_license>${xml(spec.projectLicense)}</project_license>")
            appendLine()
            appendLine("  <name>${xml(spec.name)}</name>")
            appendLine("  <summary>${xml(spec.summary)}</summary>")
            appendLine("""  <developer id="${xml(spec.developerId)}">""")
            appendLine("    <name>${xml(spec.developerName)}</name>")
            appendLine("  </developer>")
            appendLine()
            appendLine("  <description>")
            descriptionXml(spec.description).forEach { appendLine("    $it") }
            appendLine("  </description>")
            appendLine()
            appendLine("  <screenshots>")
            spec.screenshots.forEachIndexed { index, shot ->
                appendLine(if (index == 0) """    <screenshot type="default">""" else "    <screenshot>")
                shot.caption?.let { appendLine("      <caption>${xml(it)}</caption>") }
                appendLine("      <image>${xml(shot.url)}</image>")
                appendLine("    </screenshot>")
            }
            appendLine("  </screenshots>")
            appendLine()
            appendLine("""  <launchable type="desktop-id">${xml(spec.appId)}.desktop</launchable>""")
            val urls =
                listOf(
                    "homepage" to spec.homepage,
                    "bugtracker" to spec.bugtrackerUrl,
                    "vcs-browser" to spec.vcsBrowserUrl,
                ).mapNotNull { (type, url) -> url?.let { type to it } }
            if (urls.isNotEmpty()) {
                appendLine()
                urls.forEach { (type, url) -> appendLine("""  <url type="$type">${xml(url)}</url>""") }
            }
            appendLine()
            appendLine("  <categories>")
            spec.categories.forEach { appendLine("    <category>${xml(it)}</category>") }
            appendLine("  </categories>")
            if (spec.keywords.isNotEmpty()) {
                appendLine("  <keywords>")
                spec.keywords.forEach { appendLine("    <keyword>${xml(it)}</keyword>") }
                appendLine("  </keywords>")
            }
            appendLine()
            appendLine("  <provides>")
            appendLine("    <binary>${xml(spec.command)}</binary>")
            spec.mimeTypes.forEach { appendLine("    <mediatype>${xml(it.type)}</mediatype>") }
            appendLine("  </provides>")
            appendLine()
            appendLine("  <supports>")
            appendLine("    <control>pointing</control>")
            appendLine("    <control>keyboard</control>")
            appendLine("  </supports>")
            if (spec.brandingLight != null || spec.brandingDark != null) {
                appendLine()
                appendLine("  <branding>")
                spec.brandingLight?.let {
                    appendLine("""    <color type="primary" scheme_preference="light">$it</color>""")
                }
                spec.brandingDark?.let {
                    appendLine("""    <color type="primary" scheme_preference="dark">$it</color>""")
                }
                appendLine("  </branding>")
            }
            appendLine()
            appendLine("""  <content_rating type="oars-1.1"/>""")
            appendLine()
            appendLine("  <releases>")
            appendLine("""    <release version="${xml(spec.version)}" date="${xml(spec.releaseDate)}"/>""")
            appendLine("  </releases>")
            appendLine("</component>")
        }

    /** Paragraphs separated by blank lines become `<p>`; a block of `- ` lines becomes a `<ul>`. */
    fun descriptionXml(description: String): List<String> =
        description
            .trim()
            .split(Regex("\\n\\s*\\n"))
            .map { block -> block.lines().map(String::trim).filter(String::isNotEmpty) }
            .filter { it.isNotEmpty() }
            .flatMap { lines ->
                if (lines.all { it.startsWith("- ") }) {
                    listOf("<ul>") + lines.map { "  <li>${xml(it.removePrefix("- ").trim())}</li>" } + "</ul>"
                } else {
                    listOf("<p>${xml(lines.joinToString(" "))}</p>")
                }
            }

    private fun xml(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun yaml(text: String): String =
        if (PLAIN_YAML.matches(text) && text != "-") {
            text
        } else {
            "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
}

/** What Flathub's review or linter would reject in a [FlathubSpec] and its icon, checked before writing. */
internal object FlathubChecks {
    private const val MAX_SUMMARY = 35
    private const val MIN_ICON_SIZE = 256
    private const val IHDR_WIDTH = 16
    private const val IHDR_HEIGHT = 20
    private const val IHDR_END = 24
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    private val APP_ID = Regex("[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z_][A-Za-z0-9_-]*){2,}")
    private val COLOR = Regex("#[0-9a-fA-F]{6}")

    // flatpak-builder-lint's is_latest_release_prerelease, word for word.
    private val PRERELEASE = Regex("(alpha|beta|rc|pre(?:view)?|dev(?:el)?)", RegexOption.IGNORE_CASE)

    /** What Flathub's review or linter would reject, as messages; empty when the spec is fine. */
    fun problems(spec: FlathubSpec): List<String> =
        buildList {
            if (!APP_ID.matches(spec.appId)) {
                add("appId '${spec.appId}' must be reverse DNS with at least three components (com.example.App)")
            }
            if (spec.summary.isBlank()) add("summary is required")
            if (spec.summary.length > MAX_SUMMARY) add("summary is longer than $MAX_SUMMARY characters")
            if (spec.summary.endsWith(".")) add("summary must not end with a period")
            if (spec.description.isBlank()) {
                add("description is required (flathub { description } or nativeDistributions.description)")
            }
            if (spec.projectLicense.isBlank()) add("projectLicense is required (SPDX, or LicenseRef-proprietary)")
            if (spec.developerName.isBlank()) {
                add("developerName is required (flathub { developerName } or nativeDistributions.vendor)")
            }
            if (spec.categories.isEmpty()) add("categories are required (flathub { categories } or linux.appCategory)")
            if (spec.screenshots.isEmpty()) add("at least one screenshot is required")
            if (spec.arches.isEmpty()) add("arches must not be empty")
            if (PRERELEASE.containsMatchIn(spec.version)) {
                add(
                    "release version '${spec.version}' reads as a pre-release, which Flathub's stable remote " +
                        "refuses (appstream-latest-release-is-prerelease): set flathub { releaseVersion }",
                )
            }
            listOfNotNull(spec.brandingLight, spec.brandingDark).filterNot(COLOR::matches).forEach {
                add("branding color '$it' must be #rrggbb")
            }
        }

    /** Width and height of a PNG, read from its IHDR chunk, or `null` when [bytes] is not one. */
    fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < IHDR_END || !bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
            return null
        }
        val buffer = ByteBuffer.wrap(bytes)
        return buffer.getInt(IHDR_WIDTH) to buffer.getInt(IHDR_HEIGHT)
    }

    /** The icon problem, if any: Flathub wants a square PNG of at least 256 px. */
    fun iconProblem(bytes: ByteArray): String? {
        val (width, height) = pngSize(bytes) ?: return "the icon must be a PNG"
        return when {
            width != height -> "the icon must be square (it is ${width}x$height)"
            width < MIN_ICON_SIZE -> "the icon must be at least ${MIN_ICON_SIZE}px (it is ${width}px)"
            else -> null
        }
    }
}

/**
 * Reads the entry names of a `tar.gz`, enough to check that a released archive carries the
 * metadata of [FlathubRepository.archiveFiles]. Handles ustar prefixes and GNU long names, the two
 * ways electron-builder's tar spells a path longer than 100 bytes.
 */
internal object FlathubArchive {
    private const val BLOCK = 512
    private const val NAME_LENGTH = 100
    private const val SIZE_OFFSET = 124
    private const val SIZE_LENGTH = 12
    private const val TYPE_OFFSET = 156
    private const val MAGIC_OFFSET = 257
    private const val PREFIX_OFFSET = 345
    private const val PREFIX_LENGTH = 155
    private const val GNU_LONG_NAME = 'L'.code.toByte()
    private const val MAGIC = "ustar"
    private const val READ_BLOCKS = 64

    /** The paths of every entry of the gzipped tar [input], under the archive's top directory. */
    fun entries(input: java.io.InputStream): List<String> {
        val tar = java.util.zip.GZIPInputStream(input, BLOCK * READ_BLOCKS)
        val names = mutableListOf<String>()
        val header = ByteArray(BLOCK)
        var longName: String? = null
        while (tar.readNBytes(header, 0, BLOCK) == BLOCK && header.any { it != 0.toByte() }) {
            val size = field(header, SIZE_OFFSET, SIZE_LENGTH).trim().ifEmpty { "0" }.toLong(radix = 8)
            val padded = (size + BLOCK - 1) / BLOCK * BLOCK
            if (header[TYPE_OFFSET] == GNU_LONG_NAME) {
                longName = String(tar.readNBytes(size.toInt()), Charsets.UTF_8).trimEnd('\u0000')
                tar.skipNBytes(padded - size)
                continue
            }
            val name =
                longName ?: run {
                    val base = field(header, 0, NAME_LENGTH)
                    val ustar = field(header, MAGIC_OFFSET, MAGIC.length) == MAGIC
                    val prefix = if (ustar) field(header, PREFIX_OFFSET, PREFIX_LENGTH) else ""
                    if (prefix.isEmpty()) base else "$prefix/$base"
                }
            longName = null
            name.removePrefix("./").substringAfter('/', "").takeIf { it.isNotEmpty() }?.let(names::add)
            tar.skipNBytes(padded)
        }
        // Drain the stream, so a caller digesting it sees every byte.
        input.transferTo(java.io.OutputStream.nullOutputStream())
        return names
    }

    private fun field(
        header: ByteArray,
        offset: Int,
        length: Int,
    ): String {
        val end = (offset until offset + length).firstOrNull { header[it] == 0.toByte() } ?: (offset + length)
        return String(header, offset, end - offset, Charsets.UTF_8)
    }
}
