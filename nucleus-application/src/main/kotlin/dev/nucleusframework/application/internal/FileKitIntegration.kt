package dev.nucleusframework.application.internal

import dev.nucleusframework.core.runtime.NucleusApp
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.exceptions.FileKitNotInitializedException
import io.github.vinceglb.filekit.filesDir
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger("dev.nucleusframework.application.internal.FileKitIntegration")

/**
 * Initializes FileKit with [NucleusApp.appId] — only when FileKit is on the runtime classpath and
 * the app has not initialized it already.
 *
 * On Windows `FileKit.filesDir` is then `%APPDATA%\<appId>`, exactly the directory the NSIS
 * uninstaller removes with `deleteAppDataOnUninstall`: the plugin passes the Windows package name
 * (= appId) as `win.executableName`, from which electron-builder derives `productFilename`.
 *
 * FileKit is a `compileOnly` dependency: when the app does not ship it, touching [FileKitBootstrap]
 * fails with a [LinkageError] (at verification or first resolution), which is also what an
 * incompatible FileKit version produces. The catch must stay here, outside the class that
 * references FileKit, since that class is the one that fails to load.
 */
internal fun initializeFileKitIfPresent() {
    try {
        FileKitBootstrap.initializeIfUnset(NucleusApp.appId)
    } catch (_: LinkageError) {
        // FileKit absent (or binary-incompatible): nothing to initialize.
    } catch (
        @Suppress("TooGenericExceptionCaught") e: RuntimeException, // never take the app down for this
    ) {
        logger.log(Level.WARNING, "FileKit auto-initialization failed", e)
    }
}

private object FileKitBootstrap {
    fun initializeIfUnset(appId: String) {
        if (isInitialized()) return
        FileKit.init(appId = appId)
        logger.fine { "FileKit initialized with appId=$appId" }
    }

    // `appId` covers `init(appId)`; `filesDir` covers `init(filesDir, cacheDir)`, which sets no
    // appId. Checked in that order because `filesDir` creates the directory it resolves.
    private fun isInitialized(): Boolean = isSet { FileKit.appId } || isSet { FileKit.filesDir }

    private inline fun isSet(probe: () -> Any): Boolean =
        try {
            probe()
            true
        } catch (_: FileKitNotInitializedException) {
            false
        }
}
