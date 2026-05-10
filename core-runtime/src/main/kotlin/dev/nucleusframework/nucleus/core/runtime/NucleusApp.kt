package dev.nucleusframework.nucleus.core.runtime

import dev.nucleusframework.nucleus.core.runtime.tools.AppIdProvider
import java.util.Properties

/**
 * Provides access to application metadata injected by the Nucleus Gradle plugin.
 *
 * Resolution order per property (first non-null wins):
 * 1. System property (`nucleus.app.*`)
 * 2. Classpath resource (`nucleus/nucleus-app.properties`)
 * 3. Legacy fallback (for [appId] only: [AppIdProvider])
 */
public object NucleusApp {
    private const val RESOURCE_PATH = "nucleus/nucleus-app.properties"

    private const val PROP_APP_ID = "nucleus.app.id"
    private const val PROP_APP_VERSION = "nucleus.app.version"
    private const val PROP_APP_VENDOR = "nucleus.app.vendor"
    private const val PROP_APP_DESCRIPTION = "nucleus.app.description"
    private const val PROP_APP_NAME = "nucleus.app.name"
    private const val PROP_APP_AUMID = "nucleus.app.aumid"
    private const val PROP_STARTUP_TASK_ID = "nucleus.app.startup.task.id"

    private const val RES_APP_ID = "app.id"
    private const val RES_APP_VERSION = "app.version"
    private const val RES_APP_VENDOR = "app.vendor"
    private const val RES_APP_DESCRIPTION = "app.description"
    private const val RES_APP_NAME = "app.name"
    private const val RES_APP_AUMID = "app.aumid"
    private const val RES_STARTUP_TASK_ID = "startup.task.id"

    private val resourceProps: Properties? by lazy { loadResource() }

    /**
     * The application identifier. Matches the `packageName` configured in the Nucleus DSL.
     * Falls back to [AppIdProvider.appId] if not injected by the plugin.
     */
    @JvmStatic
    public val appId: String by lazy {
        resolve(PROP_APP_ID, RES_APP_ID) ?: AppIdProvider.appId()
    }

    /** The application version, or `null` if not configured. */
    @JvmStatic
    public val version: String? by lazy {
        resolve(PROP_APP_VERSION, RES_APP_VERSION)
    }

    /** The application vendor, or `null` if not configured. */
    @JvmStatic
    public val vendor: String? by lazy {
        resolve(PROP_APP_VENDOR, RES_APP_VENDOR)
    }

    /** The application description, or `null` if not configured. */
    @JvmStatic
    public val description: String? by lazy {
        resolve(PROP_APP_DESCRIPTION, RES_APP_DESCRIPTION)
    }

    /** The application display name (e.g. "Nucleus Demo"), or `null` if not configured. */
    @JvmStatic
    public val appName: String? by lazy {
        resolve(PROP_APP_NAME, RES_APP_NAME)
    }

    /**
     * The Windows Application User Model ID (AUMID).
     * Matches the electron-builder `appId` (e.g. "com.app.NucleusDemo").
     * Used for toast notifications, badge updates, and jump lists.
     * Falls back to [appId] if not explicitly configured.
     */
    @JvmStatic
    public val aumid: String by lazy {
        resolve(PROP_APP_AUMID, RES_APP_AUMID) ?: appId
    }

    /**
     * MSIX `uap5:StartupTask` TaskId auto-injected by the Nucleus Gradle plugin when
     * `appx { addAutoLaunchExtension = true }` is configured. `null` in all other cases
     * (non-MSIX build, extension not enabled). Used as the default TaskId for the
     * `autolaunch` module.
     */
    @JvmStatic
    public val startupTaskId: String? by lazy {
        resolve(PROP_STARTUP_TASK_ID, RES_STARTUP_TASK_ID)
    }

    /** `true` if the Nucleus plugin injected metadata (via system property or classpath resource). */
    @JvmStatic
    public val isConfigured: Boolean by lazy {
        System.getProperty(PROP_APP_ID) != null || resourceProps != null
    }

    private fun resolve(
        systemPropKey: String,
        resourceKey: String,
    ): String? =
        System.getProperty(systemPropKey)?.takeIf { it.isNotBlank() }
            ?: resourceProps?.getProperty(resourceKey)?.takeIf { it.isNotBlank() }

    @Suppress("TooGenericExceptionCaught")
    private fun loadResource(): Properties? =
        try {
            val stream = NucleusApp::class.java.classLoader?.getResourceAsStream(RESOURCE_PATH)
            stream?.use { Properties().apply { load(it) } }
        } catch (_: Exception) {
            null
        }
}
