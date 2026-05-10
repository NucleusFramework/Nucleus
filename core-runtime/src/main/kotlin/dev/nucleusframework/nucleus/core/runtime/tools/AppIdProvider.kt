package dev.nucleusframework.nucleus.core.runtime.tools

/**
 * Provides a unique, stable application identifier to namespace shared resources
 * (temp files, locks, properties) and avoid conflicts when multiple apps use
 * this library on the same machine.
 *
 * Resolution order (first non-empty wins):
 * 1) System property "nucleus.app.id" (injected by the Nucleus Gradle plugin)
 * 2) Classpath resource "nucleus/nucleus-app.properties" → `app.id` key
 * 3) Main class from system property "sun.java.command" (first token)
 * 4) Fallback to "NucleusApp"
 */
object AppIdProvider {
    private const val FALLBACK_ID = "NucleusApp"
    private const val MAX_ID_LENGTH = 128
    private const val RESOURCE_PATH = "nucleus/nucleus-app.properties"
    private val cached by lazy { computeAppId() }

    fun appId(): String = cached

    @Suppress("TooGenericExceptionCaught")
    private fun computeAppId(): String {
        // 1) System property injected by the plugin
        val sysProp = System.getProperty("nucleus.app.id")?.trim().orEmpty()
        if (sysProp.isNotEmpty()) {
            debugln { "[AppIdProvider] resolved from system property: $sysProp" }
            return sanitize(sysProp)
        }

        // 2) Classpath resource written by the plugin
        try {
            val stream = AppIdProvider::class.java.classLoader?.getResourceAsStream(RESOURCE_PATH)
            if (stream != null) {
                val props = java.util.Properties()
                stream.use { props.load(it) }
                val resId = props.getProperty("app.id")?.trim().orEmpty()
                if (resId.isNotEmpty()) {
                    debugln { "[AppIdProvider] resolved from classpath resource: $resId" }
                    return sanitize(resId)
                }
            }
        } catch (_: Exception) {
            // Ignore — fall through to legacy resolution
        }

        // 3) Legacy: main class from sun.java.command
        val sunCmd = System.getProperty("sun.java.command")?.trim().orEmpty()
        debugln { "[AppIdProvider] sunCmd: $sunCmd" }

        if (sunCmd.isNotEmpty()) {
            val firstToken = sunCmd.split(" ", limit = 2).firstOrNull().orEmpty()
            if (firstToken.isNotEmpty()) return sanitize(firstToken)
        }

        // 4) Fallback
        return FALLBACK_ID
    }

    private fun sanitize(raw: String): String {
        // Replace non-alphanumeric/._- with underscore; trim length if excessively long
        val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.take(MAX_ID_LENGTH).ifEmpty { FALLBACK_ID }
    }
}
