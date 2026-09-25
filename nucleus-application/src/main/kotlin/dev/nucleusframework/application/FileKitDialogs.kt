package dev.nucleusframework.application

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.XdgPortalParent
import io.github.vinceglb.filekit.dialogs.FileKitDialogParent
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs [block] with [settings] parented to this window, so the FileKit dialog it opens is attached
 * to the window instead of floating free:
 *
 * - **Windows**: the window's HWND becomes the dialog's owner.
 * - **Linux X11 / XWayland**: the portal gets `x11:<xid>`.
 * - **Linux Wayland**: the window is exported through `xdg_foreign` for the duration of [block]
 *   and unexported when it returns, which is the lifetime the portal requires.
 * - **macOS**: left unparented — FileKit only accepts an AWT parent there and rejects any other.
 *
 * A [settings] that already carries a parent is passed through untouched, and so is every
 * setting when the window exposes no platform identity (not realized yet, native bridge missing).
 *
 * ```kotlin
 * val window = LocalNucleusWindow.current
 * scope.launch {
 *     val file = window.withFileKitDialogSettings { settings ->
 *         FileKit.openFilePicker(dialogSettings = settings)
 *     }
 * }
 * ```
 *
 * Requires `filekit-dialogs` on the app's classpath; `nucleus-application` never ships it.
 */
public suspend fun <T> NucleusWindow.withFileKitDialogSettings(
    settings: FileKitDialogSettings = FileKitDialogSettings.createDefault(),
    block: suspend (FileKitDialogSettings) -> T,
): T = withDialogParent(settings, { unsafe.taoWindow?.fileKitDialogParent() }, block)

/** A dialog parent plus whatever keeps it valid (the Wayland export), released after the dialog. */
internal class BorrowedDialogParent(
    val parent: FileKitDialogParent,
    private val lease: AutoCloseable? = null,
) : AutoCloseable {
    override fun close() {
        lease?.close()
    }
}

internal suspend fun <T> withDialogParent(
    settings: FileKitDialogSettings,
    resolveParent: () -> BorrowedDialogParent?,
    block: suspend (FileKitDialogSettings) -> T,
): T {
    if (settings.parent != null) return block(settings)
    // The Wayland export blocks until the compositor answers, so keep it off the UI thread.
    val borrowed = withContext(Dispatchers.IO) { resolveParent() } ?: return block(settings)
    return borrowed.use { block(settings.copy(parent = it.parent)) }
}

private fun TaoWindow.fileKitDialogParent(): BorrowedDialogParent? =
    when (Platform.Current) {
        Platform.Windows -> {
            val hwnd = nativeHandle
            if (hwnd == 0L) null else BorrowedDialogParent(FileKitDialogParent.windows(hwnd))
        }
        Platform.Linux ->
            when (val portalParent = xdgPortalParent()) {
                is XdgPortalParent.X11 -> BorrowedDialogParent(FileKitDialogParent.x11(portalParent.xid))
                is XdgPortalParent.Wayland ->
                    BorrowedDialogParent(FileKitDialogParent.wayland(portalParent.handle), lease = portalParent)
                null -> null
            }
        // FileKit (0.16) accepts only an AWT parent on macOS: an NSWindow would make the picker throw.
        else -> null
    }
