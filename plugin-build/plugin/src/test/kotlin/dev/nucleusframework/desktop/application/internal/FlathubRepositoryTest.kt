package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.FlathubArch
import dev.nucleusframework.desktop.application.dsl.FlathubScreenshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

private const val BASE = "https://example.com"

class FlathubRepositoryTest {
    private val spec =
        FlathubSpec(
            appId = "com.example.App",
            name = "Example & Co",
            version = "1.2.3",
            archiveVersion = "1.2.3",
            releaseDate = "2026-11-02",
            runtime = "org.gnome.Platform",
            runtimeVersion = "51",
            sdk = "org.gnome.Sdk",
            finishArgs = listOf("--share=ipc", "--socket=wayland"),
            executable = "example-app",
            summary = "Edit example documents",
            description = "First paragraph\ncontinues here.\n\n- one\n- two <b>",
            projectLicense = "LicenseRef-proprietary",
            developerId = "com.example",
            developerName = "Example",
            homepage = BASE,
            bugtrackerUrl = null,
            vcsBrowserUrl = null,
            categories = listOf("Office"),
            keywords = listOf("example"),
            brandingLight = "#ffffff",
            brandingDark = "#000000",
            screenshots =
                listOf(
                    FlathubScreenshot("https://example.com/a.png", "Main"),
                    FlathubScreenshot("https://example.com/b.png", null),
                ),
            mimeTypes = listOf(FlathubMimeType("application/x-example", "exm", "Example document")),
            urlSchemes = listOf("example"),
            arches = listOf(FlathubArch.X86_64, FlathubArch.AARCH64),
        )

    private val sources =
        listOf(
            FlathubSource(FlathubArch.X86_64, "$BASE/app-1.2.3-linux-x64.tar.gz", isUrl = true, sha256 = "aa"),
            FlathubSource(FlathubArch.AARCH64, "$BASE/app-1.2.3-linux-arm64.tar.gz", isUrl = true, sha256 = "bb"),
        )

    @Test
    fun `archive url replaces version and arch`() {
        assertEquals(
            "https://example.com/v1.2.3/app-1.2.3-linux-arm64.tar.gz",
            FlathubRepository.archiveUrl(
                "$BASE/v{version}/app-{version}-linux-{arch}.tar.gz",
                "1.2.3",
                FlathubArch.AARCH64,
            ),
        )
    }

    @Test
    fun `manifest repackages each archive and installs the metadata the archive carries`() {
        val manifest = FlathubRepository.manifest(spec, sources)
        assertTrue(manifest.contains("runtime-version: '51'"))
        assertTrue(manifest.contains("command: example-app"))
        assertTrue(manifest.contains("  - --share=ipc"))
        assertTrue(manifest.contains("      - ln -s /app/lib/example-app/example-app /app/bin/example-app"))
        val arm64 = "only-arches: [aarch64]\n        url: $BASE/app-1.2.3-linux-arm64.tar.gz\n        sha256: bb"
        assertTrue(manifest.contains(arm64))
        // Flathub's review wants nothing but the manifest in its repository: the metadata comes from the archive.
        assertTrue(manifest.contains("      - cp -a /app/lib/example-app/share/. /app/share/\n      - rm -r /app/lib/example-app/share"))
        assertTrue(!manifest.contains("type: file"))
    }

    @Test
    fun `the archive carries the metadata under share`() {
        val files = FlathubRepository.archiveFiles(spec, png(1024, 1024))
        assertEquals(
            setOf(
                "share/applications/com.example.App.desktop",
                "share/metainfo/com.example.App.metainfo.xml",
                "share/icons/hicolor/512x512/apps/com.example.App.png",
                "share/mime/packages/com.example.App.xml",
            ),
            files.keys,
        )
        val icon = ImageIO.read(files.getValue("share/icons/hicolor/512x512/apps/com.example.App.png").inputStream())
        assertEquals(512, icon.width)
        assertTrue(String(files.getValue(spec.metainfoPath)).contains("<launchable type=\"desktop-id\">com.example.App.desktop</launchable>"))
        assertEquals(3, FlathubRepository.archiveFiles(spec.copy(mimeTypes = emptyList()), png(256, 256)).size)
    }

    @Test
    fun `archive entries are read under the top directory, long names included`() {
        val long = "share/icons/hicolor/512x512/apps/" + "a".repeat(120) + ".png"
        val tar =
            ByteArrayOutputStream().also { out ->
                java.util.zip.GZIPOutputStream(out).use { gz ->
                    tarEntry(gz, "app-1.2.3-linux-x64/", ByteArray(0), '5')
                    tarEntry(gz, "././@LongLink", "app-1.2.3-linux-x64/$long".toByteArray(), 'L')
                    tarEntry(gz, "app-1.2.3-linux-x64/" + long.take(90), ByteArray(3), '0')
                    tarEntry(gz, "app-1.2.3-linux-x64/share/metainfo/com.example.App.metainfo.xml", ByteArray(700), '0')
                    gz.write(ByteArray(1024))
                }
            }.toByteArray()
        val raw = java.io.ByteArrayInputStream(tar)
        assertEquals(listOf(long, "share/metainfo/com.example.App.metainfo.xml"), FlathubArchive.entries(raw))
        // Drained: a digest over the stream sees the whole archive.
        assertEquals(0, raw.available())
    }

    @Test
    fun `a jpackage launcher is linked from bin`() {
        val manifest = FlathubRepository.manifest(spec.copy(executable = "bin/ExampleApp"), sources)
        assertTrue(manifest.contains("command: ExampleApp"))
        assertTrue(manifest.contains("ln -s /app/lib/ExampleApp/bin/ExampleApp /app/bin/ExampleApp"))
    }

    @Test
    fun `local archives are paths`() {
        val local = listOf(FlathubSource(FlathubArch.X86_64, "app.tar.gz", isUrl = false, sha256 = "cc"))
        assertTrue(FlathubRepository.manifest(spec, local).contains("        path: app.tar.gz\n        sha256: cc"))
    }

    @Test
    fun `desktop entry opens the file types and url schemes`() {
        val entry = FlathubRepository.desktopEntry(spec)
        assertTrue(entry.contains("Exec=example-app %U"))
        assertTrue(entry.contains("Icon=com.example.App"))
        assertTrue(entry.contains("Categories=Office;"))
        assertTrue(entry.contains("MimeType=application/x-example;x-scheme-handler/example;"))
    }

    @Test
    fun `metainfo escapes and structures the description`() {
        val metainfo = FlathubRepository.metainfo(spec)
        assertTrue(metainfo.contains("<name>Example &amp; Co</name>"))
        assertTrue(metainfo.contains("<p>First paragraph continues here.</p>"))
        assertTrue(metainfo.contains("<li>two &lt;b&gt;</li>"))
        assertTrue(metainfo.contains("""<screenshot type="default">"""))
        assertTrue(metainfo.contains("""<release version="1.2.3" date="2026-11-02"/>"""))
        assertTrue(metainfo.contains("<mediatype>application/x-example</mediatype>"))
        assertTrue(metainfo.contains("<project_license>LicenseRef-proprietary</project_license>"))
        // Well-formed XML.
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(metainfo.byteInputStream())
    }

    @Test
    fun `no mime file without file associations`() {
        assertNull(FlathubRepository.mimeInfo(spec.copy(mimeTypes = emptyList())))
        assertTrue(FlathubRepository.mimeInfo(spec)!!.contains("""<glob pattern="*.exm"/>"""))
    }

    @Test
    fun `flathub json only restricts a subset of arches`() {
        assertNull(FlathubRepository.flathubJson(spec))
        val x64Only = spec.copy(arches = listOf(FlathubArch.X86_64))
        assertEquals("{\n  \"only-arches\": [\"x86_64\"]\n}\n", FlathubRepository.flathubJson(x64Only))
    }

    @Test
    fun `problems name what Flathub would reject`() {
        assertEquals(emptyList<String>(), FlathubChecks.problems(spec))
        val problems =
            FlathubChecks.problems(
                spec.copy(
                    appId = "Example",
                    summary = "A summary that is far too long to fit in.",
                    screenshots = emptyList(),
                ),
            )
        assertEquals(4, problems.size)
    }

    @Test
    fun `a pre-release version is refused like Flathub's stable remote does`() {
        for (version in listOf("3.0.0-dev-202611011507", "1.0-alpha.2", "2.0.0-RC1", "1.0-beta", "4.0-preview")) {
            val problems = FlathubChecks.problems(spec.copy(version = version))
            assertTrue(version, problems.single().contains("pre-release"))
        }
        assertEquals(emptyList<String>(), FlathubChecks.problems(spec.copy(version = "2026.11.01")))
    }

    @Test
    fun `icon must be a square png of 256 px or more`() {
        assertNull(FlathubChecks.iconProblem(png(512, 512)))
        assertTrue(FlathubChecks.iconProblem(png(684, 675))!!.contains("square"))
        assertTrue(FlathubChecks.iconProblem(png(128, 128))!!.contains("256"))
        assertTrue(FlathubChecks.iconProblem(byteArrayOf(1, 2, 3))!!.contains("PNG"))
        assertEquals(64 to 32, FlathubChecks.pngSize(png(64, 32)))
    }

    @Test
    fun `an icon larger than 512 px is scaled down, a smaller one is kept as is`() {
        // flatpak build-export refuses a hicolor/512x512 icon larger than 512 px (Zayit's is 1024 px).
        val scaled = ImageIO.read(FlathubRepository.icon(png(1024, 1024)).inputStream())
        assertEquals(512 to 512, scaled.width to scaled.height)
        val small = png(256, 256)
        assertTrue(FlathubRepository.icon(small).contentEquals(small))
    }

    private fun tarEntry(
        out: java.io.OutputStream,
        name: String,
        content: ByteArray,
        type: Char,
    ) {
        val header = ByteArray(512)
        name.toByteArray().copyInto(header, 0, 0, minOf(name.length, 100))
        "%011o".format(content.size).toByteArray().copyInto(header, 124)
        header[156] = type.code.toByte()
        "ustar".toByteArray().copyInto(header, 257)
        out.write(header)
        out.write(content)
        out.write(ByteArray((512 - content.size % 512) % 512))
    }

    private fun png(
        width: Int,
        height: Int,
    ): ByteArray {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
