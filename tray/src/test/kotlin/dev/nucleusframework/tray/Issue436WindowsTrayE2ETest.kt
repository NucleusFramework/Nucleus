package dev.nucleusframework.tray

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.tray.internal.WindowsTrayInitializer
import dev.nucleusframework.tray.utils.ComposableIconUtils
import dev.nucleusframework.tray.utils.IconRenderProperties
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end coverage for #436 on Windows: the JVM must hand the native layer a
 * scene-resolution PNG and a multi-frame ICO, and [WindowsTrayInitializer] must
 * accept both as a live notification-area icon plus a menu-item bitmap.
 */
class Issue436WindowsTrayE2ETest {
    @Test
    fun `native tray loads a hidpi master ico and a hidpi menu icon`() {
        if (!isWindows) return

        val trayProps = IconRenderProperties.forCurrentOperatingSystem()
        val menuProps = IconRenderProperties.forMenuItem()
        val trayIco =
            try {
                ComposableIconUtils.renderComposableToIcoBytes(trayProps) { SolidRedIcon() }
            } catch (t: Throwable) {
                fail("failed to render tray ICO master: ${t.message}")
            }
        val menuIco =
            try {
                ComposableIconUtils.renderComposableToIcoBytes(menuProps) { SolidBlueIcon() }
            } catch (t: Throwable) {
                fail("failed to render menu ICO master: ${t.message}")
            }
        val trayPng =
            try {
                ComposableIconUtils.renderComposableToPngBytes(trayProps) { SolidRedIcon() }
            } catch (t: Throwable) {
                fail("failed to render tray PNG master: ${t.message}")
            }

        val (pngW, pngH) = pngSize(trayPng)
        assertEquals(trayProps.sceneWidth, pngW)
        assertEquals(trayProps.sceneHeight, pngH)

        val trayFrames = icoFrameSizes(trayIco)
        assertTrue(trayFrames.size > 1, "tray ICO is not a DPI pyramid: $trayFrames")
        assertTrue(16 in trayFrames && 32 in trayFrames, "tray ICO missing 16/32: $trayFrames")
        assertTrue(trayFrames.max() >= 64, "tray ICO missing ≥64px frame: $trayFrames")

        val menuFrames = icoFrameSizes(menuIco)
        assertTrue(menuFrames.isNotEmpty(), "menu ICO has no frames")
        assertTrue(
            menuFrames.all { it <= menuProps.sceneWidth },
            "menu ICO upscaled past the 64px master: $menuFrames",
        )
        assertTrue(16 in menuFrames, "menu ICO missing the 16px frame: $menuFrames")

        val trayFile = kotlin.io.path.createTempFile(prefix = "issue436-tray-", suffix = ".ico").toFile()
        trayFile.writeBytes(trayIco)
        trayFile.deleteOnExit()

        val (smCxSmallIcon, extracted) = probeLoadImageSize(trayFile)
        assertTrue(extracted > 0, "LoadImageW failed on the multi-frame ICO")
        assertEquals(
            smCxSmallIcon,
            extracted,
            "LoadImageW at SM_CXSMICON=$smCxSmallIcon picked ${extracted}px — the ICO pyramid must supply that size",
        )

        val id = "issue-436-e2e"
        try {
            WindowsTrayInitializer.initialize(
                id = id,
                iconPath = trayFile.absolutePath,
                tooltip = "issue-436",
                menuContent = {
                    Item(
                        label = "HiDPI",
                        iconContent = { SolidBlueIcon() },
                        iconRenderProperties = menuProps,
                    ) {}
                },
            )
            WindowsTrayInitializer.refreshPosition(id)
        } finally {
            WindowsTrayInitializer.dispose(id)
        }
    }

    private fun pngSize(png: ByteArray): Pair<Int, Int> {
        require(png.size >= 24) { "PNG too short: ${png.size}" }
        val buf = ByteBuffer.wrap(png, 16, 8).order(ByteOrder.BIG_ENDIAN)
        return buf.int to buf.int
    }

    private fun icoFrameSizes(ico: ByteArray): List<Int> {
        require(ico.size >= 6) { "ICO too short: ${ico.size}" }
        val count = ico[4].toInt() and 0xFF
        return (0 until count).map { i ->
            val w = ico[6 + i * 16].toInt() and 0xFF
            if (w == 0) 256 else w
        }
    }

    private val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /**
     * Asks user32 to load the ICO at [SM_CXSMICON] — the same size the tray
     * and menu-item bitmaps request after the native DPI fix.
     */
    private fun probeLoadImageSize(ico: File): Pair<Int, Int> {
        val csc =
            File("""C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe""")
        if (!csc.isFile) {
            fail("csc.exe not found; cannot probe LoadImageW against the ICO")
        }
        val dir = ico.parentFile
        val cs = File(dir, "Issue436IcoProbe.cs")
        val exe = File(dir, "Issue436IcoProbe.exe")
        cs.writeText(
            """
            using System;
            using System.Runtime.InteropServices;
            class Issue436IcoProbe {
                [DllImport("user32.dll", CharSet=CharSet.Unicode)]
                static extern IntPtr LoadImage(IntPtr h, string n, uint t, int cx, int cy, uint f);
                [DllImport("user32.dll")] static extern bool DestroyIcon(IntPtr h);
                [DllImport("user32.dll")] static extern bool GetIconInfo(IntPtr h, out ICONINFO i);
                [DllImport("gdi32.dll")] static extern int GetObject(IntPtr h, int n, out BITMAP b);
                [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr h);
                [DllImport("user32.dll")] static extern int GetSystemMetrics(int n);
                [StructLayout(LayoutKind.Sequential)]
                struct ICONINFO { public bool fIcon; public int xHotspot; public int yHotspot; public IntPtr hbmMask; public IntPtr hbmColor; }
                [StructLayout(LayoutKind.Sequential)]
                struct BITMAP { public int bmType; public int bmWidth; public int bmHeight; public int bmWidthBytes; public short bmPlanes; public short bmBitsPixel; public IntPtr bmBits; }
                static int Main(string[] args) {
                    int sm = GetSystemMetrics(49);
                    IntPtr h = LoadImage(IntPtr.Zero, args[0], 1, sm, sm, 0x0010);
                    if (h == IntPtr.Zero) { Console.WriteLine("SM_CXSMICON="+sm+" EXTRACTED=0"); return 2; }
                    ICONINFO info; GetIconInfo(h, out info);
                    BITMAP bmp; GetObject(info.hbmColor, Marshal.SizeOf(typeof(BITMAP)), out bmp);
                    Console.WriteLine("SM_CXSMICON="+sm+" EXTRACTED="+bmp.bmWidth);
                    if (info.hbmColor != IntPtr.Zero) DeleteObject(info.hbmColor);
                    if (info.hbmMask != IntPtr.Zero) DeleteObject(info.hbmMask);
                    DestroyIcon(h);
                    return 0;
                }
            }
            """.trimIndent(),
        )
        val compile =
            ProcessBuilder(csc.absolutePath, "/nologo", "/out:${exe.absolutePath}", cs.absolutePath)
                .redirectErrorStream(true)
                .start()
        val compileOut = compile.inputStream.bufferedReader().readText()
        check(compile.waitFor() == 0) { "csc failed: $compileOut" }
        val run =
            ProcessBuilder(exe.absolutePath, ico.absolutePath)
                .redirectErrorStream(true)
                .start()
        val output = run.inputStream.bufferedReader().readText().trim()
        check(run.waitFor() == 0) { "icon probe failed: $output" }
        val sm =
            Regex("""SM_CXSMICON=(\d+)""")
                .find(output)
                ?.groupValues
                ?.get(1)
                ?.toInt()
                ?: fail("probe output missing SM_CXSMICON: $output")
        val extracted =
            Regex("""EXTRACTED=(\d+)""")
                .find(output)
                ?.groupValues
                ?.get(1)
                ?.toInt()
                ?: fail("probe output missing EXTRACTED: $output")
        return sm to extracted
    }
}

@Composable
private fun SolidRedIcon() {
    Box(Modifier.fillMaxSize().background(Color.Red))
}

@Composable
private fun SolidBlueIcon() {
    Box(Modifier.fillMaxSize().background(Color.Blue))
}
