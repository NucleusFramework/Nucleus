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

    /** File names of the repository, all named after [appId] as Flathub expects. */
    val manifestFile: String get() = "$appId.yml"
    val metainfoFile: String get() = "$appId.metainfo.xml"
    val desktopFile: String get() = "$appId.desktop"
    val iconFile: String get() = "$appId.png"
    val mimeFile: String get() = "$appId.mime.xml"

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
 * Writes the files of a Flathub repository (`github.com/flathub/<appId>`) for an app Flathub
 * repackages from its released `tar.gz`: the manifest, the AppStream metainfo, the desktop entry
 * and the shared-mime-info XML. Pure functions, so the content is testable without Gradle.
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
            appendLine("      - mkdir -p $lib /app/bin")
            appendLine("      - cp -a app/. $lib/")
            appendLine("      - ln -s $lib/${spec.executable} /app/bin/${spec.command}")
            appendLine("      - install -Dm644 ${spec.desktopFile} -t /app/share/applications")
            appendLine("      - install -Dm644 ${spec.metainfoFile} -t /app/share/metainfo")
            appendLine(
                "      - install -Dm644 ${spec.iconFile} " +
                    "/app/share/icons/hicolor/${ICON_SIZE}x$ICON_SIZE/apps/${spec.iconFile}",
            )
            if (spec.mimeTypes.isNotEmpty()) {
                appendLine("      - install -Dm644 ${spec.mimeFile} /app/share/mime/packages/${spec.appId}.xml")
            }
            appendLine("    sources:")
            for (source in sources) {
                appendLine("      - type: archive")
                appendLine("        only-arches: [${source.arch.flatpakArch}]")
                appendLine("        ${if (source.isUrl) "url" else "path"}: ${yaml(source.location)}")
                appendLine("        sha256: ${source.sha256}")
                appendLine("        dest: app")
            }
            listOfNotNull(
                spec.desktopFile,
                spec.metainfoFile,
                spec.iconFile,
                spec.mimeFile.takeIf { spec.mimeTypes.isNotEmpty() },
            ).forEach {
                appendLine("      - type: file")
                appendLine("        path: $it")
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
            appendLine("""  <launchable type="desktop-id">${xml(spec.desktopFile)}</launchable>""")
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
