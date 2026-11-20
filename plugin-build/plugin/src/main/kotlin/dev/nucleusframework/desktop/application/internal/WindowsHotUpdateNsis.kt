package dev.nucleusframework.desktop.application.internal

import org.gradle.api.logging.Logger
import java.io.File

/**
 * NSIS hooks that let electron-builder's installer run as a hot update, next to a running app laid
 * out by [WindowsHotUpdateLayout].
 *
 * The updater runs the installer with `NUCLEUS_HOT_UPDATE=1` in its environment (inherited by the
 * old version's uninstaller, which the installer runs first). In that mode:
 * - `customCheckAppRunning` does not close the running app — by default electron-builder kills
 *   every process started from the install directory;
 * - `customRemoveFiles` (uninstaller) keeps the old version's files — they are in use, and the new
 *   version deletes the retired `versions\<old>` once the old process has exited.
 *
 * Without the variable (a manual install, an uninstall, a classic update) both reproduce
 * electron-builder's default bodies, copied from the pinned 26.x templates
 * (`allowOnlyOneInstallerInstance.nsh` / `uninstaller.nsh`). Defining `customCheckAppRunning`
 * makes the template skip `getProcessInfo.nsh` and `Var pid`, which the default body needs, so
 * they are declared here.
 *
 * Both macros are guarded with `!ifmacrondef`: a user include script defining its own wins (and
 * [warnOnConflicts] says hot updates are then up to it).
 */
internal object WindowsHotUpdateNsis {
    private val HOOKS = listOf("customCheckAppRunning", "customRemoveFiles")

    val MACROS: String =
        """
        |; --- Nucleus hot update (see WindowsHotUpdateNsis) ---
        |!ifmacrondef customCheckAppRunning
        |  !include "getProcessInfo.nsh"
        |  Var pid
        |
        |  !macro customCheckAppRunning
        |    ReadEnvStr ${'$'}R0 NUCLEUS_HOT_UPDATE
        |    ${'$'}{if} ${'$'}R0 != "1"
        |      !insertmacro IS_POWERSHELL_AVAILABLE
        |      !insertmacro _CHECK_APP_RUNNING
        |    ${'$'}{endIf}
        |  !macroend
        |!endif
        |
        |!ifmacrondef customRemoveFiles
        |  !macro customRemoveFiles
        |    ReadEnvStr ${'$'}R0 NUCLEUS_HOT_UPDATE
        |    ${'$'}{if} ${'$'}R0 == "1"
        |    ${'$'}{andIf} ${'$'}{isUpdated}
        |      DetailPrint "Hot update: the running version keeps its files"
        |    ${'$'}{else}
        |      ${'$'}{if} ${'$'}{isUpdated}
        |        CreateDirectory "${'$'}PLUGINSDIR\old-install"
        |
        |        Push ""
        |        Call un.atomicRMDir
        |        Pop ${'$'}R0
        |
        |        ${'$'}{if} ${'$'}R0 != 0
        |          DetailPrint "File is busy, aborting: ${'$'}R0"
        |
        |          Push ""
        |          Call un.restoreFiles
        |          Pop ${'$'}R0
        |
        |          Abort `Can't rename "${'$'}INSTDIR" to "${'$'}PLUGINSDIR\old-install".`
        |        ${'$'}{endif}
        |      ${'$'}{endif}
        |
        |      SetOutPath ${'$'}TEMP
        |      RMDir /r ${'$'}INSTDIR
        |    ${'$'}{endIf}
        |  !macroend
        |!endif
        |
        """.trimMargin()

    /** Warns when [userInclude] defines a hook the hot update needs, since it then takes over. */
    fun warnOnConflicts(
        userInclude: File,
        logger: Logger,
    ) {
        val text = runCatching { userInclude.readText() }.getOrDefault("")
        val overridden = HOOKS.filter { Regex("""!macro\s+$it\b""").containsMatchIn(text) }
        if (overridden.isNotEmpty()) {
            logger.warn(
                "nsis.includeScript defines ${overridden.joinToString()}; Nucleus keeps yours, so hot " +
                    "updates only work if it honours NUCLEUS_HOT_UPDATE=1 (leave the running app and " +
                    "its files alone); otherwise the app updates the classic way, closing during the install.",
            )
        }
    }
}
